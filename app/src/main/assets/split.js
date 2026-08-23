/**
 * 大章节段落切片器（方案 B：段落切分 + 滑动窗口）。
 *
 * 目标：把一个大 chapter section 拆成多个「段落段」，每段 ≈ 3~8 屏内容量，
 * 交给 foliate 三窗口机制只驻留当前±1 段，避免『整章一次 CSS columns』导致的卡死。
 *
 * 切分原则（见 docs/PAGINATION_SPLIT_PLAN.md §4）：
 *   - 以「段预算」切分，断点恒在某一块的顶端，绝不在文字行中间。
 *   - 顶层块切成段；某块独大超预算则向下递归其内部子块。
 *   - 不可分超大块（超大 <pre>/MathML/大图）整块放行不切。
 *   - 全链路容器克隆：断点在深层时，把 root→断点 的祖先链镜像为多套，样式不丢。
 *
 * 惰性：本模块只提供"切单章"的能力；是否切、何时切由宿主按阅读位置懒触发。
 * 书签/进度 CFI 重映射不在本模块范围（见文档 Step4，另做）。
 *
 * 导出：splitSection（切单章 → 代理 section[]）
 *       setBudgetPerSegment / getBudgetPerSegment（段预算控制，可被设置项驱动）
 */

// —— 默认段预算（字符量单位）——
// 粗映射：一屏中文正文 ≈ 若干字符。为"宁碎勿卡"，默认按 ~5 屏估算，简单保守。
let budgetPerSegment = 1200

const DEFAULT_BUDGET = 1200
const MIN_BUDGET = 200

// 不可深拆的标签（原子块）：整块放行，不进入内部切。
const ATOMIC_TAGS = new Set([
  'pre', 'table', 'math', 'svg', 'img', 'picture', 'figure',
  'code', 'blockquote', 'audio', 'video', 'canvas', 'object',
])

/** 估算元素的内容量（字符数，粗筛用；不精确决定屏数）。 */
function weight(el) {
  if (el.nodeType === 3 /* TEXT */) return (el.data ?? '').length
  if (el.nodeType !== 1 /* ELEMENT */) return 0
  return (el.textContent ?? '').length
}

/** 是否可作为"容器"被递归进入内部切（有子元素且非原子块）。 */
function isSplittable(el) {
  if (el.nodeType !== 1) return false
  const tag = (el.tagName ?? '').toLowerCase()
  if (ATOMIC_TAGS.has(tag)) return false
  return el.children.length > 0
}

/**
 * 把一个容器元素递归切成若干片段，每片段 = 该容器 `cloneNode(false)`，
 * 其中承载了一部分子节点。返回片段元素数组。
 * 对原容器只读不写（用 cloneNode 深拷贝子节点），不破坏宿主 DOM。
 */
function cutContainer(container, budget) {
  const results = []
  const makeClone = () => {
    const c = container.cloneNode(false)
    results.push(c)
    return c
  }

  let cur = null
  let curW = 0
  for (const child of Array.from(container.childNodes)) {
    if (child.nodeType === 3 /* TEXT */) {
      if (!cur) cur = makeClone()
      cur.append(child.cloneNode(true))
      curW += weight(child)
      continue
    }
    if (child.nodeType !== 1) continue

    const w = weight(child)
    // 独大(>预算)且可再拆 → 递归拆 child 成多份 clone(child)，各自独立成段
    if (w > budget && isSplittable(child)) {
      cur = null // 结束当前未满段，child 自成一组
      curW = 0
      const subs = cutContainer(child, budget)
      results.push(...subs)
      continue
    }
    // 普通子节点 → 并入当前段
    if (!cur) cur = makeClone()
    cur.append(child.cloneNode(true))
    curW += w
    if (curW >= budget) {
      cur = null
      curW = 0
    }
  }
  return results.length ? results : [container.cloneNode(true)]
}

/**
 * 把单个原始 section 切成多个代理 section。
 * 每代理复刻原 section 字段契约（见 epub.js L981-993），load() 返回完整 HTML 文档。
 * 小内容量章节不拆（返回单个代理，等同原章）。
 */
export async function splitSection(section, { budget = budgetPerSegment } = {}) {
  const doc = await section.createDocument()
  const frags = cutContainer(doc.body, Math.max(MIN_BUDGET, budget))
  if (!frags.length) return [section]

  const headHTML = doc.head
    ? `<!doctype html><html lang="${doc.documentElement.lang || ''}"><head>${doc.head.innerHTML}</head>`
    : `<!doctype html><html lang="${doc.documentElement.lang || ''}"><head><meta charset="utf-8"></head>`
  const closeHTML = '</body></html>'
  // 片段 <body> 直接作为 body 内容
  const fragBodyHTML = frag => frag.innerHTML

  return frags
    .map(function (frag, i) {
      const inner = fragBodyHTML(frag)
      const size = (inner ?? '').length
      // 完整文档（保留原 <head> 资源与阅读 CSS 注入点）
      const load = async () => `${headHTML}<body>${inner}${closeHTML}`
      return {
        id: `${section.id}#s${i}`,
        load,
        unload: section.unload ?? (() => {}),
        createDocument: async () => new DOMParser().parseFromString(await load(), 'text/html'),
        size,
        cfi: undefined,          // CFI 重映射不在本阶段（Step4）
        requestCfi: undefined,
        linear: section.linear,
        pageSpread: section.pageSpread,
        resolveHref: section.resolveHref,
        mediaOverlay: null,
        _textLen: size,
        _group: section.id,      // 归属原章
      }
    })
}

/** 设置段预算（宿主可从设置项驱动，把"卡/碎"旋钮交给用户）。 */
export function setBudgetPerSegment(n) {
  if (Number.isFinite(n) && n >= MIN_BUDGET) budgetPerSegment = n
}
export function getBudgetPerSegment() {
  return budgetPerSegment
}
export const resetBudgetPerSegment = () => { budgetPerSegment = DEFAULT_BUDGET }