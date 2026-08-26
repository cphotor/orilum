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
            const expandedSize = pageCount * this.#size
            this.#element.style.padding = '0'
            this.#iframe.style[side] = `${expandedSize}px`
            this.#element.style[side] = `${expandedSize + this.#size * 2}px`
            this.#iframe.style[otherSide] = '100%'
            this.#element.style[otherSide] = '100%'
            documentElement.style[side] = `${this.#size}px`
            if (this.#overlayer) {
                this.#overlayer.element.style.margin = '0'
                this.#overlayer.element.style.left = this.#vertical ? '0' : `${this.#size}px`
                this.#overlayer.element.style.top = this.#vertical ? `${this.#size}px` : '0'
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
    #view
    #vertical = false
    #rtl = false
    #margin = 0
    // 宿主通过 setPageMargins() 注入的四向页边距（px）。作为每页内容内边距应用，
    // 让翻页动画铺满整屏，而不是被外框轨道裁切成只在边框内滑动。
    #pageMargin = null
    #index = -1
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
    /* ---- 章节驻留缓存（跨章切换动画的数据基础） ----
     * #viewCache: index -> View。已排版完成的章节视图常驻，跨章时不再销毁重建，
     *             而是直接切可见（叠放在 #container 内，改动 opacity，绝不改父节点——
     *             本 WebView 改挂载父容器会重载 iframe，丢失样式/排版）。
     * #preloadQueue/#preloading: 空闲优先级的后台预排队列（按距当前章远近，近者优先）。 */
    #viewCache = new Map()
    #preloadQueue = []
    #preloading = false
    /** 后台预排覆盖的邻章距离跨度（近章优先，最多各预排此距离内的章）。 */
    #preloadSpan = 3
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
            /* 定位上下文：多个驻留章节视图绝对定位叠放于此，永不改父节点（WebView 会重载 iframe），
               激活 = 切换可见( opacity ) + 滚动到目标页。 */
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
        this.#container.addEventListener('scroll', () => this.dispatchEvent(new Event('scroll')))
        this.#container.addEventListener('scroll', debounce(() => {
            if (this.scrolled) {
                if (this.#justAnchored) this.#justAnchored = false
                else this.#afterScroll('scroll')
            }
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
            if (!this.#view) return
            this.#background.style.background = getBackground(this.#view.document)
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
    /** 在 #container 内建一个章节视图并绝对定位叠放（同尺寸同原点）。加载后永不改父节点——
     *  这是本 WebView 下让 iframe 文档/样式存活的关键（改挂载父节点会触发其重载）。 */
    #makeResidentView(index) {
        const view = new View({
            container: this,
            onExpand: () => { if (this.#index === index) this.#scrollToAnchor(this.#anchor) },
        })
        view.index = index // 记录章节号，供激活时定位；非激活的空闲扩张不触碰活性视图滚动
        // 绝对定位叠放：占据容器整块，与其它驻留视图原点一致
        Object.assign(view.element.style, {
            position: 'absolute',
            top: '0', left: '0',
            width: '100%', height: '100%',
            opacity: '0', // 默认隐藏；激活后置可见
        })
        this.#container.append(view.element)
        return view
    }
    /** 置某视图可见 / 隐藏（叠放切换）。opacity 切换不触发 iframe 重载。 */
    #setViewVisible(view, visible) {
        if (!view) return
        view.element.style.opacity = visible ? '1' : '0'
    }
    /** 把某章已排好的样式节点应用到指定 doc（缓存视图可能不是当前活性视图，setStyles 只更新活性 View）。 */
    #applyStylesToDoc(doc) {
        if (!doc?.head || !this.#styles || !this.#styleMap.has(doc)) return
        const [before, after] = this.#styleMap.get(doc)
        if (Array.isArray(this.#styles)) {
            const [bs, s] = this.#styles
            before.textContent = bs
            after.textContent = s
        } else after.textContent = this.#styles
    }
    /** 后台准备并缓存章节视图（构建 + 排版）。命中缓存直接返回；新建成功则入 #viewCache。
     *  onLoad 在构建完成（样式注入后）触发一次；失败返回 null。 */
    async #prepView(index, onLoad) {
        if (this.#viewCache.has(index)) return this.#viewCache.get(index)
        const section = this.sections[index]
        if (!section || section.linear === 'no') return null
        const view = this.#makeResidentView(index)
        const afterLoad = doc => {
            // 封面页识别 + 置零 body 边距，使封面图整屏铺满（与官方 #display 一致）
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
            onLoad?.(doc)
        }
        const beforeRender = this.#beforeRender.bind(this)
        try {
            const src = await section.load()
            if (typeof src !== 'string') throw new Error(`src of section ${index} is not string`)
            await view.load(src, afterLoad, beforeRender)
        } catch (e) {
            console.warn(e)
            console.warn(new Error(`Failed to load section ${index}`))
            if (view.element.parentNode) view.element.parentNode.removeChild(view.element)
            return null
        }
        this.#viewCache.set(index, view)
        this.dispatchEvent(new CustomEvent('create-overlayer', {
            detail: { doc: view.document, index, attach: overlayer => view.overlayer = overlayer },
        }))
        return view
    }
    /** 把已就绪的缓存视图切换到可见容器。尺寸恒定（舞台与容器同宽），只挪元素，
     *  无重排风暴、无白屏；不销毁旧视图，留驻缓存供回退。 */
    async #activate(index, anchor, select) {
        const view = this.#viewCache.get(index)
        // 就绪守卫：仅当文档已装载（#prepView 成功后必有 body）才执行切换，绝不"先挂后等"
        if (!view?.document?.body) return
        // 老活性视图隐藏（叠放），新视图显示——节点不改父容器，iframe 不重载，样式/排版常驻
        if (this.#view && this.#view !== view) this.#setViewVisible(this.#view, false)
        this.#setViewVisible(view, true)
        this.#view = view
        this.#index = index
        // 视图在构建时已按当时的尺寸/样式排好版，勿在此重排（会破坏列宽）。
        // 仅在样式此刻已就绪时补套样式；第一屏若在 setStyles 前激活，由随后 setStyles 补齐。
        if (this.#styles) this.#applyStylesToDoc(this.#view.document)
        await this.scrollToAnchor((typeof anchor === 'function'
            ? anchor(view.document) : anchor) ?? 0, select)
    }
    /** 空闲预排调度：把确定性邻章按「距当前章远近（Math.abs 距离），近者优先」排队后台构建。
     *  只在空闲 & 非翻页(未锁定) & 页面可见时执行；每排一章让出一帧，不抢屏幕刷新。 */
    #schedulePreload() {
        const cur = this.#index
        // 按绝对章节序号差升序（越近越优先）；同距离保持前章在前
        const candidates = []
        for (let d = 1; candidates.length < this.#preloadSpan; d++) {
            for (const dir of [-1, 1]) {
                const i = cur + dir * d
                if (this.#canGoToIndex(i) && this.sections[i]?.linear !== 'no'
                    && !this.#viewCache.has(i)) candidates.push(i)
            }
            if (candidates.length >= this.#preloadSpan) break
        }
        if (!candidates.length) return
        const seen = new Set(candidates)
        // 合并：把仍缺的并入队，已有缓存的不干预
        for (const i of candidates) if (!this.#viewCache.has(i) && !this.#preloadQueue.includes(i))
            this.#preloadQueue.push(i)
        if (!this.#preloading) this.#tickPreload()
    }
    async #tickPreload() {
        this.#preloading = true
        try {
            while (this.#preloadQueue.length) {
                // 翻页中 / 后台时暂停预排，避免抢占用户能感知的动画与刷新
                if (this.#locked || document.hidden) break
                await new Promise(res => {
                    if (typeof requestIdleCallback === 'function')
                        requestIdleCallback(res, { timeout: 2000 })
                    else setTimeout(res, 300)
                })
                const idx = this.#preloadQueue.shift()
                if (this.#viewCache.has(idx)) continue
                await this.#prepView(idx)
                // 让出一帧，把合成器那一帧还给屏幕
                await new Promise(res =>
                    requestAnimationFrame(() => requestAnimationFrame(res)))
            }
        } finally {
            this.#preloading = false
        }
    }
    #beforeRender({ vertical, rtl, background }) {
        this.#vertical = vertical
        this.#rtl = rtl
        this.#top.classList.toggle('vertical', vertical)

        // set background to `doc` background
        // this is needed because the iframe does not fill the whole element
        this.#background.style.background = background

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

            return { flow, margin, gap, columnWidth, pageMargin: pb }
        }

        const divisor = Math.min(maxColumnCount, Math.ceil(size / maxInlineSize))
        const columnWidth = (size / divisor) - gap
        this.setAttribute('dir', rtl ? 'rtl' : 'ltr')

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
        if (!this.#view) return
        this.#view.render(this.#beforeRender({
            vertical: this.#vertical,
            rtl: this.#rtl,
        }))
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
        return this.#view.element.getBoundingClientRect()[this.sideProp]
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
    scrollBy(dx, dy) {
        const delta = this.#vertical ? dy : dx
        const element = this.#container
        const { scrollProp } = this
        const [offset, a, b] = this.#scrollBounds
        const rtl = this.#rtl
        const min = rtl ? offset - b : offset - a
        const max = rtl ? offset + a : offset + b
        element[scrollProp] = Math.max(min, Math.min(max,
            element[scrollProp] + delta))
    }
    snap(vx, vy) {
        const velocity = this.#vertical ? vy : vx
        const [offset, a, b] = this.#scrollBounds
        const { start, end, pages, size } = this
        const min = Math.abs(offset) - a
        const max = Math.abs(offset) + b
        const d = velocity * (this.#rtl ? -size : size)
        const page = Math.floor(
            Math.max(min, Math.min(max, (start + end) / 2
                + (isNaN(d) ? 0 : d))) / size)

        this.#scrollToPage(page, 'snap').then(() => {
            const dir = page <= 0 ? -1 : page >= pages - 1 ? 1 : null
            if (dir) return this.#goTo({
                index: this.#adjacentIndex(dir),
                anchor: dir < 0 ? () => 1 : () => 0,
            })
        })
    }
    #onTouchStart(e) {
        const touch = e.changedTouches[0]
        this.#touchState = {
            x: touch?.screenX, y: touch?.screenY,
            t: e.timeStamp,
            vx: 0, xy: 0,
        }
    }
    #onTouchMove(e) {
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
        const dt = e.timeStamp - state.t
        state.x = x
        state.y = y
        state.t = e.timeStamp
        state.vx = dx / dt
        state.vy = dy / dt
        this.#touchScrolled = true
        this.scrollBy(dx, dy)
    }
    #onTouchEnd() {
        this.#touchScrolled = false
        if (this.scrolled) return

        // XXX: Firefox seems to report scale as 1... sometimes...?
        // at this point I'm basically throwing `requestAnimationFrame` at
        // anything that doesn't work
        requestAnimationFrame(() => {
            if (globalThis.visualViewport.scale === 1)
                this.snap(this.#touchState.vx, this.#touchState.vy)
        })
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
        if (this.scrolled) {
            const offset = this.#getRectMapper()(rect).left - this.#margin
            return this.#scrollTo(offset, reason)
        }
        const offset = this.#getRectMapper()(rect).left
        return this.#scrollToPage(Math.floor(offset / this.size) + (this.#rtl ? -1 : 1), reason)
    }
    async #scrollTo(offset, reason, smooth) {
        const element = this.#container
        const { scrollProp, size } = this
        if (element[scrollProp] === offset) {
            this.#scrollBounds = [offset, this.atStart ? 0 : size, this.atEnd ? 0 : size]
            this.#afterScroll(reason)
            return
        }
        // FIXME: vertical-rl only, not -lr
        if (this.scrolled && this.#vertical) offset = -offset
        if ((reason === 'snap' || smooth) && this.hasAttribute('animated')) return animate(
            element[scrollProp], offset, 300, easeOutQuad,
            x => element[scrollProp] = x,
        ).then(() => {
            this.#scrollBounds = [offset, this.atStart ? 0 : size, this.atEnd ? 0 : size]
            this.#afterScroll(reason)
        })
        else {
            element[scrollProp] = offset
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
        // if anchor is a fraction
        if (this.scrolled) {
            await this.#scrollTo(anchor * this.viewSize, reason)
            return
        }
        const { pages } = this
        if (!pages) return
        const textPages = pages - 2
        const newPage = Math.round(anchor * (textPages - 1))
        await this.#scrollToPage(newPage + 1, reason)
    }
    #getVisibleRange() {
        if (this.scrolled) return getVisibleRange(this.#view.document,
            this.start + this.#margin, this.end - this.#margin, this.#getRectMapper())
        const size = this.#rtl ? -this.size : this.size
        return getVisibleRange(this.#view.document,
            this.start - size, this.end - size, this.#getRectMapper())
    }
    #afterScroll(reason) {
        const range = this.#getVisibleRange()
        this.#lastVisibleRange = range
        // don't set new anchor if relocation was to scroll to anchor
        if (reason !== 'selection' && reason !== 'navigation' && reason !== 'anchor')
            this.#anchor = range
        else this.#justAnchored = true

        const index = this.#index
        const detail = { reason, range, index }
        if (this.scrolled) detail.fraction = this.start / this.viewSize
        else if (this.pages > 0) {
            const { page, pages } = this
            this.#header.style.visibility = page > 1 ? 'visible' : 'hidden'
            detail.fraction = (page - 1) / (pages - 2)
            detail.size = 1 / (pages - 2)
        }
        this.dispatchEvent(new CustomEvent('relocate', { detail }))
    }
    async #display(promise) {
        const { index, anchor, select, onLoad } = await promise
        const hasFocus = this.#view?.document?.hasFocus()
        // 首开（尚无活性视图）：交还给引擎官方路径——直接在可见容器建首屏，
        // 保证样式/边距在其后 applySettings/render 到位时由引擎正常重排，不抢跑缓存。
        if (!this.#view) {
            await this.#openInitial({ index, anchor, select, onLoad })
            return
        }
        // 跨章：缓存通道——已排好的章节视图直接切上来（无白屏、无重建等待）。
        const view = await this.#prepView(index, doc => onLoad?.({ doc, index }))
        if (view) {
            await this.#activate(index, anchor, select)
            this.#schedulePreload()
        }
        if (hasFocus) this.focusView()
    }
    /** 首屏：按官方原始流程在可见 #container 内构建章节视图（不经过后台舞台/预排），
     *  由引擎保证首开的样式与尺寸正确；构建结果入 #viewCache，供后续跨章复用。 */
    async #openInitial({ index, anchor, select, onLoad }) {
        const section = this.sections[index]
        if (!section) return
        const view = this.#makeResidentView(index) // 叠放入容器，绝对定位同原点
        this.#setViewVisible(view, true)         // 首屏可见
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
            onLoad?.({ doc, index })
        }
        const beforeRender = this.#beforeRender.bind(this)
        try {
            const src = await section.load()
            if (typeof src !== 'string') throw new Error(`src of section ${index} is not string`)
            await view.load(src, afterLoad, beforeRender)
        } catch (e) {
            console.warn(e)
            console.warn(new Error(`Failed to load section ${index}`))
            this.#container.removeChild(view.element)
            return
        }
        this.#index = index
        this.#view = view
        this.#viewCache.set(index, view)
        this.dispatchEvent(new CustomEvent('create-overlayer', {
            detail: { doc: view.document, index, attach: overlayer => view.overlayer = overlayer },
        }))
        await this.scrollToAnchor((typeof anchor === 'function'
            ? anchor(view.document) : anchor) ?? 0, select)
        this.#schedulePreload()
    }
    #canGoToIndex(index) {
        return index >= 0 && index <= this.sections.length - 1
    }
    async #goTo({ index, anchor, select}) {
        if (index === this.#index) await this.#display({ index, anchor, select })
        else {
            // 旧章不再卸载：章节视图全驻留缓存，供回退/跨章动画复用。
            const onLoad = detail => {
                this.setStyles(this.#styles)
                this.dispatchEvent(new CustomEvent('load', { detail }))
            }
            // 章节源由 #prepView 内部加载并缓存，不在此重复加载。
            await this.#display({ index, anchor, onLoad, select })
        }
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
            return true
        }
        if (this.atStart) return
        const page = this.page - 1
        return this.#scrollToPage(page, 'page', true).then(() => page <= 0)
    }
    #scrollNext(distance) {
        if (!this.#view) return true
        if (this.scrolled) {
            if (this.viewSize - this.end > 2) return this.#scrollTo(
                Math.min(this.viewSize, distance ? this.start + distance : this.end), null, true)
            return true
        }
        if (this.atEnd) return
        const page = this.page + 1
        const pages = this.pages
        return this.#scrollToPage(page, 'page', true).then(() => page >= pages - 1)
    }
    get atStart() {
        return this.#adjacentIndex(-1) == null && this.page <= 1
    }
    get atEnd() {
        return this.#adjacentIndex(1) == null && this.page >= this.pages - 2
    }
    #adjacentIndex(dir) {
        for (let index = this.#index + dir; this.#canGoToIndex(index); index += dir)
            if (this.sections[index]?.linear !== 'no') return index
    }
    async #turnPage(dir, distance) {
        if (this.#locked) return
        this.#locked = true
        const prev = dir === -1
        const shouldGo = await (prev ? this.#scrollPrev(distance) : this.#scrollNext(distance))
        if (shouldGo) await this.#goTo({
            index: this.#adjacentIndex(dir),
            anchor: prev ? () => 1 : () => 0,
        })
        if (shouldGo || !this.hasAttribute('animated')) await wait(100)
        this.#locked = false
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
        if (this.#view) return [{
            index: this.#index,
            overlayer: this.#view.overlayer,
            doc: this.#view.document,
        }]
        return []
    }
    setStyles(styles) {
        this.#styles = styles
        // 应用到活性视图（沿用官方逻辑）
        const activeDoc = this.#view?.document
        if (activeDoc) this.#applyStylesToDoc(activeDoc)
        // 同步应用到所有驻留缓存视图，保证切上来时样式已是新设置
        for (const view of this.#viewCache.values())
            if (view.document !== activeDoc) this.#applyStylesToDoc(view.document)

        // NOTE: needs `requestAnimationFrame` in Chromium
        requestAnimationFrame(() =>
            this.#background.style.background = getBackground(this.#view.document))

        // needed because the resize observer doesn't work in Firefox
        this.#view?.document?.fonts?.ready?.then(() => this.#view.expand())
    }
    focusView() {
        this.#view.document.defaultView.focus()
    }
    destroy() {
        this.#observer.unobserve(this)
        this.#view?.destroy()
        this.#view = null
        // 释放所有驻留缓存视图及其章节资源
        for (const view of this.#viewCache.values()) {
            view.destroy()
            view.element.remove()
        }
        this.#viewCache.clear()
        this.#preloadQueue.length = 0
        this.sections[this.#index]?.unload?.()
        this.#mediaQuery.removeEventListener('change', this.#mediaQueryListener)
    }
}

customElements.define('foliate-paginator', Paginator)
