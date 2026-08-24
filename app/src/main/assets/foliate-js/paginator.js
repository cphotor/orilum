const wait = ms => new Promise(resolve => setTimeout(resolve, ms))

const debounce = (f, wait, immediate) => {
    let timeout
    return (...args) => {
        const later = () => {
            timeout = null
            if (!immediate) f(...args)
        }
        const callNow = immediate && !timeout
        if (timeout) clearTimeout(timeout)
        timeout = setTimeout(later, wait)
        if (callNow) f(...args)
    }
}

const lerp = (min, max, x) => x * (max - min) + min
const easeOutQuad = x => 1 - (1 - x) * (1 - x)
const animate = (a, b, duration, ease, render) => new Promise(resolve => {
    let start
    const step = now => {
        start ??= now
        const fraction = Math.min(1, (now - start) / duration)
        render(lerp(a, b, ease(fraction)))
        if (fraction < 1) requestAnimationFrame(step)
        else resolve()
    }
    requestAnimationFrame(step)
})

// collapsed range doesn't return client rects sometimes (or always?)
// try make get a non-collapsed range or element
const uncollapse = range => {
    if (!range?.collapsed) return range
    const { endOffset, endContainer } = range
    if (endContainer.nodeType === 1) {
        const node = endContainer.childNodes[endOffset]
        if (node?.nodeType === 1) return node
        return endContainer
    }
    if (endOffset + 1 < endContainer.length) range.setEnd(endContainer, endOffset + 1)
    else if (endOffset > 1) range.setStart(endContainer, endOffset - 1)
    else return endContainer.parentNode
    return range
}

const makeRange = (doc, node, start, end = start) => {
    const range = doc.createRange()
    range.setStart(node, start)
    range.setEnd(node, end)
    return range
}

// use binary search to find an offset value in a text node
const bisectNode = (doc, node, cb, start = 0, end = node.nodeValue.length) => {
    if (end - start === 1) {
        const result = cb(makeRange(doc, node, start), makeRange(doc, node, end))
        return result < 0 ? start : end
    }
    const mid = Math.floor(start + (end - start) / 2)
    const result = cb(makeRange(doc, node, start, mid), makeRange(doc, node, mid, end))
    return result < 0 ? bisectNode(doc, node, cb, start, mid)
        : result > 0 ? bisectNode(doc, node, cb, mid, end) : mid
}

const { SHOW_ELEMENT, SHOW_TEXT, SHOW_CDATA_SECTION,
    FILTER_ACCEPT, FILTER_REJECT, FILTER_SKIP } = NodeFilter

const filter = SHOW_ELEMENT | SHOW_TEXT | SHOW_CDATA_SECTION

// needed cause there seems to be a bug in `getBoundingClientRect()` in Firefox
// where it fails to include rects that have zero width and non-zero height
// (CSSOM spec says "rectangles [...] of which the height or width is not zero")
// which makes the visible range include an extra space at column boundaries
const getBoundingClientRect = target => {
    let top = Infinity, right = -Infinity, left = Infinity, bottom = -Infinity
    for (const rect of target.getClientRects()) {
        left = Math.min(left, rect.left)
        top = Math.min(top, rect.top)
        right = Math.max(right, rect.right)
        bottom = Math.max(bottom, rect.bottom)
    }
    return new DOMRect(left, top, right - left, bottom - top)
}

const getVisibleRange = (doc, start, end, mapRect) => {
    // first get all visible nodes
    const acceptNode = node => {
        const name = node.localName?.toLowerCase()
        // ignore all scripts, styles, and their children
        if (name === 'script' || name === 'style') return FILTER_REJECT
        if (node.nodeType === 1) {
            const { left, right } = mapRect(node.getBoundingClientRect())
            // no need to check child nodes if it's completely out of view
            if (right < start || left > end) return FILTER_REJECT
            // elements must be completely in view to be considered visible
            // because you can't specify offsets for elements
            if (left >= start && right <= end) return FILTER_ACCEPT
            // TODO: it should probably allow elements that do not contain text
            // because they can exceed the whole viewport in both directions
            // especially in scrolled mode
        } else {
            // ignore empty text nodes
            if (!node.nodeValue?.trim()) return FILTER_SKIP
            // create range to get rect
            const range = doc.createRange()
            range.selectNodeContents(node)
            const { left, right } = mapRect(range.getBoundingClientRect())
            // it's visible if any part of it is in view
            if (right >= start && left <= end) return FILTER_ACCEPT
        }
        return FILTER_SKIP
    }
    const walker = doc.createTreeWalker(doc.body, filter, { acceptNode })
    const nodes = []
    for (let node = walker.nextNode(); node; node = walker.nextNode())
        nodes.push(node)

    // we're only interested in the first and last visible nodes
    const from = nodes[0] ?? doc.body
    const to = nodes[nodes.length - 1] ?? from

    // find the offset at which visibility changes
    const startOffset = from.nodeType === 1 ? 0
        : bisectNode(doc, from, (a, b) => {
            const p = mapRect(getBoundingClientRect(a))
            const q = mapRect(getBoundingClientRect(b))
            if (p.right < start && q.left > start) return 0
            return q.left > start ? -1 : 1
        })
    const endOffset = to.nodeType === 1 ? 0
        : bisectNode(doc, to, (a, b) => {
            const p = mapRect(getBoundingClientRect(a))
            const q = mapRect(getBoundingClientRect(b))
            if (p.right < end && q.left > end) return 0
            return q.left > end ? -1 : 1
        })

    const range = doc.createRange()
    range.setStart(from, startOffset)
    range.setEnd(to, endOffset)
    return range
}

const selectionIsBackward = sel => {
    const range = document.createRange()
    range.setStart(sel.anchorNode, sel.anchorOffset)
    range.setEnd(sel.focusNode, sel.focusOffset)
    return range.collapsed
}

const setSelectionTo = (target, collapse) => {
    let range
    if (target.startContainer) range = target.cloneRange()
    else if (target.nodeType) {
        range = document.createRange()
        range.selectNode(target)
    }
    if (range) {
        const sel = range.startContainer.ownerDocument.defaultView.getSelection()
        sel.removeAllRanges()
        if (collapse === -1) range.collapse(true)
        else if (collapse === 1) range.collapse()
        sel.addRange(range)
    }
}

const getDirection = doc => {
    const { defaultView } = doc
    const { writingMode, direction } = defaultView.getComputedStyle(doc.body)
    const vertical = writingMode === 'vertical-rl'
        || writingMode === 'vertical-lr'
    const rtl = doc.body.dir === 'rtl'
        || direction === 'rtl'
        || doc.documentElement.dir === 'rtl'
    return { vertical, rtl }
}

const getBackground = doc => {
    const bodyStyle = doc.defaultView.getComputedStyle(doc.body)
    return bodyStyle.backgroundColor === 'rgba(0, 0, 0, 0)'
        && bodyStyle.backgroundImage === 'none'
        ? doc.defaultView.getComputedStyle(doc.documentElement).background
        : bodyStyle.background
}

const makeMarginals = (length, part) => Array.from({ length }, () => {
    const div = document.createElement('div')
    const child = document.createElement('div')
    div.append(child)
    child.setAttribute('part', part)
    return div
})

const setStylesImportant = (el, styles) => {
    const { style } = el
    for (const [k, v] of Object.entries(styles)) style.setProperty(k, v, 'important')
}

/** 是否「封面页」：首页（spine[0]）且以一张大图/占满整页的矢量封面为主、几乎无正文。
 *  命中则取消该页四向页边距，使封面图可整屏铺满（object-fit:contain → 至少一维贴边）。
 *  识别须兼容 `<img>`、`<svg><image>` 等常见封面形态（许多 EPU B 封面用 svg 承载）。 */
const isCoverLike = doc => {
    if (!doc?.body) return false
    const hasVisual = doc.body.querySelector('img, svg, picture, video')
    if (!hasVisual) return false
    const textLen = (doc.body.textContent ?? '').trim().length
    return textLen < 500
}

