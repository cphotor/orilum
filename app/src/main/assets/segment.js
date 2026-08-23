/**
 * POC：离屏断点测量器——把一章 HTML 塞进隐藏 iframe，按与 foliate 相同的版式参数排整章，
 * 用 Range 逐「屏」量出断点字符偏移，产出屏级区间 [{start,end}]。
 * 目的：验证「按屏断点」在真实复杂大章上是否稳定可靠，为屏级小节切分提供断点依据。
 *
 * 模块导出；buildReadingCSS/log 由宿主注入（见 reader.html 的 segmentSetup）。
 */
export const segmentPOC = () => {
  let $wrap = null // 复用的离屏容器（hidden via offscreen positioning，非 display:none，需真正 layout）

  // 确保存在离屏容器。display:none 不 layout，必须用绝对定位移出可视区。
  const ensureWrap = () => {
    if ($wrap && $wrap.isConnected) return $wrap
    $wrap = document.createElement('iframe')
    $wrap.style.cssText = 'position:absolute;left:-100000px;top:0;width:1px;height:1px;border:0;visibility:hidden;pointer-events:none;'
    $wrap.setAttribute('tabindex', '-1')
    document.body.append($wrap)
    $wrap.srcdoc = '<!doctype html><html><head><meta charset="utf-8"></head><body></body></html>'
    return $wrap
  }

  /**
   * 断点测量（CSS columns 物理模型对齐）：
   *  内容按多栏横向排布，每列 = 一屏（列宽 contentWidth、列高 contentHeight，纵向填满一列流入下一列）。
   *  「屏断点」= 每一列的最后一个字符后。方法：遍历所有文本节点，用 Range.getBoundingClientRect()
   *  判断节点跨了哪几列；凡文本节点右边界跨到下一列，即递归缩小定位到精确断点字符。
   *  返回 [{start,end}] 字符区间序列（start/end 为相对全章纯文本序号的偏移，POC 近似）。
   */
  async function measureSide(chapterHTML, readingCSS, opts) {
    const { contentWidth, contentHeight, pagePadding } = opts
    const frame = ensureWrap()
    await new Promise(r => (frame.onload = r))
    const doc = frame.contentDocument
    const body = doc.body

    // 复位 + 注入与 foliate 相同的版式：html=整宽(多列容器)、每列高=屏高
    doc.documentElement.style.cssText =
      'box-sizing:border-box;margin:0;padding:0;width:auto;height:auto;overflow:hidden;' +
      `column-width:${contentWidth}px;column-gap:0px;column-fill:auto;position:static;` +
      'min-height:0;min-width:0;'
    body.style.cssText =
      'box-sizing:border-box;margin:0;padding:0;max-width:none;max-height:none;white-space:normal;' +
      `padding-top:${pagePadding.top}px;padding-bottom:${pagePadding.bottom}px;`

    body.innerHTML = chapterHTML
    const style = doc.createElement('style')
    style.textContent = readingCSS
    doc.head.append(style)

    if (doc.fonts?.ready) await doc.fonts.ready
    await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))

    // 收集文本节点并记录全局纯文本序号（按顺序累加，用作 POC 字符偏移近似）
    const walker = doc.createTreeWalker(body, NodeFilter.SHOW_TEXT, null)
    const nodes = []
    let acc = 0
    let node = walker.nextNode()
    while (node) {
      const len = (node.nodeValue ?? '').length
      nodes.push({ node, start: acc })
      acc += len
      node = walker.nextNode()
    }
    const totalText = acc

    // 每列右边界 X（相对 iframe 可视区）。列从左到右排，第 k 列右边界 = (k+1)*contentWidth。
    // 找到内容整体宽度（多列总宽）以确定列数。
    const bodyRect = body.getBoundingClientRect()
    const frameRect = frame.contentDocument.documentElement.getBoundingClientRect()
    // 文本列是从 body 水平排布的；取所有文本节点中最大的右 extent 反推列数
    let maxRight = 0
    const ranges = nodes.map(({ node }) => {
      const range = doc.createRange()
      range.selectNodeContents(node)
      const r = range.getBoundingClientRect()
      if (r.right > maxRight) maxRight = r.right
      return r
    })

    // 列数 = ceil(maxRight / contentWidth)
    const colCount = Math.max(1, Math.ceil((maxRight - (frameRect.left > 0 ? frameRect.left : 0)) / contentWidth))

    // 对每个断点（每 1..colCount-1 列的右边界），找第一个右边界跨过它的文本节点，递归二分定位断点字符。
    const breaks = [{ start: 0 }]
    for (let k = 1; k < colCount; k++) {
      const boundaryX = frameRect.left + k * contentWidth
      // 找到跨过 boundaryX 的文本节点
      let hit = -1
      for (let i = 0; i < ranges.length; i++) {
        if (ranges[i].right > boundaryX && ranges[i].left < boundaryX) { hit = i; break }
        if (ranges[i].left >= boundaryX) { hit = i; break } // 到达下一节点起点即断点
      }
      if (hit < 0) { breaks.push({ start: totalText }); continue }
      const { node, start } = nodes[hit]
      const text = node.nodeValue ?? ''
      // 在该节点内二分找精确断点字符偏移
      const docRange = doc.createRange()
      let lo = 0, hi = text.length
      while (lo < hi) {
        const mid = (lo + hi) >> 1
        docRange.setStart(node, 0); docRange.setEnd(node, mid)
        const rr = docRange.getBoundingClientRect()
        if (rr.right <= boundaryX) lo = mid + 1
        else hi = mid
      }
      breaks.push({ start: start + lo })
    }
    breaks.push({ start: totalText })
    return breaks
  }

  const ctx = { buildReadingCSS: () => '', log: () => {}, settings: {}, view: null }

  /** 调试入口：对当前打开的书第 index 章做断点测量并打日志。 */
  async function chapter(index) {
    const { buildReadingCSS, log, settings, view } = ctx
    const book = view?.book
    if (!book) return log('segmentPOC: no book')
    const sec = book.sections?.[index]
    if (!sec) return log(`segmentPOC: no section ${index}`)
    const html = await sec.load?.()
    const readingCSS = buildReadingCSS()
    const el = document.getElementById('view')
    const cw = (el?.clientWidth || document.documentElement.clientWidth)
    const ch = (el?.clientHeight || document.documentElement.clientHeight)
    const s = settings || {}
    const opts = {
      size: cw,
      contentWidth: Math.max(1, cw - (s.marginLeft || 0) - (s.marginRight || 0)),
      contentHeight: ch - (s.marginTop || 0) - (s.marginBottom || 0),
      pagePadding: {
        top: s.marginTop || 0,
        bottom: s.marginBottom || 0,
      },
    }
    const breaks = await measureSide(html, readingCSS, opts)
    log(`segmentPOC: chapter ${index} -> ${breaks.length} screens`)
    for (let i = 0; i < breaks.length; i++) {
      log(`  break[${i}] start=${breaks[i].start}`)
    }
    return breaks
  }

  return { chapter, measureSide, setCtx: c => Object.assign(ctx, c) }
}