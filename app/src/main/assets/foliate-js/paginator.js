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
        if (document.hidden) {
            render(lerp(a, b, 1))
            return resolve()
        }
        start ??= now
        const fraction = Math.min(1, (now - start) / duration)
        render(lerp(a, b, ease(fraction)))
        if (fraction < 1) requestAnimationFrame(step)
        else resolve()
    }
    if (document.hidden) {
        render(lerp(a, b, 1))
        return resolve()
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
        if (sel) {
            sel.removeAllRanges()
            if (collapse === -1) range.collapse(true)
            else if (collapse === 1) range.collapse()
            sel.addRange(range)
        }
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
 *  识别须兼容 `<img>`、`<svg><image>` 等常见封面形态（许多 EPUB 封面用 svg 承载）。 */
const isCoverLike = doc => {
    if (!doc?.body) return false
    const hasVisual = doc.body.querySelector('img, svg, picture, video')
    if (!hasVisual) return false
    const textLen = (doc.body.textContent ?? '').trim().length
    return textLen < 500
}

class View {
    #observer = new ResizeObserver(() => this.expand())
    #element = document.createElement('div')
    #iframe = document.createElement('iframe')
    #contentRange = document.createRange()
    #overlayer
    #vertical = false
    #rtl = false
    #column = true
    #size
    #layout = {}
    #contentPages = 0
    // 四向页边距（px）：由 Paginator 经 beforeRender 转发，columnize 据此写入每页内容内边距。
    #pageMargin = null
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
    /** 内容页数（无前后空白缓冲的区分，纯内容列数）。 */
    get contentPages() {
        return this.#contentPages
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
                const background = getBackground(doc)
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
        if (!layout) return
        this.#column = layout.flow !== 'scrolled'
        this.#layout = layout
        // 封面页（isCover）：取消四向页边距，令其内容整屏铺满、封面图可全屏显示。
        if (layout.pageMargin) this.#pageMargin = this.isCover
            ? { top: 0, right: 0, bottom: 0, left: 0 } : layout.pageMargin
        if (this.#column) this.columnize(layout)
        else this.scrolled(layout)
    }
    scrolled({ gap, columnWidth }) {
        const vertical = this.#vertical
        const doc = this.document
        setStylesImportant(doc.documentElement, {
            'box-sizing': 'border-box',
            'padding': vertical ? `${gap}px 0` : `0 ${gap}px`,
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
        // 封面拉伸填满：由阅读设置「封面等比例缩放」开关决定（reader 顶层经 window.__coverProportional 传入）。
        // true/未定义 = 等比缩放（object-fit:contain，不变形、四周留阅读底色）；false = 拉伸铺满整屏（object-fit:fill，可变形）。
        const coverFill = window.__coverProportional === false
        for (const el of doc.body.querySelectorAll('img, svg, video')) {
            // 封面矢量图：确保有「内容全图」viewBox + 正确的 preserveAspectRatio，使整幅封面 fit 进整页，杜绝放大超屏/裁切。
            // 许多 EPUB 封面 svg 的 viewBox 比内嵌 <image> 小（如裁切封皮），甚至会 <image> 裁掉右/下内容，
            // 或 viewBox 比图大 → 直接按原始/作者 viewBox 渲染导致「封面放大到大于屏幕、只显示一部分」。
            // 这里**一律**以 <image> 完整尺寸改写 viewBox（不判断是否已有 viewBox，避免作者给了小 viewBox 时不覆盖而裁切）：
            //  - 等比(coverFill=false)：preserveAspectRatio=xMidYMid meet → 整幅等比缩放居中，四周留阅读底色
            //  - 拉伸(coverFill=true)：preserveAspectRatio=none + object-fit:fill → 铺满整页（可变形）
            if (this.isCover && el.tagName.toLowerCase() === 'svg') {
                const sub = el.querySelector('image')
                const iw = sub ? parseFloat(sub.getAttribute('width')) : NaN
                const ih = sub ? parseFloat(sub.getAttribute('height')) : NaN
                if (iw > 0 && ih > 0) {
                    el.setAttribute('viewBox', `0 0 ${iw} ${ih}`)
                }
                el.setAttribute('preserveAspectRatio', coverFill ? 'none' : 'xMidYMid meet')
                setStylesImportant(el, {
                    'width': '100% !important',
                    'height': '100% !important',
                    'max-width': 'none',
                    'max-height': 'none',
                    'object-fit': coverFill ? 'fill' : 'contain',
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
                'object-fit': coverFill ? 'fill' : 'contain',
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
            this.#contentPages = pageCount
            try { window.EPUBBridge?.log?.('[ex] idx=' + this.index + ' cp=' + pageCount + ' trig=' + (new Error()).stack?.split('\n').slice(2, 4).join('|')) } catch (_) {}
            const expandedSize = pageCount * this.#size
            this.#element.style.padding = '0'
            this.#iframe.style[side] = `${expandedSize}px`
            this.#element.style[side] = `${expandedSize}px`
            this.#iframe.style[otherSide] = '100%'
            this.#element.style[otherSide] = '100%'
            documentElement.style[side] = `${this.#size}px`
            if (this.#overlayer) {
                this.#overlayer.element.style.margin = '0'
                this.#overlayer.element.style.left = '0'
                this.#overlayer.element.style.top = '0'
                this.#overlayer.element.style[side] = `${expandedSize}px`
                this.#overlayer.redraw()
            }
        } else {
            const side = this.#vertical ? 'width' : 'height'
            const otherSide = this.#vertical ? 'height' : 'width'
            const contentSize = documentElement.getBoundingClientRect()[side]
            const expandedSize = contentSize
            const { margin } = this.#layout
            const padding = this.#vertical ? `0 ${margin}px` : `${margin}px 0`
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
        }
        this.onExpand()
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

// 多章节 View 按 index 拼接成一条横向长条（flex row，首尾相接），
// offsets 全部从 DOM 实时计算（#getViewOffset），prepend 插入时做锚定补偿，
// 从而根治旧「offsets 表 + scrollLeft 补偿」三条结构性缺陷（offsets/scroll 不同步、宽度异步、回调互踩）。
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
    #views = new Map()            // index → View（全部已排版章节，按 index 排序拼接）
    #primaryIndex = -1            // 当前正在阅读的章节
    #lastLayout = null            // 最近一次 #beforeRender 产出的 layout，供邻章复用（不重复改全局态）
    #vertical = false
    #rtl = false
    #margin = 0
    // 宿主通过 setPageMargins() 注入的四向页边距（px）。作为每页内容内边距应用，
    // 让翻页动画铺满整屏，而不是被外框轨道裁切成只在边框内滑动。
    #pageMargin = null
    #anchor = 0 // anchor view to a fraction (0-1), Range, or Element
    #justAnchored = false
    #locked = false // while true, prevent any further navigation
    #styles
    #styleMap = new WeakMap()
    #mediaQuery = matchMedia('(prefers-color-scheme: dark)')
    #mediaQueryListener
    #scrollBounds
    #touchState
    #touchScrolled
    #lastVisibleRange
    #stabilizing = false          // goTo 稳定期（抑制 onExpand 抢滚动）
    #isAnimating = false          // snap 滚动动画中
    #trimming = false             // 窗口外章节销毁中（防重入）
    #filling = false              // true while #fillVisibleArea is running
    #fillPromise = null           // tracks in-progress #fillVisibleArea for awaiting
    columnCount = 1               // 本项目单栏整屏（每屏一页）
    /* ---- 预排 ----
     * 预排只保证 primary±1 在池中。 */
    #idlePreload = false          // 预排循环守卫（同一时刻只跑一个预排循环）
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
            --_gap: 7%;
            --_margin: 48px;
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
            /* 横向恒满屏（1 / -1）：容器宽度恒定，避免封面进出窗口时切换网格导致
               容器宽窄跳变（窄栏 ↔ 满屏）→ 白边 / 全部章节重排刷屏。左右页边距由
               columnize 内容内边距（四向 pageMargin 的 l/r）处理，不依赖外部窄栏。 */
            grid-column: 1 / -1;
            grid-row: 2;
            overflow: hidden;
            /* 多窗口拼接：视图按 index 排成 flex 行，首尾相接，靠 scrollLeft 横向滚动翻页。 */
            display: flex;
            flex-direction: row;
            position: relative;
        }
        :host([flow="scrolled"]) #container {
            grid-column: 1 / -1;
            grid-row: 1 / -1;
            overflow: auto;
            flex-direction: column;
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
            <div id="container"></div>
            <div id="footer"></div>
        </div>
        `

        this.#top = this.#root.getElementById('top')
        this.#background = this.#root.getElementById('background')
        this.#container = this.#root.getElementById('container')
        this.#header = this.#root.getElementById('header')
        this.#footer = this.#root.getElementById('footer')

        this.#observer.observe(this.#container)
        this.#container.addEventListener('scroll', () => {
            if (!this.#isAnimating) this.dispatchEvent(new Event('scroll'))
            try { window.EPUBBridge?.log?.('[scr] scrollEvent primary=' + this.#primaryIndex + ' vo=' + Math.round(this.#getViewOffset(this.#primaryIndex)) + ' scroll=' + Math.round(this.#container[this.scrollProp]) + ' animating=' + this.#isAnimating) } catch (_) {}
        })
        this.#container.addEventListener('scroll', debounce(() => {
            if (this.#justAnchored) this.#justAnchored = false
            else this.#afterScroll('scroll')
            // 翻页静止后：销毁缓冲窗口外的远章节，并补齐新窗口内未排章。
            this.#trimDistantViews()
            this.#scheduleAllPreload()
        }, 250))

        const opts = { passive: false }
        this.addEventListener('touchstart', this.#onTouchStart.bind(this), opts)
        this.addEventListener('touchmove', this.#onTouchMove.bind(this), opts)
        this.addEventListener('touchend', this.#onTouchEnd.bind(this))
        this.addEventListener('load', ({ detail: { doc } }) => {
            doc.addEventListener('touchstart', this.#onTouchStart.bind(this), opts)
            doc.addEventListener('touchmove', this.#onTouchMove.bind(this), opts)
            doc.addEventListener('touchend', this.#onTouchEnd.bind(this))
        })
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
            if (!this.#primaryView) return
            this.#background.style.background = getBackground(this.#primaryView.document)
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
    /** 宿主注入四向页边距（px，可独立不等）。存为内容内边距（columnize 应用），
     *  使翻页动画覆盖整屏，边距随正文一起滑动。 */
    setPageMargins({ top = 0, right = 0, bottom = 0, left = 0 } = {}) {
        this.#pageMargin = { top, right, bottom, left }
        this.render()
    }

    /* ---- 多窗口拼接：视图创建/销毁/offset 计算 ----
     * 视图不存绝对偏移表，#getViewOffset 每次遍历已 inFlow 视图实时累加各视图实测宽度，
     * 从根上避开「offsets 表与 scrollLeft 不同步」「章节宽度异步变化重排漂移」两类旧坑。 */
    get #primaryView() {
        return this.#views.get(this.#primaryIndex)
    }
    get #sortedViews() {
        // 只认「已真正拼入 flex 长条 (inFlow)」的章：屏外 fixed 的预排章宽度/位置与屏幕不符。
        return [...this.#views.keys()].sort((a, b) => a - b)
            .map(i => [i, this.#views.get(i)])
            .filter(([i, v]) => v && v.inFlow)
    }
    /** 视图在长条中的物理宽度（按真实 DOM 布局，按需读取；对滚动位置无关，RTL 下宽度不变）。
     *  只认已 inFlow（真正拼入 flex 长条）的章：屏外 fixed 的预排章不计宽度。 */
    #domWidth(view) {
        const r = view?.element?.getBoundingClientRect?.()
        return (r ? (this.#vertical ? r.height : r.width) : 0) || 0
    }
    /** 某章节之前所有 inFlow 章的真实物理宽度和（像素）。事实源=浏览器布局，
     *  每帧 what-you-see-is-the-truth，不再自维护"contentPages×size"前缀和。
     *  用于把容器 scroll 与章偏移统一到同一个物理坐标。 */
    #domLeftBefore(index) {
        let left = 0
        for (const i of [...this.#views.keys()].sort((a, b) => a - b)) {
            if (i === index) break
            const v = this.#views.get(i)
            if (v?.inFlow) left += this.#domWidth(v)
        }
        return left
    }
    /** 某章节在当前长条中的绝对像素偏移（章节 content 起点对应的容器滚动坐标）。 */
    #getViewOffset(index) {
        return this.#domLeftBefore(index)
    }
    /** index 视图之前完整占用的页数（物理布局换算：宽 / size，四舍五入对齐页单位）。 */
    #getPagesBeforeView(index) {
        return Math.round(this.#domLeftBefore(index) / this.size)
    }
    /** 依据当前滚动位置判定哪个视图是 primary（读者正在读的章节）。 */
    #detectPrimaryView() {
        if (this.#views.size <= 1 || !this.#scrollBounds) return
        const visibleStart = this.#renderedStart
        let offset = 0
        for (const index of [...this.#views.keys()].sort((a, b) => a - b)) {
            const view = this.#views.get(index)
            if (!view?.inFlow) continue
            const viewSize = this.#domWidth(view)
            if (visibleStart < offset + viewSize) {
                if (index !== this.#primaryIndex) this.#primaryIndex = index
                return
            }
            offset += viewSize
        }
    }
    /** 创建章节视图并入池，初始保持屏外渲染（position:fixed），加载完成后转为 relative 进入 flex 流。 */
    #buildView(index) {
        const view = new View({
            container: this,
            onExpand: () => {
                if (this.#stabilizing) return
                if (this.#primaryIndex === index) this.#scrollToAnchor(this.#anchor)
            },
        })
        view.index = index
        // 初始屏外渲染（position:fixed; left:-9999px），保持布局计算但不可见，
        // 加载完成后转为 relative 进入 flex 流。
        // inFlow：是否已真正拼入 flex 长条（position:relative）。屏外 fixed 阶段不占物理宽度，
        // 各 offset/edge/page 记账必须只认 inFlow 视图，否则与屏幕实际不符。
        view.inFlow = false
        Object.assign(view.element.style, {
            position: 'fixed', left: '-9999px', top: '0',
        })
        this.#views.set(index, view)
        // 按 index 顺序插入 DOM
        const sorted = [...this.#views.keys()].sort((a, b) => a - b)
        const myPos = sorted.indexOf(index)
        const nextEntry = sorted[myPos + 1]
        if (nextEntry != null) this.#container.insertBefore(view.element, this.#views.get(nextEntry).element)
        else this.#container.append(view.element)
        return view
    }
    /** 兼容别名：#goTo 里"新建并入池"。 */
    #createView(index) {
        return this.#buildView(index)
    }
    #destroyView(index) {
        const view = this.#views.get(index)
        if (!view) return
        try {
            const st = (new Error()).stack?.split('\n').slice(1, 4).join('|') ?? ''
            window.EPUBBridge?.log?.('[dx] destroy idx=' + index + ' cp=' + (view.contentPages ?? '?')
                + ' caller=' + st)
        } catch (_) {}
        view.destroy()
        view.element.remove()
        this.#views.delete(index)
        this.sections[index]?.unload?.()
    }
    #snap(label) {
        try {
            const rip = []
            const sz = this.size || 0
            for (const [i, v] of this.#sortedViews) {
                const w = Math.round(v.element.getBoundingClientRect()[this.sideProp])
                rip.push(i + ':cp' + (v.contentPages ?? '?') + ':w' + w + ((sz && w) ? '(' + Math.round(w / sz) + '屏)' : ''))
            }
            const cp = this.#primaryView?.contentPages ?? 0
            const total = this.sections?.length ?? 0
            window.EPUBBridge?.log?.('[snap] ' + label +
                ' |页#' + this.#primaryIndex + ' p' + (this.page + 1) + '/' + cp + (cp ? '(frac' + (this.page / cp).toFixed(3) + ')' : '') +
                ' |条[' + rip.join(',') + ']' +
                ' |缓存' + this.#views.size + '/' + total +
                ' scroll=' + Math.round(this.#renderedStart))
        } catch (_) {}
    }
    /** 把某章已排好的样式节点应用到指定 doc。 */
    #applyStylesToDoc(doc) {
        if (!doc?.head || !this.#styles || !this.#styleMap.has(doc)) return
        const [before, after] = this.#styleMap.get(doc)
        if (Array.isArray(this.#styles)) {
            const [bs, s] = this.#styles
            before.textContent = bs
            after.textContent = s
        } else after.textContent = this.#styles
    }
    /** 加载并排版一章。排完即拼入全书长条（flex row）。 */
    async #loadSection(index) {
        if (!this.#canGoToIndex(index)) return
        const section = this.sections[index]
        if (!section || section.linear === 'no') return
        // 已在池中 → 直接返回
        let view = this.#views.get(index)
        if (view?.document?.body) return view
        view = this.#buildView(index)
        const afterLoad = doc => {
            if (index === 0 && isCoverLike(doc)) {
                view.isCover = true
                const $coverStyle = doc.createElement('style')
                $coverStyle.textContent =
                    'html, body, body > * { margin: 0 !important; padding: 0 !important; height: 100% !important; min-height: 100% !important; }'
                doc.head.append($coverStyle)
            }
            if (doc.head) {
                const $styleBefore = doc.createElement('style')
                doc.head.prepend($styleBefore)
                const $style = doc.createElement('style')
                doc.head.append($style)
                this.#styleMap.set(doc, [$styleBefore, $style])
            }
            this.#applyStylesToDoc(doc)
        }
        const beforeRender = () => this.#lastLayout
        try {
            const src = await section.load()
            if (typeof src !== 'string') throw new Error(`src of section ${index} is not string`)
            await view.load(src, afterLoad, beforeRender)
        } catch (e) {
            console.warn(e)
            console.warn(new Error(`Failed to load section ${index}`))
            this.#destroyView(index)
            return
        }
        if (!view.document?.body) {
            this.#destroyView(index)
            return
        }
        // 判断是否「左邻章 prepend」：排在所有已装配视图之前、且非主章，需锚定补偿
        const hasBefore = [...this.#views.keys()].some(i => i < index)
        const isLeftNeighbor = !hasBefore && index !== this.#primaryIndex
        // 左邻章必须先等 contentPages 定稿，再拼入长条并补偿
        if (isLeftNeighbor) await this.#settleViewWidth(view)
        // 排完即拼入长条：从屏外 fixed 转为 relative → 进入 flex 布局
        view.inFlow = true
        Object.assign(view.element.style, {
            position: 'relative', left: 'auto', top: 'auto',
        })
        try { window.EPUBBridge?.log?.('[asb] idx=' + index + ' primary=' + this.#primaryIndex + ' voP=' + Math.round(this.#getViewOffset(this.#primaryIndex)) + ' start=' + Math.round(this.#renderedStart) + ' hasBefore=' + hasBefore) } catch (_) {}
        // prepend 补偿：在长条最左装入章节后，保持视口内容不动
        if (isLeftNeighbor) {
            const startBefore = this.#renderedStart
            const addedSize = this.#domWidth(view)
            const correction = startBefore + addedSize - this.#renderedStart
            if (Math.abs(correction) > 0.5) {
                if (Math.abs(correction) > this.size * 2) {
                    void this.#container[(this.#vertical ? 'offsetHeight' : 'offsetWidth')]
                    try { window.EPUBBridge?.log?.('[cmp] big reflow correction=' + Math.round(correction)) } catch (_) {}
                }
                this.containerPosition += (this.#vertical ? -1 : 1) * correction
                this.#scrollBounds = [this.#container[this.scrollProp],
                    this.atStart ? 0 : this.size, this.atEnd ? 0 : this.size]
            }
            try { window.EPUBBridge?.log?.('[cmp] startBefore=' + Math.round(startBefore) + ' addedSize=' + Math.round(addedSize) + ' correction=' + Math.round(correction) + ' apply=' + (Math.abs(correction) > 0.5) + ' scrollNow=' + Math.round(this.#container[this.scrollProp]) + ' primary=' + this.#primaryIndex + ' voP=' + Math.round(this.#getViewOffset(this.#primaryIndex)) + ' sb=' + JSON.stringify(this.#scrollBounds?.map(v => Math.round(v)))) } catch (_) {}
        }
        this.dispatchEvent(new CustomEvent('create-overlayer', {
            detail: { doc: view.document, index, attach: overlayer => view.overlayer = overlayer },
        }))
        return view
    }
    /** 等待指定视图的 contentPages 定稿（等字体 + 首帧 + 连续稳定帧）。
     *  WebKit 的字体加载/末列取整会在加载完成后**晚一步**再跳 1 页，
     *  若此时就用该临时页数做 prepend 补偿，会把视口过度后推。
     *  屏外 fixed 态等待，不影响可见视口、无闪烁。 */
    async #settleViewWidth(view) {
        const doc = view?.document
        if (!doc) return
        try { await doc.fonts.ready } catch (_) {}
        await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))
        let last = view.contentPages
        let stable = 0
        // 最多 1.5s：等 contentPages 连续稳定(≥2 次相同)才认为真正定稿
        for (let i = 0; i < 30; i++) {
            await new Promise(r => setTimeout(r, 50))
            const now = view.contentPages
            if (now === last && now > 0) {
                if (++stable >= 2) break
            } else {
                last = now
                stable = 0
            }
        }
        try { window.EPUBBridge?.log?.('[stl] idx=' + view.index + ' cpFinal=' + view.contentPages) } catch (_) {}
    }
    /** 判断某章是否落在「当前章前后各 1 章」的缓存窗口内。 */
    #isWithinBuffer(index) {
        return Math.abs(index - this.#primaryIndex) <= 1
    }
    // 根据 Readest 方案：只销毁前向（右侧）10 页以外的远视图，不销毁左侧视图。
    // 左侧视图由 scrollToAnchor 保持锚定，浏览器 scroll anchoring 处理宽度变化。
    #trimDistantViews() {
        if (this.#views.size <= 1 || this.#trimming) return
        if (this.#isAnimating || this.#stabilizing || this.#locked) return
        this.#trimming = true
        try {
            const primary = this.#primaryIndex
            if (primary < 0) return
            const { size } = this
            if (!size) return
            const maxDistance = size * 10
            const viewportEnd = this.#renderedEnd
            // 只销毁前向（右侧）远距离视图，不销毁左侧视图
            for (const [index, view] of this.#sortedViews) {
                if (index <= primary) continue
                const offset = this.#getViewOffset(index)
                if (offset - viewportEnd > maxDistance) {
                    this.#destroyView(index)
                }
            }
            try { window.EPUBBridge?.log?.('[trim] primary=' + primary + ' keep=[' +
                [...this.#views.keys()].sort((a, b) => a - b).join(',') + ']') } catch (_) {}
        } finally {
            this.#trimming = false
        }
    }
    /** 取下一个待预排章节：只可能是 primary 的相邻两章（primary±1）。 */
    #popNearestPrep() {
        const cur = this.#primaryIndex
        const needed = [cur - 1, cur + 1].filter(i =>
            i >= 0 && i < this.sections.length
            && this.sections[i]?.linear !== 'no' && !this.#views.has(i))
        return needed[0] ?? -1
    }
    /** 预排：逐个加载未排的相邻章，排齐即停。 */
    async #tickIdlePreload() {
        if (this.#idlePreload) return
        this.#idlePreload = true
        try {
            // 最多预排 1 次（只排一个邻章），避免长时间占用主线程
            const idx = this.#popNearestPrep()
            if (idx >= 0) await this.#loadSection(idx)
        } finally {
            this.#idlePreload = false
        }
    }
    #scheduleAllPreload() {
        this.#tickIdlePreload()
    }
    /** 渲染所有已排版视图为同一新 layout（resize / 设置变更时）。offsets 从 DOM 实时算，重排后重锚定。 */
    render() {
        if (this.#views.size === 0) return
        try { window.EPUBBridge?.log?.('[ren] ENTER primary=' + this.#primaryIndex + ' vo=' + Math.round(this.#getViewOffset(this.#primaryIndex)) + ' scroll=' + Math.round(this.#container[this.scrollProp]) + ' anchor=' + (this.#anchor ? 'yes' : 'null') + ' total=' + this.#views.size) } catch (_) {}
        const layout = this.#beforeRender({
            vertical: this.#vertical,
            rtl: this.#rtl,
        })
        this.#stabilizing = true
        for (const [, view] of this.#views) {
            if (view.document) view.render(layout)
        }
        this.#stabilizing = false
        this.#scrollToAnchor(this.#anchor)
        try { window.EPUBBridge?.log?.('[ren] DONE  primary=' + this.#primaryIndex + ' vo=' + Math.round(this.#getViewOffset(this.#primaryIndex)) + ' scroll=' + Math.round(this.#container[this.scrollProp])) } catch (_) {}
    }
    #beforeRender({ vertical, rtl, background }) {
        this.#vertical = vertical
        this.#rtl = rtl
        this.#top.classList.toggle('vertical', vertical)

        // set background to `doc` background
        // this is needed because the iframe does not fill the whole element
        // （resize 重排时 background 无实参，忽略以免清空已有背景；媒体跟随回调会补）
        if (background) this.#background.style.background = background

        const { width, height } = this.#container.getBoundingClientRect()
        const size = vertical ? height : width

        const style = getComputedStyle(this.#top)
        const maxInlineSize = parseFloat(style.getPropertyValue('--_max-inline-size'))
        const maxColumnCount = parseInt(style.getPropertyValue('--_max-column-count-spread'))
        const margin = parseFloat(style.getPropertyValue('--_margin'))
        this.#margin = margin
        // 四向独立页边距：随 layout 一起传给 View，供 columnize 写每页内容内边距。
        const pb = this.#pageMargin

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

            this.columnCount = 1
            const layout = { flow, margin, gap, columnWidth, pageMargin: pb, columnCount: 1 }
            this.#lastLayout = layout
            return layout
        }

        const divisor = Math.min(maxColumnCount, Math.ceil(size / maxInlineSize))
        const columnWidth = (size / divisor) - gap
        this.setAttribute('dir', rtl ? 'rtl' : 'ltr')

        // 本项目单栏整屏（每屏一页），页推进 = size；columnCount 恒为 1 供页定位一致。
        this.columnCount = 1
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

        const layout = { height, width, margin, gap, columnWidth, pageMargin: pb, columnCount: 1 }
        this.#lastLayout = layout
        return layout
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
    get containerPosition() {
        return this.#container[this.scrollProp]
    }
    set containerPosition(value) {
        this.#container[this.scrollProp] = value
    }
    get #renderedViewSize() {
        // 长条总宽 = 所有已 inFlow（物理拼入）章的实测布局宽之和；屏外预排章不计。
        let sizePx = 0
        for (const i of [...this.#views.keys()].sort((a, b) => a - b)) {
            const v = this.#views.get(i)
            if (v?.inFlow) sizePx += this.#domWidth(v)
        }
        return sizePx
    }
    get #renderedStart() {
        return Math.abs(this.#container[this.scrollProp])
    }
    get #renderedEnd() {
        return this.#renderedStart + this.size
    }
    get #renderedPage() {
        return Math.floor(((this.#renderedStart + this.#renderedEnd) / 2) / this.size)
    }
    get #renderedPages() {
        return Math.round(this.#renderedViewSize / this.size)
    }
    // primary 相对的常用只读：start/end/page/pages ── 供 #scrollToAnchor(fraction) 与外部读取一致
    get start() {
        return this.#renderedStart - this.#getViewOffset(this.#primaryIndex)
    }
    get end() {
        return this.#renderedEnd - this.#getViewOffset(this.#primaryIndex)
    }
    get page() {
        return this.#renderedPage - this.#getPagesBeforeView(this.#primaryIndex)
    }
    get pages() {
        const primaryView = this.#primaryView
        if (!primaryView) return 0
        return primaryView.contentPages
    }
    scrollBy(dx, dy) {
        const delta = this.#vertical ? dy : dx
        if (!this.#scrollBounds) return
        const { scrollProp } = this
        const [offset, a, b] = this.#scrollBounds
        const rtl = this.#rtl
        const min = rtl ? offset - b : offset - a
        const max = rtl ? offset + a : offset + b
        this.#container[scrollProp] = Math.max(min, Math.min(max,
            this.#container[scrollProp] + delta))
        try { window.EPUBBridge?.log?.('[drg] dx=' + Math.round(dx) + ' d=' + Math.round(delta) + ' sb=[' + Math.round(offset) + ',' + Math.round(a) + ',' + Math.round(b) + '] clamp=[' + Math.round(min) + ',' + Math.round(max) + '] scroll=' + Math.round(this.#container[scrollProp]) + ' start=' + Math.round(this.#renderedStart) + ' voP=' + Math.round(this.#getViewOffset(this.#primaryIndex)) + ' prio=' + this.#primaryIndex) } catch (_) {}
    }
    snap(vx, vy) {
        if (!this.#scrollBounds) return
        const size = this.size
        const rest0 = this.#touchState?.restBase ?? this.#scrollBounds[0]
        const delta = this.containerPosition - rest0
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
        // 越界：直接去相邻章（从对应边缘起）
        const pages = this.#renderedPages
        if (target < 0) {
            try { window.EPUBBridge?.log?.('[snap] target<0 target=' + target + ' pages=' + pages + ' rest0=' + rest0 + ' dir=' + dir + ' → goToEdge(-1)') } catch (_) {}
            return this.#goToEdge(-1)
        }
        if (target >= pages) {
            try { window.EPUBBridge?.log?.('[snap] target>=pages target=' + target + ' pages=' + pages + ' rest0=' + rest0 + ' dir=' + dir + ' → goToEdge(1)') } catch (_) {}
            return this.#goToEdge(1)
        }
        return this.#scrollToPage(target, 'snap')
    }
    #onTouchStart(e) {
        // 触摸由 reader.html（宿主窗口）全权处理跟手 scrollBy + snap 时短路，避免与自身双处理
        // （双调 scrollBy → 距离翻倍；双 snap → 翻两页）。
        if (this._readerDrag) return
        const touch = e.changedTouches[0]
        this.#touchState = {
            x: touch?.screenX, y: touch?.screenY,
            t: e.timeStamp, vx: 0, vy: 0, tdx: 0, tdy: 0,
            // 保存拖拽开始时的静止基准 scroll，防止预排补偿/裁剪在拖拽中改 scrollBounds 后
            // snap 读到「补偿后」的 rest0 → 目标页算错 → 误触发 goToEdge 跳到错误章节。
            restBase: this.#scrollBounds?.[0] ?? 0,
        }
        try {
            const pv = this.#primaryView
            window.EPUBBridge?.log?.('[tst] ↓ touchStart prio=' + this.#primaryIndex +
                ' vo=' + Math.round(this.#getViewOffset(this.#primaryIndex)) +
                ' scroll=' + Math.round(this.#container[this.scrollProp]) +
                ' renderedPage=' + this.#renderedPage + ' pagesBeforePri=' + this.#getPagesBeforeView(this.#primaryIndex) +
                ' cp=' + (pv?.contentPages ?? '?') + ' size=' + Math.round(this.size) +
                ' sb=' + JSON.stringify(this.#scrollBounds?.map(v => Math.round(v))))
        } catch (_) {}
    }
    #onTouchMove(e) {
        if (this._readerDrag) return
        const state = this.#touchState
        if (state.pinched) return
        state.pinched = globalThis.visualViewport.scale > 1
        if (this.scrolled || state.pinched) return
        if (e.touches.length > 1) {
            if (this.#touchScrolled) e.preventDefault()
            return
        }
        e.preventDefault()
        const touch = e.changedTouches[0]
        const x = touch.screenX, y = touch.screenY
        const dx = state.x - x, dy = state.y - y
        const dt = Math.max(1, e.timeStamp - state.t)
        state.x = x
        state.y = y
        state.t = e.timeStamp
        state.vx = dx / dt
        state.vy = dy / dt
        state.tdx += dx
        state.tdy += dy
        this.#touchScrolled = true
        // 横向意图 && 超过阈值 → 拖动预览（实时 scrollLeft）
        const horiz = Math.abs(state.tdx), vert = Math.abs(state.tdy)
        if (horiz > 10 && horiz > vert) this.scrollBy(dx, 0)
    }
    #onTouchEnd() {
        if (this._readerDrag) return
        this.#touchScrolled = false
        const vx = this.#touchState?.vx ?? 0
        requestAnimationFrame(() => {
            if (!this.scrolled && globalThis.visualViewport.scale === 1)
                this.snap(vx, this.#touchState?.vy ?? 0)
        })
    }
    // allows one to process rects as if they were LTR and horizontal
    #getRectMapper(view) {
        const viewSize = view.element.getBoundingClientRect()[this.sideProp]
        if (this.scrolled) return this.#vertical
            ? ({ left, right }) => ({ left: viewSize - right, right: viewSize - left })
            : ({ top, bottom }) => ({ left: top + this.#margin, right: bottom + this.#margin })
        return this.#rtl
            ? ({ left, right }) => ({ left: viewSize - right, right: viewSize - left })
            : this.#vertical
                ? ({ top, bottom }) => ({ left: top, right: bottom })
                : f => f
    }
    async #scrollToRect(rect, reason) {
        if (this.scrolled) {
            const localOffset = this.#getRectMapper(this.#primaryView)(rect).left - this.#margin
            const viewOffset = this.#getViewOffset(this.#primaryIndex)
            return this.#scrollTo(viewOffset + localOffset, reason)
        }
        // rect 是 iframe 本地坐标；加 primary 视图偏移换算成容器滚动坐标
        const localOffset = this.#getRectMapper(this.#primaryView)(rect).left
        const viewOffset = this.#getViewOffset(this.#primaryIndex)
        const containerOffset = viewOffset + localOffset
        return this.#scrollToPage(Math.floor(containerOffset / this.size + 0.01), reason)
    }
    async #scrollTo(offset, reason, smooth = true) {
        const element = this.#container
        const { scrollProp, size } = this
        const apply = o => {
            element[scrollProp] = o
            this.#scrollBounds = [o, this.atStart ? 0 : size, this.atEnd ? 0 : size]
        }
        if (Math.abs(element[scrollProp] - offset) < 1) {
            apply(offset)
            this.#afterScroll(reason)
            return
        }
        const animating = this.hasAttribute('animated') && (reason === 'snap' || smooth)
        if (animating) {
            const from = element[scrollProp]
            try { window.EPUBBridge?.log?.('[sc] ' + reason + ' primary=' + this.#primaryIndex + ' scroll ' + Math.round(from) + '→' + Math.round(offset) + ' (Δ' + Math.round(offset - from) + ') cp=' + (this.#primaryView?.contentPages ?? '?')) } catch (_) {}
            this.#isAnimating = true
            try {
                await animate(from, offset, 260, easeOutQuad, x => { element[scrollProp] = x })
            } catch (e) {
                // 动画被打断/抛错：记录现场，防止 #isAnimating 泄漏成"永久锁死、交互全失效"
                try { window.EPUBBridge?.log?.('[sc] animate ERR reason=' + reason + ' ' + (e?.stack ?? e)) } catch (_) {}
            } finally {
                this.#isAnimating = false
            }
            apply(offset)
        } else apply(offset)
        this.#afterScroll(reason)
    }
    async #scrollToPage(page, reason, smooth) {
        const offset = this.size * (this.#rtl ? -page : page)
        try { window.EPUBBridge?.log?.('[flip] reason=' + reason + ' from=' + Math.round(this.#renderedStart) + ' page=' + page + '→px' + Math.round(offset) + ' b4=' + this.#getPagesBeforeView(this.#primaryIndex) + ' cp=' + (this.#primaryView?.contentPages ?? '?') + ' prio=' + this.#primaryIndex + ' vo=' + Math.round(this.#getViewOffset(this.#primaryIndex)) + ' sz=' + Math.round(this.size)) } catch (_) {}
        // 诊断：翻到「当前章末页」时各打一次，观察前后章当时的装配/预排状态
        if (this.#views.size && !this.scrolled) {
            const b4 = this.#getPagesBeforeView(this.#primaryIndex)
            const cp = this.#primaryView?.contentPages ?? 1
            if (page === b4 + cp - 1) this.#snap('pageEnd' + this.#primaryIndex + '@' + page)
        }
        return this.#scrollTo(offset, reason, smooth)
    }
    /** 等待主章排版完全定稿后再锚定。
     *  字体/图片加载会让 contentPages 重排（日志里往往 1→3→4 页）；若按中途页数计算滚动，
     *  重排后 scroll 会错位到章末/前一章。已定稿的邻章（字体就绪）几乎不增加耗时。
     *  供恢复/目录/书签/批注/链接等所有锚点归位共用。 */
    async scrollToAnchor(anchor, select) {
        return this.#scrollToAnchor(anchor, select ? 'selection' : 'navigation')
    }
    async #scrollToAnchor(anchor, reason = 'anchor') {
        this.#anchor = anchor
        const rects = uncollapse(anchor)?.getClientRects?.()
        // 元素 / Range 锚点
        if (rects) {
            const rect = Array.from(rects)
                .find(r => r.width > 0 && r.height > 0) || rects[0]
            if (!rect) return
            await this.#scrollToRect(rect, reason)
            return
        }
        if (this.scrolled) {
            const primaryOffset = this.#getViewOffset(this.#primaryIndex)
            const primarySize = this.#primaryView
                ? this.#primaryView.element.getBoundingClientRect()[this.sideProp] : this.#renderedViewSize
            await this.#scrollTo(primaryOffset + anchor * primarySize, reason, false)
            return
        }
        // 分数锚点 → 换算成 primary 内页 + 前置页，再定位到容器页
        const primaryView = this.#primaryView
        if (!primaryView) return
        const pagesBeforePrimary = this.#getPagesBeforeView(this.#primaryIndex)
        const textPages = primaryView.contentPages
        const newPage = Math.round(anchor * Math.max(0, textPages - 1))
        await this.#scrollToPage(pagesBeforePrimary + newPage, reason, false)
    }
    #getVisibleRange() {
        const targetView = this.#primaryView
        if (!targetView?.document) return
        const viewOffset = this.#getViewOffset(this.#primaryIndex)
        if (this.scrolled) {
            const range = getVisibleRange(targetView.document,
                this.#renderedStart - viewOffset,
                this.#renderedEnd - viewOffset,
                this.#getRectMapper(targetView))
            return range ? { range, index: this.#primaryIndex } : undefined
        }
        const range = getVisibleRange(targetView.document,
            this.#renderedStart - viewOffset,
            this.#renderedEnd - viewOffset,
            this.#getRectMapper(targetView))
        return range ? { range, index: this.#primaryIndex } : undefined
    }
    #afterScroll(reason) {
        // #goTo 进入稳定期时会先把 #primaryIndex 设为目标章，再 await #settleViewWidth（最长 1.5s）
        // 锚定。这段等待期间若用户仍滑动/系统触发 scroll，会走 #detectPrimaryView 把主章改回
        // "当前仍可见"的邻章 → 随后 #scrollToAnchor 又按 #primaryIndex 锚定，导致跨章落点退回调转前的
        // 本章开头。稳定期内不得再做滚动驱动的 primary 覆写。
        if (this.#views.size > 1 && reason !== 'anchor' && reason !== 'navigation' && !this.#stabilizing
            && !this.#isAnimating)
            this.#detectPrimaryView()
        const { range, index: visibleIndex } = this.#getVisibleRange() || {}
        if (!range) return
        this.#lastVisibleRange = range
        if (reason === 'selection' || reason === 'navigation' || reason === 'anchor')
            this.#justAnchored = true
        else this.#anchor = range

        const index = visibleIndex ?? this.#primaryIndex
        const primaryView = this.#primaryView
        const detail = { reason, range, index }
        if (this.scrolled) {
            const primaryOffset = this.#getViewOffset(index)
            const primarySize = primaryView
                ? primaryView.element.getBoundingClientRect()[this.sideProp] : this.#renderedViewSize
            detail.fraction = primarySize > 0
                ? Math.max(0, Math.min(1, (this.#renderedStart - primaryOffset) / primarySize)) : 0
        } else if (this.#renderedPages > 0 && primaryView) {
            const page = this.#renderedPage
            const pagesBeforePrimary = this.#getPagesBeforeView(index)
            const textPages = primaryView.contentPages
            this.#header.style.visibility = page > 0 ? 'visible' : 'hidden'
            const localPage = page - pagesBeforePrimary
            detail.fraction = textPages > 0
                ? Math.max(0, Math.min(1, localPage / textPages)) : 0
            detail.size = textPages > 0 ? 1 / textPages : 1
        }
        this.dispatchEvent(new CustomEvent('relocate', { detail }))
    }
    /** 主力加载入口：目标章已排好则复用（换 primary + 滚动）；否则加载并定位，随后预排邻章。 */
    async #goTo({ index, anchor, select }) {
        if (!this.#canGoToIndex(index)) return
        const section = this.sections[index]
        if (!section || section.linear === 'no') return
        this.#stabilizing = true
        let view = this.#views.get(index)
        if (!view) {
            view = this.#createView(index)
            const afterLoad = doc => {
                if (index === 0 && isCoverLike(doc)) {
                    view.isCover = true
                    const $coverStyle = doc.createElement('style')
                    $coverStyle.textContent =
                        'html, body, body > * { margin: 0 !important; padding: 0 !important; height: 100% !important; min-height: 100% !important; }'
                    doc.head.append($coverStyle)
                }
                if (doc.head) {
                    const $styleBefore = doc.createElement('style')
                    doc.head.prepend($styleBefore)
                    const $style = doc.createElement('style')
                    doc.head.append($style)
                    this.#styleMap.set(doc, [$styleBefore, $style])
                }
                this.#applyStylesToDoc(doc)
                this.dispatchEvent(new CustomEvent('load', { detail: { doc, index } }))
            }
            const src = await section.load()
            if (typeof src !== 'string') throw new Error(`src of section ${index} is not string`)
            try {
                // 主章：走真实 #beforeRender（含 background / vertical / rtl 全局态），余量交给 #lastLayout 复用
                await view.load(src, afterLoad, args => this.#beforeRender(args))
            } catch (e) {
                console.warn(e)
                console.warn(new Error(`Failed to load section ${index}`))
                this.#destroyView(index)
                this.#stabilizing = false
                return
            }
            if (!view.document?.body) {
                this.#destroyView(index)
                this.#stabilizing = false
                return
            }
        }
        this.#primaryIndex = index
        // 目标章必须在 contentPages 定稿后才落位：否则用临时(未定)宽度 + 翻页动画去 scrollToAnchor，
        // 定稿过程(如 cp 1→3→4)每次 expand 都触发 onExpand→#scrollToAnchor 重锚 → 前后翻页均闪现。
        // 此刻 #stabilizing=true，onExpand 重锚被抑制；等定稿后再一次性精确落位。
        await this.#settleViewWidth(view)
        // 只销毁距目标 >1 的远章，保留 ±1 邻章（历史窗口）
        const drop = [...this.#views.keys()]
            .filter(i => i !== index && Math.abs(i - index) > 1)
        if (drop.length) {
            // 销毁右侧远章（无补偿）
            const right = drop.filter(i => i > index).sort((a, b) => b - a)
            for (const i of right) this.#destroyView(i)
            // 销毁左侧远章（无补偿，scrollToAnchor 会重新锚定）
            const left = drop.filter(i => i < index).sort((a, b) => a - b)
            for (const i of left) this.#destroyView(i)
        }
        // 若长条只剩目标章自身，重置 scroll 到 0
        if (this.#views.size === 1) this.containerPosition = 0
        const hasFocus = this.#primaryView?.document?.hasFocus()
        try { window.EPUBBridge?.log?.('[#go] idx=' + index + ' vo=' + Math.round(this.#getViewOffset(index)) + ' scroll=' + Math.round(this.#container[this.scrollProp]) + ' cp=' + (this.#views.get(index)?.contentPages ?? '?')) } catch (_) {}
        // #goTo 加载的视图初始 position:fixed，现转为 relative 进入 flex 长条
        view.inFlow = true
        Object.assign(view.element.style, {
            position: 'relative', left: 'auto', top: 'auto',
        })
        // 在主章定位前预加载前一章（短章需要填充前置列）
        if (!this.scrolled) {
            const primaryView = this.#primaryView
            const needsPrev = primaryView && primaryView.contentPages > 0
                && primaryView.contentPages < this.columnCount
            if (needsPrev) {
                const sorted = this.#sortedViews
                const firstIndex = sorted[0]?.[0]
                if (firstIndex != null && firstIndex >= this.#primaryIndex) {
                    const prevIdx = this.#adjacentIndex(-1, firstIndex)
                    if (prevIdx != null) await this.#loadSection(prevIdx)
                }
            }
        }
        const primaryView = this.#primaryView
        const resolvedAnchor = (typeof anchor === 'function'
            ? anchor(primaryView.document) : anchor) ?? 0
        await this.scrollToAnchor(resolvedAnchor, select)
        this.#snap('go' + index)
        this.#stabilizing = false
        // 非阻塞填充邻章（对齐 Readest #fillVisibleArea）
        this.#fillPromise = this.#fillVisibleArea()
        this.#fillPromise.then(() => {})
        if (hasFocus) this.focusView()
    }
    // 填充邻章：确保至少 5 页 ahead，最多加载 8 个章节
    async #fillVisibleArea() {
        if (this.#filling) return
        this.#filling = true
        try {
            const { size } = this
            if (!size) return
            const minPages = 5
            const maxSections = 8

            // 加载前向章节直到至少有 minPages 页 ahead
            let iterations = 0
            while (this.#views.size < maxSections && iterations < maxSections) {
                iterations++
                const pagesAhead = Math.floor(
                    (this.#renderedViewSize - this.#renderedEnd) / size)
                if (pagesAhead >= minPages) break
                const sorted = this.#sortedViews
                const lastIndex = sorted[sorted.length - 1]?.[0]
                if (lastIndex == null) break
                const nextIdx = this.#adjacentIndex(1, lastIndex)
                if (nextIdx == null) break
                await this.#loadSection(nextIdx)
                if (!this.#views.has(nextIdx)) break
            }
        } finally {
            this.#filling = false
        }
    }
    async goTo(target) {
        if (this.#locked) return
        const resolved = await target
        if (this.#canGoToIndex(resolved.index)) {
            try { await this.#goTo(resolved) }
            catch (e) { console.warn(e) }
        }
    }
    #canGoToIndex(index) {
        return index >= 0 && index <= this.sections.length - 1
    }
    #scrollPrev(distance) {
        if (this.#views.size === 0) return true
        if (this.scrolled) {
            if (this.#renderedStart > 0) return this.#scrollTo(
                Math.max(0, this.#renderedStart - (distance ?? this.size)), null, true)
            return !this.atStart
        }
        if (this.atStart) return
        const page = this.#renderedPage - 1
        if (page < 0) return true // 越界 → 交 #goToEdge 去前一章
        return this.#scrollToPage(page, 'page', true)
    }
    #scrollNext(distance) {
        if (this.#views.size === 0) return true
        if (this.scrolled) {
            if (this.#renderedViewSize - this.#renderedEnd > 2) return this.#scrollTo(
                Math.min(this.#renderedViewSize, distance ? this.#renderedStart + distance : this.#renderedEnd), null, true)
            return !this.atEnd
        }
        if (this.atEnd) return
        const page = this.#renderedPage + 1
        const pages = this.#renderedPages
        if (page >= pages) return true // 越界 → 交 #goToEdge 去下一章
        return this.#scrollToPage(page, 'page', true)
    }
    get atStart() {
        const sorted = this.#sortedViews
        const firstIndex = sorted[0]?.[0] ?? this.#primaryIndex
        if (this.scrolled) return this.#adjacentIndex(-1, firstIndex) == null && this.#renderedStart <= 0
        return this.#adjacentIndex(-1, firstIndex) == null && this.#renderedPage <= 0
    }
    get atEnd() {
        const sorted = this.#sortedViews
        const lastIndex = sorted[sorted.length - 1]?.[0] ?? this.#primaryIndex
        if (this.scrolled) return this.#adjacentIndex(1, lastIndex) == null && this.#renderedViewSize - this.#renderedEnd <= 2
        return this.#adjacentIndex(1, lastIndex) == null && this.#renderedPage >= this.#renderedPages - 1
    }
    #adjacentIndex(dir, fromIndex) {
        if (fromIndex === undefined) fromIndex = this.#primaryIndex
        for (let index = fromIndex + dir; this.#canGoToIndex(index); index += dir)
            if (this.sections[index]?.linear !== 'no') return index
    }
    /** 跨章：从**当前主章**直接跳到其相邻章（对标 readest goTo(adjacentIndex(±1))）。
     *  不从"长条物理边缘"再找章——那样会把屏外预排中的邻章一并越过去（跳章/乱跳）。
     *  目标章未就绪交给 #goTo：加载→settle 稳定→按真实 DOM 偏移锚定→动画。 */
    #goToEdge(dir) {
        const idx = this.#adjacentIndex(dir, this.#primaryIndex)
        try { window.EPUBBridge?.log?.('[goToEdge] dir=' + dir + ' primary=' + this.#primaryIndex + ' target=' + idx + ' views=' + JSON.stringify([...this.#views.keys()]) + ' inFlow=' + JSON.stringify([...this.#views.keys()].filter(i => this.#views.get(i)?.inFlow))) } catch (_) {}
        if (idx == null) return
        return this.#goTo({
            index: idx,
            anchor: dir < 0 ? () => 1 : () => 0,
        })
    }
    async #turnPage(dir, distance) {
        if (this.#locked) return
        this.#locked = true
        const prev = dir === -1
        try {
            const shouldGo = await (prev ? this.#scrollPrev(distance) : this.#scrollNext(distance))
            if (shouldGo) await this.#goToEdge(dir)
            if (shouldGo || !this.hasAttribute('animated')) await wait(100)
        } finally {
            this.#locked = false
        }
    }
    prev(distance) {
        return this.#turnPage(-1, distance)
    }
    next(distance) {
        return this.#turnPage(1, distance)
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
        if (this.#primaryView) return [{
            index: this.#primaryIndex,
            overlayer: this.#primaryView.overlayer,
            doc: this.#primaryView.document,
        }]
        return []
    }
    setStyles(styles) {
        this.#styles = styles
        for (const view of this.#views.values()) {
            if (view.document) this.#applyStylesToDoc(view.document)
        }
        // NOTE: needs `requestAnimationFrame` in Chromium
        requestAnimationFrame(() => {
            if (this.#primaryView?.document)
                this.#background.style.background = getBackground(this.#primaryView.document)
        })
        // needed because the resize observer doesn't work in Firefox
        this.#primaryView?.document?.fonts?.ready?.then(() => this.#primaryView.expand())
    }
    /** 刷新已渲染封面页的 object-fit：按 window.__coverProportional（阅读设置「封面等比例缩放」开关）
     *  重设封面图片填充方式，无需重排即可即时切换 等比缩放/拉伸铺满。非封面 view 不受影响。 */
    refreshCoverFit() {
        for (const view of this.#views.values()) {
            if (view?.isCover) view.setImageSize()
        }
    }
    focusView() {
        this.#primaryView?.document?.defaultView?.focus()
    }
    destroy() {
        this.#observer.unobserve(this)
        for (const [index] of [...this.#views]) this.#destroyView(index)
        this.#views.clear()
        this.#primaryIndex = -1
        this.sections?.[0]?.unload?.()
        this.#mediaQuery.removeEventListener('change', this.#mediaQueryListener)
    }
}

customElements.define('foliate-paginator', Paginator)