class View {
    #observer = new ResizeObserver(() => this.#queueExpand())
    #expandQueued = false
    #element = document.createElement('div')
    #iframe = document.createElement('iframe')
    #contentRange = document.createRange()
    #overlayer
    #vertical = false
    #rtl = false
    #column = true
    #size
    /** 最近一次 expand 计算的内容列数（屏）。仅作进度计算用（纯 JS 字段，不改布局）。 */
    pageCount = 0
    /** 是否已完成首次尺寸设置（首次必须强制设置，即使 pageCount 为 0，避免 iframe 保持 100% 宽被裁剪）。 */
    #expanded = false
    /** 最近一次据此重设 element/iframe 尺寸的 screen size，检测「size 变了但 pageCount 没变」的尺寸漂移。 */
    #sizedFor = 0
    #layout = {}
    // 四向页边距（px）：由 Paginator 经 #beforeRender 转发，columnize 据此写入每页内容内边距。
    #pageMargin = null
    /** ResizeObserver 回调用 rAF 合并，避免「回调内改尺寸 → loop 警告/自反馈」。 */
    #queueExpand() {
        if (this.#expandQueued) return
        this.#expandQueued = true
        requestAnimationFrame(() => {
            this.#expandQueued = false
            this.expand()
        })
    }
    constructor({ container, onExpand }) {
        this.container = container
        this.onExpand = onExpand
        this.#iframe.setAttribute('part', 'filter')
        this.#element.append(this.#iframe)
        Object.assign(this.#element.style, {
            boxSizing: 'content-box',
            position: 'relative',
            overflow: 'hidden',
            flex: '0 0 auto',
            width: '100%', height: '100%',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
        })
        Object.assign(this.#iframe.style, {
            overflow: 'hidden',
            border: '0',
            display: 'none',
            width: '100%', height: '100%',
        })
        // `allow-scripts` is needed for events because of WebKit bug
        // https://bugs.webkit.org/show_bug.cgi?id=218086
        this.#iframe.setAttribute('sandbox', 'allow-same-origin allow-scripts')
        this.#iframe.setAttribute('scrolling', 'no')
    }
    get element() {
        return this.#element
    }
    get document() {
        return this.#iframe.contentDocument
    }
    async load(src, afterLoad, beforeRender) {
        if (typeof src !== 'string') throw new Error(`${src} is not string`)
        return new Promise(resolve => {
            this.#iframe.addEventListener('load', () => {
                const doc = this.document
                afterLoad?.(doc)

                // it needs to be visible for Firefox to get computed style
                this.#iframe.style.display = 'block'
                const { vertical, rtl } = getDirection(doc)
                this.docBackground = getBackground(doc)
                doc.body.style.background = 'none'
                const background = this.docBackground
                this.#iframe.style.display = 'none'

                this.#vertical = vertical
                this.#rtl = rtl

                this.#contentRange.selectNodeContents(doc.body)
                const layout = beforeRender?.({ vertical, rtl, background })
                this.#iframe.style.display = 'block'
                this.render(layout)
                this.#observer.observe(doc.body)

                // the resize observer above doesn't work in Firefox
                // (see https://bugzilla.mozilla.org/show_bug.cgi?id=1832939)
                // until the bug is fixed we can at least account for font load
                doc.fonts.ready.then(() => this.expand())

                resolve()
            }, { once: true })
            this.#iframe.src = src
        })
    }
    render(layout) {
        if (!layout || !this.document) return
        this.#column = layout.flow !== 'scrolled'
        this.#layout = layout
        // 四向页边距经 layout 传入，供 columnize/scrolled 写入每页内容内边距。
        // 封面页（isCover）：取消四向页边距，令其内容整屏铺满、封面图可全屏显示。
        if (layout.pageMargin) this.#pageMargin = this.isCover
            ? { top: 0, right: 0, bottom: 0, left: 0 } : layout.pageMargin
        if (this.#column) this.columnize(layout)
        else this.scrolled(layout)
    }
    scrolled({ margin, gap, columnWidth }) {
        const vertical = this.#vertical
        const doc = this.document
        setStylesImportant(doc.documentElement, {
            'box-sizing': 'border-box',
            'padding': vertical ? `${margin*1.5}px ${gap}px` : `0 ${gap}px`,
            'column-width': 'auto',
            'height': 'auto',
            'width': 'auto',
        })
        setStylesImportant(doc.body, {
            [vertical ? 'max-height' : 'max-width']: `${columnWidth}px`,
            'margin': 'auto',
        })
        this.setImageSize()
        this.expand()
    }
    columnize({ width, height, margin, gap, columnWidth }) {
        const vertical = this.#vertical
        this.#size = vertical ? height : width

        const doc = this.document
        // 四向独立页边距：作为每页内容内边距，翻页动画随之铺满整屏。
        const t = this.#pageMargin?.top ?? 0
        const r = this.#pageMargin?.right ?? 0
        const b = this.#pageMargin?.bottom ?? 0
        const l = this.#pageMargin?.left ?? 0
        // 水平（LTR）模式下页边距的正确实现：
        // 容器滚动步长 size = 屏幕宽（整屏动画）。要让「内容列推进 == size」避免翻页漂移，
        // 必须把左右边距折叠进列间 gap —— 列宽 = size-l-r、列 gap = l+r，
        // 推进 = (size-l-r)+(l+r) = size，与滚动步长严格一致；每屏恰好显示 [左空l + 正文 + 右空r]。
        // 上下边距作为每列（整行）的内边距写入，逐页生效。
        const contentWidth = Math.max(1, this.#size - r - l)
        setStylesImportant(doc.documentElement, {
            'box-sizing': 'border-box',
            'column-width': vertical ? `${Math.trunc(columnWidth)}px` : `${Math.trunc(contentWidth)}px`,
            'column-gap': vertical ? `${margin}px` : `${r + l}px`,
            'column-fill': 'auto',
            ...(vertical
                ? { 'width': `${width}px` }
                : { 'height': `${height}px` }),
            // 水平：padding 左=l 右=r（首屏左缘/末屏右缘边距，其余页边距由列 gap=l+r 承担）；上下= t/b 每列生效。
            // 垂直：维持原 foliate 对称 gap 处理。
            'padding': vertical
                ? `${t + margin / 2}px ${r + gap}px ${b + margin / 2}px ${l + gap}px`
                : `${t}px ${r}px ${b}px ${l}px`,
            'overflow': 'hidden',
            // force wrap long words
            'overflow-wrap': 'break-word',
            // reset some potentially problematic props
            'position': 'static', 'border': '0', 'margin': '0',
            'max-height': 'none', 'max-width': 'none',
            'min-height': 'none', 'min-width': 'none',
            // fix glyph clipping in WebKit
            '-webkit-line-box-contain': 'block glyphs replaced',
        })
        setStylesImportant(doc.body, {
            'max-height': 'none',
            'max-width': 'none',
            'margin': '0',
        })
        this.setImageSize()
        this.expand()
    }
    setImageSize() {
        const { width, height, margin } = this.#layout
        const vertical = this.#vertical
        const doc = this.document
        // 封面页：取消图片边距余量（effMargin=0），水平模式 max-height 变为确定像素整屏高、
        // max-width 恒 100%（整屏列宽），object-fit:contain → 至少一维贴边，实现整屏封面。
        const effMargin = this.isCover ? 0 : (margin ?? 0)
        for (const el of doc.body.querySelectorAll('img, svg, video')) {
            // 封面矢量图：让 svg 撑满整页（配合封面样式 html,body,body>*{height:100%}）。
            // 关键：许多 svg 封面的 viewBox 比其内嵌 <image> 小（出版社做成裁切封皮），
            // 直接按 viewBox 渲染会裁掉图片右/下内容。把 viewBox 改成 <image> 全图尺寸、
            // 保持 preserveAspectRatio=none，令完整封面拉伸填满整页，不裁切。
            if (this.isCover && el.tagName.toLowerCase() === 'svg') {
                const sub = el.querySelector('image')
                const iw = sub ? parseFloat(sub.getAttribute('width')) : NaN
                const ih = sub ? parseFloat(sub.getAttribute('height')) : NaN
                if (iw > 0 && ih > 0) {
                    el.setAttribute('viewBox', `0 0 ${iw} ${ih}`)
                    el.setAttribute('preserveAspectRatio', 'none')
                }
                setStylesImportant(el, {
                    'width': '100% !important',
                    'height': '100% !important',
                    'max-width': 'none',
                    'max-height': 'none',
                    'object-fit': 'fill',
                    'display': 'block',
                    'page-break-inside': 'avoid',
                    'break-inside': 'avoid',
                    'box-sizing': 'border-box',
                })
                continue
            }
            // preserve max size if they are already set
            const { maxHeight, maxWidth } = doc.defaultView.getComputedStyle(el)
            setStylesImportant(el, {
                'max-height': vertical
                    ? (maxHeight !== 'none' && maxHeight !== '0px' ? maxHeight : '100%')
                    : `${height - effMargin * 2}px`,
                'max-width': vertical
                    ? `${width - effMargin * 2}px`
                    : (maxWidth !== 'none' && maxWidth !== '0px' ? maxWidth : '100%'),
                'object-fit': 'contain',
                'page-break-inside': 'avoid',
                'break-inside': 'avoid',
                'box-sizing': 'border-box',
            })
        }
    }
    expand() {
        const { documentElement } = this.document
        if (this.#column) {
            const side = this.#vertical ? 'height' : 'width'
            const otherSide = this.#vertical ? 'width' : 'height'
            const contentRect = this.#contentRange.getBoundingClientRect()
            const rootRect = documentElement.getBoundingClientRect()
            // offset caused by column break at the start of the page
            // which seem to be supported only by WebKit and only for horizontal writing
            const contentStart = this.#vertical ? 0
                : this.#rtl ? rootRect.right - contentRect.right : contentRect.left - rootRect.left
            const contentSize = contentStart + contentRect[side]
            const pageCount = Math.ceil(contentSize / this.#size)
            // 首次必须强制设置尺寸（即使 pageCount 为 0）：否则 iframe 保持初始 100% 宽被裁剪，
            // 且 #layoutViews 以 100% 宽（1 屏）参与 offset 累加会算出负偏移，导致整页空白。
            // 仅在 pageCount 变化时重设尺寸：多 View 拼接下 expand 可能被反复调用，
            // 每次都重设会触发「expand → 尺寸变 → body 重排 → observer → expand」自反馈循环。
            // 但「pageCount 未变而 #size 已变」时也必须重设：否则 iframe/element 停在旧尺寸，
            // 内容按新尺寸撑开却溢出旧裁剪区 → 右侧被裁 / 水平错位（封面横切、短章偏左同源）。
            if (this.pageCount !== pageCount || !this.#expanded || this.#sizedFor !== this.#size) {
                this.pageCount = pageCount
                this.#sizedFor = this.#size
                const expandedSize = Math.max(pageCount, 1) * this.#size
                this.#element.style.padding = '0'
                this.#iframe.style[side] = `${expandedSize}px`
                this.#element.style[side] = `${expandedSize + this.#size * 2}px`
                this.#iframe.style[otherSide] = '100%'
                this.#element.style[otherSide] = '100%'
                if (this.#overlayer) {
                    this.#overlayer.element.style.margin = '0'
                    this.#overlayer.element.style.left = this.#vertical ? '0' : `${this.#size}px`
                    this.#overlayer.element.style.top = this.#vertical ? `${this.#size}px` : '0'
                    this.#overlayer.element.style[side] = `${expandedSize}px`
                    this.#overlayer.redraw()
                }
                this.onExpand()
                this.#expanded = true
            }
            documentElement.style[side] = `${this.#size}px`
        } else {
            const side = this.#vertical ? 'width' : 'height'
            const otherSide = this.#vertical ? 'height' : 'width'
            const contentSize = documentElement.getBoundingClientRect()[side]
            const expandedSize = contentSize
            const { margin, gap } = this.#layout
            const padding = this.#vertical ? `0 ${gap}px` : `${margin}px 0`
            this.#element.style.padding = padding
            this.#iframe.style[side] = `${expandedSize}px`
            this.#element.style[side] = `${expandedSize}px`
            this.#iframe.style[otherSide] = '100%'
            this.#element.style[otherSide] = '100%'
            if (this.#overlayer) {
                this.#overlayer.element.style.margin = padding
                this.#overlayer.element.style.left = '0'
                this.#overlayer.element.style.top = '0'
                this.#overlayer.element.style[side] = `${expandedSize}px`
                this.#overlayer.redraw()
            }
            this.onExpand()
        }
    }
    set overlayer(overlayer) {
        this.#overlayer = overlayer
        this.#element.append(overlayer.element)
    }
    get overlayer() {
        return this.#overlayer
    }
    destroy() {
        if (this.document) this.#observer.unobserve(this.document.body)
    }
}

// NOTE: everything here assumes the so-called "negative scroll type" for RTL
export class Paginator extends HTMLElement {
    static observedAttributes = [
        'flow', 'gap', 'margin',
        'max-inline-size', 'max-block-size', 'max-column-count',
    ]
    #root = this.attachShadow({ mode: 'closed' })
    #observer = new ResizeObserver(() => this.render())
    #top
    #background
    #container
    #header
    #footer
    /** 主 View（= #viewMap.get(#index)）。窗口迁移时更新。 */
    #view
    /** 章节 View 缓存：index -> View。窗口 = 当前章 + 相邻章（最多 3 个）。 */
    #viewMap = new Map()
    /** index -> 该 View element 在容器内的全局偏移（px）。 */
    #offsets = new Map()
    /** index -> 正在进行的章节加载 Promise（防重入）。 */
    #loadPromises = new Map()
    /** 窗口内全部 View 的总宽度（px），即全局滚动范围。 */
    #totalSize = 0
    /** 最近一次窗口迁移/补偿时间戳，用于抑制补偿引发的滚动链式迁移。 */
    #lastShiftAt = 0
    /** 撑宽占位 div：absolute 定位的 View 不撑开 #container 的 scrollWidth，用它提供滚动宽度。 */
    #sizer
    #vertical = false
    #rtl = false
    #margin = 0
    // 宿主通过 setPageMargins() 注入的四向页边距（px）。作为每页内容内边距应用，
    // 让翻页动画铺满整屏（这是 foliate 的正文 page-选页动画的实际可视区域），
    // 而不是被外框轨道裁切成只在边框内滑动。
    #pageMargin = null
    #index = -1
    #anchor = 0 // anchor view to a fraction (0-1), Range, or Element
    #justAnchored = false
    #locked = false // while true, prevent any further navigation
    #styles
    /** doc -> [beforeStyle, style] 注入节点。用普通 Map（需可迭代应用到所有 View），destroy 时统一清理。 */
    #styleMap = new Map()
    /** doc -> 封面页注入的「整屏铺满」style 节点，destroy 时一并清理。 */
    #coverStyleMap = new Map()
    #mediaQuery = matchMedia('(prefers-color-scheme: dark)')
    #mediaQueryListener
    #scrollBounds
    #lastVisibleRange
    constructor() {
        super()
        this.#root.innerHTML = `<style>
        :host {
            display: block;
            container-type: size;
        }
        :host, #top {
            box-sizing: border-box;
            position: relative;
            overflow: hidden;
            width: 100%;
            height: 100%;
        }
        #top {
            --_gap: 2%;
            --_margin: 0px;
            --_max-inline-size: 720px;
            --_max-block-size: 1440px;
            --_max-column-count: 2;
            --_max-column-count-portrait: 1;
            --_max-column-count-spread: var(--_max-column-count);
            --_half-gap: calc(var(--_gap) / 2);
            --_max-width: calc(var(--_max-inline-size) * var(--_max-column-count-spread));
            --_max-height: var(--_max-block-size);
            display: grid;
            grid-template-columns:
                minmax(var(--_half-gap), 1fr)
                var(--_half-gap)
                minmax(0, calc(var(--_max-width) - var(--_gap)))
                var(--_half-gap)
                minmax(var(--_half-gap), 1fr);
            grid-template-rows:
                minmax(var(--_margin), 1fr)
                minmax(0, var(--_max-height))
                minmax(var(--_margin), 1fr);
            &.vertical {
                --_max-column-count-spread: var(--_max-column-count-portrait);
                --_max-width: var(--_max-block-size);
                --_max-height: calc(var(--_max-inline-size) * var(--_max-column-count-spread));
            }
            @container (orientation: portrait) {
                & {
                    --_max-column-count-spread: var(--_max-column-count-portrait);
                }
                &.vertical {
                    --_max-column-count-spread: var(--_max-column-count);
                }
            }
        }
        #background {
            grid-column: 1 / -1;
            grid-row: 1 / -1;
        }
        #container {
            grid-column: 2 / 5;
            grid-row: 2;
            overflow: hidden;
            /* 多 View 拼接：View element 用绝对定位（Paginator 层），内部布局保持原版。
               撑宽占位 div 提供滚动宽度。position:relative 作为绝对定位的参照。 */
            position: relative;
        }
        :host([flow="scrolled"]) #container {
            grid-column: 1 / -1;
            grid-row: 1 / -1;
            overflow: auto;
        }
        #header {
            grid-column: 3 / 4;
            grid-row: 1;
        }
        #footer {
            grid-column: 3 / 4;
            grid-row: 3;
            align-self: end;
        }
        #header, #footer {
            display: grid;
            height: var(--_margin);
        }
        :is(#header, #footer) > * {
            display: flex;
            align-items: center;
            min-width: 0;
        }
        :is(#header, #footer) > * > * {
            width: 100%;
            overflow: hidden;
            white-space: nowrap;
            text-overflow: ellipsis;
            text-align: center;
            font-size: .75em;
            opacity: .6;
        }
        </style>
        <div id="top">
            <div id="background" part="filter"></div>
            <div id="header"></div>
            <div id="container" part="container"></div>
            <div id="footer"></div>
        </div>
        `

        this.#top = this.#root.getElementById('top')
        this.#background = this.#root.getElementById('background')
        this.#container = this.#root.getElementById('container')
        this.#header = this.#root.getElementById('header')
        this.#footer = this.#root.getElementById('footer')

        this.#observer.observe(this.#container)
        this.#container.addEventListener('scroll', () => this.dispatchEvent(new Event('scroll')))
        this.#container.addEventListener('scroll', debounce(() => {
            if (this.scrolled) {
                if (this.#justAnchored) this.#justAnchored = false
                else this.#afterScroll('scroll')
            }
        }, 250))

        this.addEventListener('relocate', ({ detail }) => {
            if (detail.reason === 'selection') setSelectionTo(this.#anchor, 0)
            else if (detail.reason === 'navigation') {
                if (this.#anchor === 1) setSelectionTo(detail.range, 1)
                else if (typeof this.#anchor === 'number')
                    setSelectionTo(detail.range, -1)
                else setSelectionTo(this.#anchor, -1)
            }
        })
        const checkPointerSelection = debounce((range, sel) => {
            if (!sel.rangeCount) return
            const selRange = sel.getRangeAt(0)
            const backward = selectionIsBackward(sel)
            if (backward && selRange.compareBoundaryPoints(Range.START_TO_START, range) < 0)
                this.prev()
            else if (!backward && selRange.compareBoundaryPoints(Range.END_TO_END, range) > 0)
                this.next()
        }, 700)
        this.addEventListener('load', ({ detail: { doc } }) => {
            let isPointerSelecting = false
            doc.addEventListener('pointerdown', () => isPointerSelecting = true)
            doc.addEventListener('pointerup', () => isPointerSelecting = false)
            let isKeyboardSelecting = false
            doc.addEventListener('keydown', () => isKeyboardSelecting = true)
            doc.addEventListener('keyup', () => isKeyboardSelecting = false)
            doc.addEventListener('selectionchange', () => {
                if (this.scrolled) return
                const range = this.#lastVisibleRange
                if (!range) return
                const sel = doc.getSelection()
                if (!sel.rangeCount) return
                if (isPointerSelecting && sel.type === 'Range')
                    checkPointerSelection(range, sel)
                else if (isKeyboardSelecting) {
                    const selRange = sel.getRangeAt(0).cloneRange()
                    const backward = selectionIsBackward(sel)
                    if (!backward) selRange.collapse()
                    this.#scrollToAnchor(selRange)
                }
            })
            doc.addEventListener('focusin', e => this.scrolled ? null :
                // NOTE: `requestAnimationFrame` is needed in WebKit
                requestAnimationFrame(() => this.#scrollToAnchor(e.target)))
        })

        this.#mediaQueryListener = () => {
            if (!this.#view) return
            this.#replaceBackground(this.#view.docBackground, this.columnCount)
        }
        this.#mediaQuery.addEventListener('change', this.#mediaQueryListener)
    }
    attributeChangedCallback(name, _, value) {
        switch (name) {
            case 'flow':
                this.render()
                break
            case 'gap':
            case 'margin':
            case 'max-block-size':
            case 'max-column-count':
                this.#top.style.setProperty('--_' + name, value)
                this.render()
                break
            case 'max-inline-size':
                // needs explicit `render()` as it doesn't necessarily resize
                this.#top.style.setProperty('--_' + name, value)
                this.render()
                break
        }
    }
    /** 宿主注入四向页边距（px，可独立不等）。存为内容内边距（columnize 应用），
     *  使翻页动画覆盖整屏，边距随正文一起滑动。 */
    setPageMargins({ top = 0, right = 0, bottom = 0, left = 0 } = {}) {
        this.#pageMargin = { top, right, bottom, left }
        this.render()
    }
    /** 只要有封面色，就让网格保持整宽，避免用窄栏先渲染封面再拉宽产生的「先白边后消除」闪烁。
     *  封面被当作普通阅读页套了栏约束，容器比视口窄。直接改写 #top 网格列令
     *  #container（第 2~4 列）独占整排宽；窗口内无封面时恢复可读窄栏（正文不受影响）。 */
    #syncCoverGrid() {
        const hasCover = [...this.#viewMap.values()].some(v => v.isCover)
        const overridden = this.#top.style.getPropertyValue('grid-template-columns')
        if (hasCover !== (overridden !== '')) {
            if (hasCover)
                this.#top.style.setProperty('grid-template-columns', '0px 0px minmax(0, 1fr) 0px 0px')
            else
                this.#top.style.removeProperty('grid-template-columns')
            void this.#top.offsetWidth // 强制 reflow
        }
    }
    open(book) {
        this.bookDir = book.dir
        this.sections = book.sections
        book.transformTarget?.addEventListener('data', ({ detail }) => {
            if (detail.type !== 'text/css') return
            const w = innerWidth
            const h = innerHeight
            detail.data = Promise.resolve(detail.data).then(data => data
                // unprefix as most of the props are (only) supported unprefixed
                .replace(/(?<=[{\s;])-epub-/gi, '')
                // replace vw and vh as they cause problems with layout
                .replace(/(\d*\.?\d+)vw/gi, (_, d) => parseFloat(d) * w / 100 + 'px')
                .replace(/(\d*\.?\d+)vh/gi, (_, d) => parseFloat(d) * h / 100 + 'px')
                // `page-break-*` unsupported in columns; replace with `column-break-*`
                .replace(/page-break-(after|before|inside)\s*:/gi, (_, x) =>
                    `-webkit-column-break-${x}:`)
                .replace(/break-(after|before|inside)\s*:\s*(avoid-)?page/gi, (_, x, y) =>
                    `break-${x}: ${y ?? ''}column`))
        })
    }
    /** 是否启用多 View 拼接（仅 LTR 水平分页；竖排/滚动式保持单 View 兼容）。 */
    get #multi() {
        return !this.scrolled && !this.#vertical && !this.#rtl
    }
    /** 加载章节 View（防重入）。主 View 需 await；邻接可后台加载。 */
    #loadView(index) {
        if (this.#viewMap.has(index)) return Promise.resolve(this.#viewMap.get(index))
        if (this.#loadPromises.has(index)) return this.#loadPromises.get(index)
        const promise = (async () => {
            const src = await this.sections[index].load()
            const view = new View({
                container: this,
                onExpand: () => this.#relayoutAfterExpand(),
            })
            this.#container.append(view.element)
            const afterLoad = doc => {
                // 封面页识别：首页且「以一张大图为主、几乎无正文」→ 取消该页四向边距并置零 body 边距，
                // 使封面图整屏铺满。追加在 head 末尾（晚于宿主注入的全局阅读样式）以高优先级覆盖。
                if (index === 0 && isCoverLike(doc)) {
                    view.isCover = true
                    const $coverStyle = doc.createElement('style')
                    $coverStyle.textContent =
                        'html, body, body > * { margin: 0 !important; padding: 0 !important; height: 100% !important; min-height: 100% !important; }'
                    doc.head.append($coverStyle)
                    this.#coverStyleMap.set(doc, $coverStyle)
                }
                if (doc.head) {
                    const $styleBefore = doc.createElement('style')
                    doc.head.prepend($styleBefore)
                    const $style = doc.createElement('style')
                    doc.head.append($style)
                    this.#styleMap.set(doc, [$styleBefore, $style])
                }
                this.setStyles(this.#styles)
                this.dispatchEvent(new CustomEvent('load', { detail: { doc, index } }))
            }
            const beforeRender = this.#beforeRender.bind(this)
            await view.load(src, afterLoad, beforeRender)
            this.dispatchEvent(new CustomEvent('create-overlayer', {
                detail: {
                    doc: view.document, index,
                    attach: overlayer => view.overlayer = overlayer,
                },
            }))
            this.#viewMap.set(index, view)
            // 封面 view 一进窗口就让网格保持整宽，使其从首次布局就用宽栏渲染（消除先白边后拉窄的闪烁）
            this.#syncCoverGrid()
            // 重排并补偿主 View 偏移（预读 View 从左侧加入会右移主 View 位置）
            this.#compensateMain()
            return view
        })().finally(() => this.#loadPromises.delete(index))
        this.#loadPromises.set(index, promise)
        return promise
    }
    /** 维护窗口内 View 的拼接布局与偏移。
     *  多 View 时，View element 用绝对定位，每个非首个 View 左移 2 屏（负重叠），
     *  使其左缓冲与前一 View 的右缓冲重叠，内容列无缝相接——View 内部布局完全不动。
     *  撑宽占位 div 提供滚动宽度（absolute 子项不撑开 scrollWidth）。 */
    #layoutViews() {
        const indices = [...this.#viewMap.keys()].sort((a, b) => a - b)
        const size = this.size
        const overlap = this.#multi ? size * 2 : 0
        if (this.#multi) {
            if (!this.#sizer) {
                this.#sizer = document.createElement('div')
                this.#sizer.style.position = 'relative'
                this.#sizer.style.height = '100%'
                this.#container.prepend(this.#sizer)
            }
            let acc = 0
            for (let k = 0; k < indices.length; k++) {
                const el = this.#viewMap.get(indices[k]).element
                const w = this.#viewWidth(this.#viewMap.get(indices[k]))
                // 无缝拼接的核心：后一个 view 必须左移 overlap（2 屏），使内容列连续。
                // left_{i+1} = left_i + w_i − overlap = left_i + pc_i*size，
                // 即去掉前一个 view 的左右各 1 屏缓冲，让正文页首尾相接。
                const left = (k === 0 && this.#adjacentIndex(-1) == null) ? acc - this.size
                    : acc - (k > 0 ? overlap : 0)
                el.style.position = 'absolute'
                el.style.top = '0'
                el.style.left = `${left}px`
                this.#offsets.set(indices[k], left)
                acc = left + w
            }
            const last = indices[indices.length - 1]
            this.#totalSize = last != null
                ? (this.#offsets.get(last) ?? 0)
                    + this.#viewWidth(this.#viewMap.get(last))
                : 0
            this.#sizer.style.width = `${this.#totalSize}px`
        } else {
            // 单 View（竖排/滚动式/RTL）：保持原版流式布局
            if (this.#sizer) {
                this.#sizer.remove()
                this.#sizer = null
            }
            for (const i of indices) {
                const el = this.#viewMap.get(i).element
                el.style.position = ''
                el.style.left = ''
                el.style.top = ''
                this.#offsets.set(i, 0)
            }
            this.#totalSize = indices.length
                ? this.#viewMap.get(indices[0]).element.getBoundingClientRect()[this.sideProp]
                : 0
        }
    }
    /** 重排窗口并保持主 View 位置稳定：以主 View offset 差补偿滚动位置与 scrollBounds 锚点。 */
    #compensateMain() {
        const index = this.#index
        const before = this.#offsets.get(index) ?? 0
        this.#layoutViews()
        const after = this.#offsets.get(index) ?? 0
        const diff = after - before
        if (diff) {
            this.containerPosition += diff
            if (this.#scrollBounds) this.#scrollBounds[0] += diff
            this.#lastShiftAt = performance.now()
        }
    }
    /** 某 View 布局尺寸变化后，重排窗口并保持主 View 位置稳定（滚动补偿）。 */
    #relayoutAfterExpand() {
        this.#compensateMain()
    }
    /** 后台预读相邻章节，使窗口保持「前章 + 当前章 + 后章」缓存。 */
    #preloadAdjacent() {
        if (!this.#multi) return
        for (const dir of [-1, 1]) {
            const idx = this.#adjacentIndex(dir)
            if (idx != null && !this.#viewMap.has(idx)) {
                this.#loadView(idx).catch(err =>
                    console.warn(`[folio] preload section ${idx} failed:`, err))
            }
        }
    }
    /** 根据当前滚动位置判定主 View；跨章后迁移窗口（卸载过期、滚动补偿、预读新邻接）。
     *  判定必须精确到「内容区」：View element 前后各含 1 屏空白缓冲列，
     *  若按元素边界判定会滞后约 2 屏才迁移，导致相邻章来不及预读、翻页翻进空白缓冲页。
     *  以屏幕中心所在的内容列判定主章，跨章时提前迁移并预读新邻接。 */
    #syncMainView() {
        if (!this.#multi) return
        const pos = this.start + this.size / 2
        const indices = [...this.#viewMap.keys()].sort((a, b) => a - b)
        for (const i of indices) {
            const offset = this.#offsets.get(i) ?? 0
            const width = this.#viewMap.get(i).element
                .getBoundingClientRect()[this.sideProp]
            const contentStart = offset + this.size          // 前导缓冲（1 屏）
            const contentEnd = offset + width - this.size    // 内容结束（去右缓冲）
            if (pos >= contentStart && pos < contentEnd) {
                if (i !== this.#index) this.#shiftWindow(i)
                return
            }
        }
    }
    /** 主 View 迁移到 index：卸载窗口外 View（含滚动补偿），预读新邻接。 */
    #shiftWindow(index) {
        // 先更新 #index，再基于新主章推导窗口边界（#adjacentIndex 依赖 #index）
        this.#index = index
        this.#view = this.#viewMap.get(index) ?? null
        const wanted = [
            this.#adjacentIndex(-1),
            index,
            this.#adjacentIndex(1),
        ].filter(x => x != null)
        const mainBefore = this.#offsets.get(index) ?? 0
        for (const j of [...this.#viewMap.keys()]) {
            if (!wanted.includes(j)) {
                const view = this.#viewMap.get(j)
                view.destroy()
                this.#container.removeChild(view.element)
                this.sections[j]?.unload?.()
                this.#viewMap.delete(j)
            }
        }
        this.#layoutViews()
        this.#syncCoverGrid()
        // 滚动补偿：卸载左侧 View / margin 变化会使主 View 内容左移，
        // 用主 View 偏移差同步滚动位置与 scrollBounds 锚点，保持视觉连续。
        const mainAfter = this.#offsets.get(index) ?? 0
        const diff = mainAfter - mainBefore
        if (diff) {
            this.containerPosition += diff
            if (this.#scrollBounds) this.#scrollBounds[0] += diff
        }
        this.#lastShiftAt = performance.now()
        this.#preloadAdjacent()
    }
    /** 确保 index 作为主 View 就绪（含窗口重建与邻接预读）。 */
    async #ensureWindow(index) {
        // 主 View 必须就绪（阻塞等待）
        if (!this.#viewMap.has(index)) await this.#loadView(index)
        // 先更新 #index 再推导窗口与预读邻接，保证 #adjacentIndex 基于正确的主章节
        this.#index = index
        this.#view = this.#viewMap.get(index) ?? null
        if (this.#multi) this.#preloadAdjacent()
        const wanted = this.#multi
            ? [this.#adjacentIndex(-1), index, this.#adjacentIndex(1)].filter(x => x != null)
            : [index]
        // 卸载窗口外的 View
        for (const j of [...this.#viewMap.keys()]) {
            if (!wanted.includes(j)) {
                const view = this.#viewMap.get(j)
                view.destroy()
                this.#container.removeChild(view.element)
                this.sections[j]?.unload?.()
                this.#viewMap.delete(j)
            }
        }
        this.#layoutViews()
        this.#syncCoverGrid()
    }
    #replaceBackground(background, columnCount) {
        const doc = this.#view?.document
        if (!doc) return
        const htmlStyle = doc.defaultView.getComputedStyle(doc.documentElement)
        const themeBgColor = htmlStyle.getPropertyValue('--theme-bg-color')
        if (background && themeBgColor) {
            const parsedBackground = background.split(/\s(?=(?:url|rgb|hsl|#[0-9a-fA-F]{3,6}))/)
            parsedBackground[0] = themeBgColor
            background = parsedBackground.join(' ')
        }
        if (/cover.*fixed|fixed.*cover/.test(background)) {
            background = background.replace('cover', 'auto 100%').replace('fixed', '')
        }
        this.#background.innerHTML = ''
        this.#background.style.display = 'grid'
        this.#background.style.gridTemplateColumns = `repeat(${columnCount}, 1fr)`
        for (let i = 0; i < columnCount; i++) {
            const column = document.createElement('div')
            column.style.background = background
            column.style.width = '100%'
            column.style.height = '100%'
            this.#background.appendChild(column)
        }
    }
    #beforeRender({ vertical, rtl, background }) {
        this.#vertical = vertical
        this.#rtl = rtl
        this.#top.classList.toggle('vertical', vertical)

        const { width, height } = this.#container.getBoundingClientRect()
        const size = vertical ? height : width

        const style = getComputedStyle(this.#top)
        const maxInlineSize = parseFloat(style.getPropertyValue('--_max-inline-size'))
        const maxColumnCount = parseInt(style.getPropertyValue('--_max-column-count-spread'))
        const margin = parseFloat(style.getPropertyValue('--_margin'))
        // 四向独立页边距：内容内边距（columnize 应用）。图片最大尺寸按上下边距之和留空，避免被边距遮挡。
        const pb = this.#pageMargin
        this.#margin = Math.max(margin, ((pb?.top ?? 0) + (pb?.bottom ?? 0)) / 2)
        // 四向页边距随 layout 一起传给 View，供 columnize 写每页内容内边距。

        const g = parseFloat(style.getPropertyValue('--_gap')) / 100
        // The gap will be a percentage of the #container, not the whole view.
        // This means the outer padding will be bigger than the column gap. Let
        // `a` be the gap percentage. The actual percentage for the column gap
        // will be (1 - a) * a. Let us call this `b`.
        //
        // To make them the same, we start by shrinking the outer padding
        // setting to `b`, but keep the column gap setting the same at `a`. Then
        // the actual size for the column gap will be (1 - b) * a. Repeating the
        // process again and again, we get the sequence
        //     x₁ = (1 - b) * a
        //     x₂ = (1 - x₁) * a
        //     ...
        // which converges to x = (1 - x) * a. Solving for x, x = a / (1 + a).
        // So to make the spacing even, we must shrink the outer padding with
        //     f(x) = x / (1 + x).
        // But we want to keep the outer padding, and make the inner gap bigger.
        // So we apply the inverse, f⁻¹ = -x / (x - 1) to the column gap.
        const gap = -g / (g - 1) * size

        const flow = this.getAttribute('flow')
        if (flow === 'scrolled') {
            // FIXME: vertical-rl only, not -lr
            this.setAttribute('dir', vertical ? 'rtl' : 'ltr')
            this.#top.style.padding = '0'
            const columnWidth = maxInlineSize

            this.heads = null
            this.feet = null
            this.#header.replaceChildren()
            this.#footer.replaceChildren()

            return { flow, margin, gap, columnWidth, pageMargin: pb }
        }

        const divisor = Math.min(maxColumnCount, Math.ceil(size / maxInlineSize))
        const columnWidth = vertical ? (size / divisor - margin) : (size / divisor - gap)
        this.setAttribute('dir', rtl ? 'rtl' : 'ltr')

        // set background to `doc` background
        // this is needed because the iframe does not fill the whole element
        this.columnCount = divisor
        this.#replaceBackground(background, this.columnCount)

        const marginalDivisor = vertical
            ? Math.min(2, Math.ceil(width / maxInlineSize))
            : divisor
        const marginalStyle = {
            gridTemplateColumns: `repeat(${marginalDivisor}, 1fr)`,
            gap: `${gap}px`,
            direction: this.bookDir === 'rtl' ? 'rtl' : 'ltr',
        }
        Object.assign(this.#header.style, marginalStyle)
        Object.assign(this.#footer.style, marginalStyle)
        const heads = makeMarginals(marginalDivisor, 'head')
        const feet = makeMarginals(marginalDivisor, 'foot')
        this.heads = heads.map(el => el.children[0])
        this.feet = feet.map(el => el.children[0])
        this.#header.replaceChildren(...heads)
        this.#footer.replaceChildren(...feet)

        return { height, width, margin, gap, columnWidth, pageMargin: pb }
    }
    render() {
        if (!this.#viewMap.size) return
        const layout = this.#beforeRender({
            vertical: this.#vertical,
            rtl: this.#rtl,
        })
        const mainBefore = this.#offsets.get(this.#index) ?? 0
        for (const view of this.#viewMap.values()) view.render(layout)
        this.#layoutViews()
        // 渲染/重排后保持主 View 位置稳定（补偿因邻接尺寸变化引起的偏移漂移）
        const mainAfter = this.#offsets.get(this.#index) ?? 0
        const diff = mainAfter - mainBefore
        if (diff) {
            this.containerPosition += diff
            if (this.#scrollBounds) this.#scrollBounds[0] += diff
        }
        this.#scrollToAnchor(this.#anchor)
    }
    get scrolled() {
        return this.getAttribute('flow') === 'scrolled'
    }
    get scrollProp() {
        const { scrolled } = this
        return this.#vertical ? (scrolled ? 'scrollLeft' : 'scrollTop')
            : scrolled ? 'scrollTop' : 'scrollLeft'
    }
    get sideProp() {
        const { scrolled } = this
        return this.#vertical ? (scrolled ? 'width' : 'height')
            : scrolled ? 'height' : 'width'
    }
    get size() {
        return this.#container.getBoundingClientRect()[this.sideProp]
    }
    get viewSize() {
        return this.#totalSize
    }
    get start() {
        return Math.abs(this.#container[this.scrollProp])
    }
    get end() {
        return this.start + this.size
    }
    get page() {
        return Math.floor(((this.start + this.end) / 2) / this.size)
    }
    get pages() {
        return Math.round(this.viewSize / this.size)
    }
    // this is the current position of the container
    get containerPosition() {
        return this.#container[this.scrollProp]
    }

    // this is the new position of the containr
    set containerPosition(newVal) {
        this.#container[this.scrollProp] = newVal
    }

    scrollBy(dx, dy) {
        const delta = this.#vertical ? dy : dx
        const [offset, a, b] = this.#scrollBounds
        const rtl = this.#rtl
        const min = rtl ? offset - b : offset - a
        const max = rtl ? offset + a : offset + b
        this.containerPosition = Math.max(min, Math.min(max,
            this.containerPosition + delta))
    }

    async snap(vx, vy) {
        // 双翻修复：方向与目标都基于「拖动前」的静止基准 #scrollBounds[0]，一次手势至多翻一页。
        // 背景：touchend 大幅滑动时 touchmove 已用 scrollBy 把位置推进到相邻页预览，若直接用当前
        // position 反推页号再加速度，会把「预览已推进的一页」又叠一次（距离+速度双判据同时命中 → 翻两页）。
        const size = this.size
        const rest0 = this.#scrollBounds?.[0] ?? this.start
        const delta = this.#scrollBounds?.length ? (this.containerPosition - rest0) : 0
        // 小于半页视为未真正越界（小幅/回滑），只按速度方向翻一页；越过半页则按已推进方向翻一页。
        // dir ∈ {-1, 0, 1}，恒为整数页，绝不卡在两页之间。
        const THRESH = size / 2
        let dir
        if (Math.abs(delta) > THRESH) dir = delta > 0 ? 1 : -1
        else {
            const velocity = this.#vertical ? vy : vx
            dir = velocity > 0 ? 1 : velocity < 0 ? -1 : 0
        }
        if (!dir) return this.#scrollToPage(Math.round(rest0 / size), 'snap')
        // 目标 = 拖动前静止整页 + dir（仅一次，不叠加位移）
        const target = Math.round(rest0 / size) + dir
        await this.#settleWindow()
        return this.#scrollToPage(target, 'snap')
    }
    // allows one to process rects as if they were LTR and horizontal
    #getRectMapper() {
        if (this.scrolled) {
            const size = this.viewSize
            const margin = this.#margin
            return this.#vertical
                ? ({ left, right }) =>
                    ({ left: size - right - margin, right: size - left - margin })
                : ({ top, bottom }) => ({ left: top + margin, right: bottom + margin })
        }
        const pxSize = this.pages * this.size
        return this.#rtl
            ? ({ left, right }) =>
                ({ left: pxSize - right, right: pxSize - left })
            : this.#vertical
                ? ({ top, bottom }) => ({ left: top, right: bottom })
                : f => f
    }
    async #scrollToRect(rect, reason) {
        const main = this.#viewMap.get(this.#index)
        if (!main) return
        const mainOffset = this.#offsets.get(this.#index) ?? 0
        if (this.scrolled) {
            const offset = mainOffset + this.#getRectMapper()(rect).left - this.#margin
            return this.#scrollTo(offset, reason)
        }
        if (this.#rtl) {
            // 负向滚动坐标系（多 View 拼接仅在 LTR，此处保持单 View 语义）
            const offset = this.#getRectMapper()(rect).left
            return this.#scrollToPage(Math.floor(offset / this.size) - 1, reason)
        }
        // 让锚点吸附到其所在列的起点（列序号 = floor((docX - 左边距)/size)），
        // 使正文距屏幕左缘正好保留左边距（与翻页页定位一致）。
        // 直接对齐文本精确位置会把「已含左内边距的 rect.left」当屏幕基准，
        // 令文本贴到屏幕左缘，左边距被吃掉（首屏偏左）。
        const mapper = this.#getRectMapper()
        const docX = mapper(rect).left
        const l = this.#pageMargin?.left ?? 0
        const col = Math.max(0, Math.floor((docX - l) / this.size))
        const offset = mainOffset + this.size + col * this.size
        return this.#scrollToPage(offset / this.size, reason)
    }
    async #scrollTo(offset, reason, smooth) {
        const { size } = this
        if (this.containerPosition === offset) {
            this.#scrollBounds = [offset, this.atStart ? 0 : size, this.atEnd ? 0 : size]
            this.#afterScroll(reason)
            return
        }
        // FIXME: vertical-rl only, not -lr
        if (this.scrolled && this.#vertical) offset = -offset
        if ((reason === 'snap' || smooth) && this.hasAttribute('animated')) return animate(
            this.containerPosition, offset, 300, easeOutQuad,
            x => this.containerPosition = x,
        ).then(() => {
            this.#scrollBounds = [offset, this.atStart ? 0 : size, this.atEnd ? 0 : size]
            this.#afterScroll(reason)
        })
        else {
            this.containerPosition = offset
            this.#scrollBounds = [offset, this.atStart ? 0 : size, this.atEnd ? 0 : size]
            this.#afterScroll(reason)
        }
    }
    async #scrollToPage(page, reason, smooth) {
        const offset = this.size * (this.#rtl ? -page : page)
        return this.#scrollTo(offset, reason, smooth)
    }
    async scrollToAnchor(anchor, select) {
        return this.#scrollToAnchor(anchor, select ? 'selection' : 'navigation')
    }
    async #scrollToAnchor(anchor, reason = 'anchor') {
        this.#anchor = anchor
        const main = this.#viewMap.get(this.#index)
        // 过期锚点保护：锚点若属于已迁移走的章节文档，用它定位会误跳到错误位置，直接忽略。
        // （仅当锚点是 Range/Element 时检查；数字 fraction 锚点恒相对于主 View，无需检查。）
        if (main && (anchor?.startContainer || anchor?.nodeType === 1)) {
            const ownerDoc = anchor.startContainer?.ownerDocument ?? anchor.ownerDocument
            if (ownerDoc && ownerDoc !== main.document) return
        }
        const rects = uncollapse(anchor)?.getClientRects?.()
        // if anchor is an element or a range
        if (rects) {
            // when the start of the range is immediately after a hyphen in the
            // previous column, there is an extra zero width rect in that column
            const rect = Array.from(rects)
                .find(r => r.width > 0 && r.height > 0) || rects[0]
            if (!rect) return
            await this.#scrollToRect(rect, reason)
            return
        }
        // if anchor is a fraction（相对主 View 的章节内进度）
        if (!main) return
        const mainOffset = this.#offsets.get(this.#index) ?? 0
        if (this.scrolled) {
            const mainSize = main.element.getBoundingClientRect()[this.sideProp]
            await this.#scrollTo(mainOffset + anchor * mainSize, reason)
            return
        }
        const textPages = main.pageCount
        if (textPages < 1) return
        const newPage = Math.round(anchor * (textPages - 1))
        const contentStartPage = (mainOffset + this.size) / this.size
        await this.#scrollToPage(contentStartPage + newPage, reason)
    }
    #getVisibleRange() {
        const main = this.#viewMap.get(this.#index)
        if (!main) return
        const mainOffset = this.#offsets.get(this.#index) ?? 0
        if (this.scrolled) return getVisibleRange(main.document,
            this.start - mainOffset + this.#margin,
            this.end - mainOffset - this.#margin, this.#getRectMapper())
        if (this.#rtl) {
            // 负向滚动坐标系（单 View）
            const size = -this.size
            return getVisibleRange(main.document,
                this.start - size, this.end - size, this.#getRectMapper())
        }
        // 主 View 内容坐标 = 全局坐标 - mainOffset - 前导缓冲（1 屏）
        const lead = this.size
        return getVisibleRange(main.document,
            this.start - mainOffset - lead,
            this.end - mainOffset - lead, this.#getRectMapper())
    }
    #afterScroll(reason) {
        // 跨章后先迁移主 View（卸载过期 View、滚动补偿、预读新邻接）。
        // 窗口迁移/补偿本身会触发 scroll 事件，短时间内（400ms）抑制，
        // 避免「迁移 → 补偿 → 再 sync → 再迁移」的震荡循环。
        if (performance.now() - this.#lastShiftAt > 400)
            this.#syncMainView()
        // 迁移/补偿可能改变了滚动位置，刷新吸附边界（scrollBounds），
        // 保证下一次 snap 的 min/max 基于最新的实际位置而非迁移前的偏移。
        const { size } = this
        this.#scrollBounds = [this.containerPosition, this.atStart ? 0 : size, this.atEnd ? 0 : size]
        const range = this.#getVisibleRange()
        this.#lastVisibleRange = range
        // don't set new anchor if relocation was to scroll to anchor
        if (reason !== 'selection' && reason !== 'navigation' && reason !== 'anchor')
            this.#anchor = range
        else this.#justAnchored = true

        const index = this.#index
        const detail = { reason, range, index }
        const main = this.#viewMap.get(index)
        const mainOffset = this.#offsets.get(index) ?? 0
        if (this.scrolled) {
            const mainSize = main?.element.getBoundingClientRect()[this.sideProp] ?? 1
            detail.fraction = (this.start - mainOffset) / mainSize
        } else if (this.pages > 0 && main) {
            const textPages = main.pageCount
            if (textPages > 1) {
                // 主 View 内内容页序号（0-based，不含缓冲列）：首页为 0 → 隐藏 header
                const lead = this.size
                const mainPage = Math.floor(
                    ((this.start + this.end) / 2 - mainOffset - lead) / this.size)
                const contentPage = Math.max(0, Math.min(textPages - 1, mainPage))
                this.#header.style.visibility = contentPage > 0 ? 'visible' : 'hidden'
                detail.fraction = contentPage / (textPages - 1)
                detail.size = 1 / (textPages - 1)
            } else {
                // 字体/布局尚未稳定（pageCount 未就绪）：进度归 0，避免 NaN
                detail.fraction = 0
                detail.size = 0
            }
        }
        this.dispatchEvent(new CustomEvent('relocate', { detail }))
    }
    #canGoToIndex(index) {
        return index >= 0 && index <= this.sections.length - 1
    }
    async #goTo({ index, anchor, select }) {
        if (!this.#canGoToIndex(index)) return
        // 确保 index 作为主 View 就绪：若已在窗口（相邻章已预读）则无需重载，
        // 直接定位；否则重建窗口（加载主 View + 预读邻接 + 卸载过期）。
        await this.#ensureWindow(index)
        const main = this.#viewMap.get(index)
        const hasFocus = main?.document?.hasFocus()
        await this.scrollToAnchor((typeof anchor === 'function'
            ? anchor(main?.document) : anchor) ?? 0, select)
        if (hasFocus) this.focusView()
    }
    async goTo(target) {
        if (this.#locked) return
        const resolved = await target
        if (this.#canGoToIndex(resolved.index)) return this.#goTo(resolved)
    }
    #scrollPrev(distance) {
        if (!this.#view) return true
        if (this.scrolled) {
            if (this.start > 0) return this.#scrollTo(
                Math.max(0, this.start - (distance ?? this.size)), null, true)
            return !this.atStart
        }
        if (this.atStart) return
        // 以「拖动前」的静止位置（#scrollBounds[0]）为基准：拖动预览期间 #scrollBounds 不更新，
        // 若用 this.page（拖动后的当前页）再 -1，会把预览已翻过的一页再叠一次（每次回翻多翻一页）。
        const rest = this.#scrollBounds?.[0] ?? this.start
        const page = Math.floor((rest + this.size / 2) / this.size) - 1
        // 目标页仍在窗口可读范围内：无缝回翻（跨章直接滚回相邻章内容，不跳章节）。
        // 左边界不能用固定的「全局页 1」——窗口滑动后左侧章节可能已卸载，
        // 其区域是空白缓冲列。必须取当前窗口内第一节的实际正文起始页。
        if (page >= this.#firstContentPage)
            return this.#scrollToPage(page, 'page', true).then(() => false)
        // 已回翻到窗口开头：上一章在窗口则无缝滚入，否则跳转加载到上一章末尾。
        const prev = this.#adjacentIndex(-1)
        if (prev == null)
            return this.#scrollToPage(1, 'page', true).then(() => false)
        return true
    }
    #scrollNext(distance) {
        if (!this.#view) return true
        if (this.scrolled) {
            if (this.viewSize - this.end > 2) return this.#scrollTo(
                Math.min(this.viewSize, distance ? this.start + distance : this.end), null, true)
            return !this.atEnd
        }
        if (this.atEnd) return
        // 与 #scrollPrev 对称：以「拖动前」的静止位置（#scrollBounds[0]）为基准 +1，
        // 而不是用拖动后的 this.page 再 +1（大幅拖动时会把预览已翻过的一页再叠一次，每翻多翻一页）。
        const rest = this.#scrollBounds?.[0] ?? this.start
        const page = Math.floor((rest + this.size / 2) / this.size) + 1
        // 窗口内最后一个可读内容页（末章右侧 1 屏空白缓冲列不可停留）。
        // 目标页仍在窗口可读范围内：无缝推进（跨章直接滚入相邻章内容，不跳回章节开头）。
        if (page <= this.#lastContentPage)
            return this.#scrollToPage(page, 'page', true).then(() => false)
        // 已翻到窗口末尾：下一章在窗口则无缝滚入，否则跳转加载到下一章开头。
        const next = this.#adjacentIndex(1)
        if (next == null)
            return this.#scrollToPage(this.#lastContentPage, 'page', true).then(() => false)
        return true
    }
    get atStart() {
        return this.#adjacentIndex(-1) == null && this.page <= 1
    }
    get atEnd() {
        return this.#adjacentIndex(1) == null && this.page >= this.pages - 2
    }
    /** 取 View element 的参与宽度：未排版时为 0，钳位到最小单元（3 屏）。 */
    #viewWidth(view) {
        const size = this.size
        return Math.max(view.element.getBoundingClientRect()[this.sideProp], size * 3)
    }
    /** 窗口内第一个可读内容页（全局页号，可为小数）。
     *  窗口滑动后第一章可能已被卸载，其左侧 1 屏空白缓冲列不可停留、也无正文可读。
     *  返回首个在窗章节的正文起始页（offset + 前导缓冲 1 屏）。 */
    get #firstContentPage() {
        const indices = [...this.#viewMap.keys()].sort((a, b) => a - b)
        const firstIndex = indices[0]
        if (firstIndex == null) return 0
        const firstOffset = this.#offsets.get(firstIndex) ?? 0
        return (firstOffset + this.size) / this.size
    }
    /** 窗口内最后一个可读内容页（全局页号，可为小数）；末章右侧 1 屏空白缓冲列不可停留。
     *  计算：末章内容结束（offset + elementWidth - size）再减一屏 = 可滚到的最大内容位置。 */
    get #lastContentPage() {
        const indices = [...this.#viewMap.keys()].sort((a, b) => a - b)
        const lastIndex = indices[indices.length - 1]
        if (lastIndex == null) return 0
        const lastOffset = this.#offsets.get(lastIndex) ?? 0
        const lastWidth = this.#viewWidth(this.#viewMap.get(lastIndex))
        return (lastOffset + lastWidth - this.size * 2) / this.size
    }
    #adjacentIndex(dir) {
        for (let index = this.#index + dir; this.#canGoToIndex(index); index += dir)
            if (this.sections[index]?.linear !== 'no') return index
    }
    /** 翻页前稳定窗口：等待在途预读完成，并等已在窗章节的字体/重排稳定。
     *  若某相邻章节在翻页动画中途才因字体加载/观察器回调而变更列宽与页数，
     *  onExpand透传的 #compensateMain 会移动容器位置，与进行中的动画帧竞争：
     *  动画帧最后写入的 target*size 在（已变化的）新偏移体系下映射到错误内容，
     *  表现为"动画正确但落点错页 / 翻两页跳回章节开头"。
     *  这里先在动画开始前收敛全部偏移（等字体两帧稳定后 #compensateMain），
     *  保证 target*size 与正文映射一致。超时兜底：加载/排版慢时最多等 timeout 毫秒避免阻塞翻页。 */
    async #settleWindow(timeout = 500) {
        const pending = [...this.#loadPromises.values()]
        if (pending.length)
            await Promise.race([Promise.allSettled(pending), wait(timeout)])
        // 等待窗口内各章节字体稳定并触发其 aR 兜底 expand（columnize 后若字体未就绪，
        // 列宽可能尚未收窄到位，页数不准确；等两帧确保 View.expand 的 onExpand → 补偿已执行）。
        const fonts = [...this.#viewMap.values()]
            .map(v => v.document?.fonts?.ready
                .then(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))))
                ?? Promise.resolve())
        if (fonts.length)
            await Promise.race([Promise.allSettled(fonts), wait(timeout)])
        this.#compensateMain()
    }
    async #turnPage(dir, distance) {
        if (this.#locked) return
        this.#locked = true
        await this.#settleWindow()
        const prev = dir === -1
        const shouldGo = await (prev ? this.#scrollPrev(distance) : this.#scrollNext(distance))
        if (shouldGo) await this.#goTo({
            index: this.#adjacentIndex(dir),
            anchor: prev ? () => 1 : () => 0,
        })
        if (shouldGo || !this.hasAttribute('animated')) await wait(100)
        this.#locked = false
    }
    async prev(distance) {
        return await this.#turnPage(-1, distance)
    }
    async next(distance) {
        return await this.#turnPage(1, distance)
    }
    prevSection() {
        return this.goTo({ index: this.#adjacentIndex(-1) })
    }
    nextSection() {
        return this.goTo({ index: this.#adjacentIndex(1) })
    }
    firstSection() {
        const index = this.sections.findIndex(section => section.linear !== 'no')
        return this.goTo({ index })
    }
    lastSection() {
        const index = this.sections.findLastIndex(section => section.linear !== 'no')
        return this.goTo({ index })
    }
    getContents() {
        return [...this.#viewMap].map(([index, view]) => ({
            index,
            overlayer: view.overlayer,
            doc: view.document,
        }))
    }
    setStyles(styles) {
        this.#styles = styles
        // 应用到窗口内所有已加载章节（主 View 与预读邻接）
        for (const [doc, $$styles] of this.#styleMap) {
            const [$beforeStyle, $style] = $$styles
            if (Array.isArray(styles)) {
                const [beforeStyle, style] = styles
                $beforeStyle.textContent = beforeStyle
                $style.textContent = style
            } else $style.textContent = styles
        }

        // NOTE: needs `requestAnimationFrame` in Chromium
        requestAnimationFrame(() => {
            this.#replaceBackground(this.#view?.docBackground, this.columnCount)
        })

        // needed because the resize observer doesn't work in Firefox
        for (const view of this.#viewMap.values())
            view.document?.fonts?.ready?.then(() => view.expand())
    }
    focusView() {
        this.#view?.document?.defaultView?.focus()
    }
    destroy() {
        this.#observer.unobserve(this)
        for (const [index, view] of this.#viewMap) {
            view.destroy()
            this.sections[index]?.unload?.()
        }
        this.#viewMap.clear()
        if (this.#sizer) {
            this.#sizer.remove()
            this.#sizer = null
        }
        this.#styleMap.clear()
        this.#view = null
        this.#mediaQuery.removeEventListener('change', this.#mediaQueryListener)
    }
}

customElements.define('foliate-paginator', Paginator)
