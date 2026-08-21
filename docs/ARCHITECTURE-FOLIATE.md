# Foliate 架构与里程碑（Orilum 阅读内核）

> 本文件描述以 **foliate-js 渲染 + 自建 Kotlin 数据层** 为核心的新阅读内核。
> 取代早期基于 `EpubNavigatorFragment` 的方案（后者分页被锁死为双栏对开、且无连续滚动与卷页能力）。

- 状态：**施工中**。本文书为**设计稿 + 工作清单**；进度以 [`PROJECT_STATUS.md`](PROJECT_STATUS.md) 为唯一溯源。
- 原则：**地基先行、逐功能完善** —— 不抢跑，每个功能做完整、确认稳定后再进入下一个
- 编写日期：2026-08-20（里程碑部分 2026-08-21 重构为「已完成 / 待办」清单）

---

## 0. 为什么换内核

| 需求 | readium-kotlin 3.1.0 | foliate-js |
|---|---|---|
| 单栏整页翻页 | ❌ 锁死双栏对开（`column-count==2` 闸门） | ✅ 原生 `flow=paginated` |
| 全书连续滚动 | ❌ 仅分章内滚动 | ✅ 原生 `flow=scrolled` |
| 左右滑动翻页动画 | ❌ 仅画布滚动 | 原生+原生 pager 可做 |
| 卷页翻页动画 | ❌ | 原生 pager 插件可做 |
| 体积/性能 | 重（DRM/streamer 连带） | 轻、纯 JS、单管线 |
| 定位（CFI） | ✅ | ✅ `epubcfi.js` |

核心结论：readium 缺的恰好是我们要的；foliate-js 两个模式都是一等公民，且是单框架。

---

## 1. 架构总览（分层）

```
┌──────────────────────────────────────────────────────┐
│ Kotlin 薄壳层（Application / Activity / ViewModel）      │
│  · 书架 · 进度/书签/笔记 UI · 阅读 overlay · 原生 pager   │
├──────────────────────────────────────────────────────┤
│ Kotlin 数据层（自建）                                    │
│  · 解析器：解 zip → 读 container.xml → opf/spine/目录     │
│  · 定位：章节 + 字符偏移 / CFI 兼容                       │
│  · 持久层：进度 / 书签 / 笔记 / 修订（Room）               │
│  · 字体：样式体系（主题 + 逐条 + UI 控件）分层             │
├──────────────────────────────────────────────────────┤
│ 通信桥（JSBridge）                                      │
│  · Kotlin ⇄ WebView（JS）：派发章节、上报进度、注入样式      │
├──────────────────────────────────────────────────────┤
│ WebView + foliate-js（渲染层）                          │
│  · 排版 / 按屏切页 / 文本与选区 / CFI / transformTarget    │
│  · flow=paginated | flow=scrolled（两种浏览模式原生）      │
└──────────────────────────────────────────────────────┘
```

**职责边界**
- **foliate-js 只做**：排版输出、把内容按屏切成页、给文本/选区/CFI、加载章节资源。
- **Kotlin 全权负责**：解析、定位、进度/书签/笔记、翻页容器与动画、字体与样式分层、科技渲染钩子。
- 遵循“JS 只负责排版、其他尽量自己来”的既定方向。

---

## 2. 渲染管线与页面化（动画的地基）

关键：**割离「排版」与「翻页呈现」**，让动画成为原生层的独立能力。

1. foliate-js 在 `flow=paginated` 下按视口把某章切成若干页（每页一个 viewport）。
2. **页面化**：Kotlin 把当前可见页渲染成位图（`captureIntoBitmap` / 截屏），或按页独立视图持有。
3. **原生 pager**：在页位图之间切换，负责动画与手势，Kotlin 持有。
4. 进下一页 → 请求 foliate 下一页范围 → 渲染新位图 → 预加载下一张。

> 这样第一阶段只要“滑动翻页”就用原生 pager 的滑入滑出；卷页作为**同一 pager 上的动画插件**单独加，不影响架构。

**页面化代价（需在工程中接受并控制）**
- 位图内存：需要按页回收 + LRU；大图/长字重注意。
- 文本选中、链接跳转、放大：位图页不原生可交互，需按需“热切换”回真实 DOM 页。
- 方案取舍：滑动翻页既可“位图 pager”，也可“每页一个轻量 WebView 页”。首版建议**位图 pager + 单 WebView**，稳定后再评估。

---

## 3. 两种浏览模式（原生）

| 模式 | foliate-js `flow` | 交互 |
|---|---|---|
| 翻页式（单栏整页） | `paginated` | 竖屏一屏一页、左右翻页；原生 pager 动画 |
| 滚动式（全书连续） | `scrolled` | 纵向连续滚动；章节间无缝衔接 |

- 切换 = 改 `flow` 属性（`reader.js` 中 `setAttribute('flow', value)`），运行时切换，无需重建。
- 两种模式均**原生一级公民**，这正是换引用的关键收益。

---

## 4. 解析层（自建 Kotlin）

- 解 zip（标准 zip 库）→ `META-INF/container.xml` → `package.opf` → `spine` / `manifest` / `toc(nav)`。
- 产出「章节序列 + 每个章节的正文 xhtml 字节」，喂给 foliate-js 渲染。
- 定位：以**章节 index + 章内字符偏移/比例**为主；同时产 CFI 兼容串（复用或比对 `epubcfi.js` 规则）以支持书签/笔记的稳定溯回。
- 兼容面先收窄到规范 EPUB + 常见偏移的“脏书”，逐功能回归后放宽。

**分批边界**：先只做 EPub3/EPub2 文本 + 图片资源，字体解混淆、嵌套样式后置。

---

## 5. 进度 / 书签 / 笔记 / 修订（自建 + Room）

- 进度：foliate `relocate` 上报 {index, fraction}；Kotlin 存 `BookReadingState`。
- 书签：CFI 串写 Room，跨重启解析回章节+位置。
- 高亮/笔记：foliate `overlayer.js` 渲染覆盖层，底层 CFI 持久化 Room。
- 修订（改写）：选区→CFI → Revision 写 Room → 阅读叠加 → 还原=删修订记录。

**等待 UI 架构定稿再接线**，因此数据层先做成可独立单测的纯逻辑。

---

## 6. 字体与排版（样式体系）

> ⚠️ **本节描述的是旧模式（五分类 + L1/L2/L3）**，2026-08-21 已被**样式系统**（主题列表 + 逐条输入 + UI 常驻控件）整体取代。完整新设计见 `PROJECT_STATUS.md`「样式系统」。下方保留旧思路作设计史参考。

- foliate-js 提供 `fontSize/fontFamily/settings` 等原生项 ✅；但**多分类字体（正/题/码/粗/斜）与“跟随原书样式”/替换字体** 是我们的差异点，需自写。
- 实现载体：foliate-js 的 `transformTarget` 事件钩子（渲染前改写 DOM）+ 注入自定义 CSS 层（`reader.html` 的 `buildReadingCSS`）。
- 新样式体系分层（取代 L1/L2/L3）：
  - L1 底座（原书样式 / 系统预设主题）
  - L2 用户导入样式表开关
  - L3 逐项指定（最高优先）+ 一键还原逐项
- 中式字重：粗→黑体、斜→楷体（非合成样式）等，按原设计实现。

---

## 7. 科技渲染（外置预处理 + 干净 HTML 喂给 foliate）

> 核心原则：**LaTeX / ABC 乐谱 / Mermaid 等局部重计算元素，不在 WebView 主线程做**。
> 外置独立 JS 运行时（QuickJS）预处理，输出 SVG 片段，再交给 foliate。不改造 foliate、不把公式计算压到 WebView JS 主线程。

**数据流完整链路**

```
EPUB 原始 XHTML 片段（Kotlin 读出）
        ↓
【外置预处理层：QuickJS】
  ├─ 匹配 $...$ / $$...$$ / ```abc / ```mermaid 标记
  ├─ 调用 katex.renderToString() / ABCJS.renderAbc() / mermaid.render()
  └─ 输出 SVG 字符串，原地替换标记块
        ↓
产出：已经把公式、乐谱、图表全部替换为 SVG 的干净 XHTML
        ↓
送入 WebView → foliate-js
        ↓
foliate 只做：DOM 挂载、CSS 流式排版、分页计算、CFI 映射、交互
```

**关键点**
1. QuickJS 无 DOM、无 window，只跑纯函数，做「字符串 → SVG 字符串」转换；
2. foliate 拿到的已是普通 SVG 标签，与普通图片无差别，**无需在 transformTarget 里跑重型渲染**；
3. WebView 主线程不再承担解析 LaTeX/乐谱语法的 CPU 开销，只做布局绘制。

**时序修正**：预处理在 Kotlin 侧完成、送入 foliate 之前；`transformTarget` 仅做轻量调整（样式层叠加、类名修改），不再跑重型计算。

**边界处理**
1. Prism 代码高亮：统一挪入 QuickJS 预处理，输出带样式的 `<pre><code>` HTML（与主路径一致）。
2. 原书自带 MathML：不经过 KaTeX，直接放行，交给 WebView（Blink）原生 MathML 渲染；QuickJS 只处理 LaTeX 源码、abc、mermaid 代码块。
3. **防断裂 CSS**：随字体/排版层（§6）注入，不在此处做。

### 7.1 CFI 映射完整性（本方案最大坑）

> 预处理会修改 HTML 文本、改变 DOM 节点，直接处理会破坏原书 CFI 位置。

- **CFI 以原始未处理 XHTML 为基准**；预处理只做视觉替换，同时在 Kotlin 层维护一份「原始位置 ↔ 替换后 DOM」映射表。
- foliate 上报选区/CFI 时，Kotlin 查表反向映射回原始文档的 CFI，用于书签、笔记持久化。
- 若不维护映射，高亮、笔记跳转会全部错位——这是本方案最易踩坑点，随「待办 · 外置 QuickJS 预处理」一并实现并加大单测覆盖。

### 7.2 技术选型（Android）

- JS 运行时：**QuickJS-Android**（so 库，约 600–800KB，无浏览器 API，仅纯函数执行）。
- 打包进 QuickJS 的脚本：katex（仅 renderToString，裁剪 DOM 相关 API）、abcjs（仅渲染函数）、mermaid（headless 输出 SVG）。
- 输入输出全部字符串，不跨边界传 DOM 对象。
- 注意：mermaid 默认依赖 DOM，须用其 `render()` 无 DOM 回调接口，不调用浏览器 API。

### 7.3 两套预案

1. **主路径**：预处理在 Kotlin 侧 QuickJS 完成，送入 foliate 干净 HTML（如上）。
2. **降级预案**：若 QuickJS 遇兼容性问题，退回到 WebView 内 **Web Worker** 执行同样的转换逻辑，同样不占主线程，只是计算发生在 WebView 进程。

> 这套设计不推翻整体架构，是对科技渲染做「性能上移」：把重型局部渲染从 WebView 环境剥离，复用笔记软件成熟的 JS 能力，同时保留 foliate 的全部排版、分页、CFI 优势。

---

## 8. 工作清单（已完成 / 待办）

> 每项结束：单元测试 + 真机/平板回归 + 记录到 `PROJECT_STATUS.md`。进度以 `PROJECT_STATUS.md` 为唯一溯源，本节为**职能全集**（含后续长期目标）。

### ✅ 已完成

- ✅ **数据层（自建）**：`EpubParser` 解析 zip → container.xml → OPF(spine/manifest/toc) → `EpubBook`；单元测试覆盖 EPUB3/EPUB2、路径大小写容错、目录关联。
- ✅ **定位模型**：`Location`（章节+章内偏移）+ `ProgressConverter`（章节↔全书进度互转、越界钳制），纯逻辑可单测。
- ✅ **书库层**：`Book` / `BookReadingState`（Room）+ `BookDao` / `AppDatabase` / `BookRepository`。
- ✅ **渲染最小管线**：`assets/reader.html` + foliate-js `<foliate-view flow="paginated">`；`WebViewAssetLoader` 走 https 虚拟域解决 ES Module/fetch 跨域。
- ✅ **SAF/选书闭环**：SAF 选书 → 私有化落盘 `filesDir/books/`（`BookImporter`）→ 交 foliate 渲染；重启后仍可读（脱离 SAF content:// 生命周期）。
- ✅ **翻页阅读**：三区点击左/右/中翻页切工具栏；滑动翻页（`renderer.scrollBy` + `next()/prev()`）；页边距行宽控制单列分栏。
- ✅ **进度存读恢复**：`reading_states.locator`（foliate `lastLocation` JSON）落库，重开 `init({ lastLocation })` 精确定位；退出按钮 `lib ›` 回书架，系统返回=退出（不污染翻页）。
- ✅ **目录导航（TOC）**：多级目录渲染、点击跳转、当前章高亮、遮罩/✕ 关闭；与设置抽屉互斥。
- ✅ **内置文件日志**：`FileLogger` 落盘 `filesDir/logs/`（按天、1MB 轮转），绕开 vivo/OPPO 系统 logcat 限流。
- ✅ **设置面板**：右侧抽屉 + 单页面板 + 多级下钻页面栈；`ReaderSettings`/`ReaderSettingsStore` 双套（默认 + 用户 `filesDir/settings/reader.json`），`EPUBBridge.getSettings/saveSettings/resetSettings` 双向同步；改动即 `setStyles(buildReadingCSS())` 实时生效 + 持久化。
- ✅ **字体后端**：`FontParser`（TTF/OTF/TTC 的 cmap/name 解析，字节序自适应）+ `FontClassifier`（按覆盖归 中文/拉丁/通用/符号/无效，过滤不可用）+ `FontRepository`/`FontDao`（Room 持久化 + 用户目录导入）。
- ✅ **字体 JS 面板**：`listFonts()` 动态拉取 → 字体选择页按**字体自身字形**渲染名称；「指定目录导入」入口；`/fonts/` handler 提供字节流。
- ✅ **样式系统 · 三套主题 CSS**：`原书设置`/`现代模式`/`传统模式`（`LAYOUT_THEMES`，传统正文衬线）注入 `buildReadingCSS` 作默认层。
- ✅ **样式系统 · 主题列表 UI**：设置面板「排版主题」列表单选，切换实时注入 + 持久化（`settings.layoutTheme`）。

### ⏳ 待办（含后续长期目标）

- ⏳ **样式系统 · UI 常驻控件层（下一步）**：字号滑块(根 em)/行距/边距跨主题保持；日夜整套配色 + 单独覆盖背景。
- ⏳ **样式系统 · 逐条输入（手写选择器）**：选择题器(h1/p/code/strong/em…) × 属性(font-family/line-height…) + 值，列表管理，后者覆盖前者；`font-family` 特判为字体选择器。
- ⏳ **样式系统 · 替换字体**：并入逐条层（`原书字体→可用字体` 特例），仅「原书」时生效。
- ⏳ **样式系统 · 废弃迁移**：移除「中英分离 + unicode-range + 五分类双字体」旧实现，数据迁移到新主题体系。
- ⏳ **样式系统 · 竖排**（待评估，低优先级）：`writing-mode: vertical-rl` + 竖排 CJK 标点替换 + 西文横放，定位为可选排版主题。
- ⏳ **字体细节**：中文字体名真机复核展示；系统字体取舍策略（是否折叠/分组）。
- ⏳ **书架增强**：续读百分比在书架列表展示；启动自动打开当前书开关。
- ⏳ **翻页细节**：翻页动画开关（滑动/无）；页码显示（需先定 span/页计数方案）。
- ⏳ **滚动式（全书连续）**：`flow=scrolled` 曾暂搁（跨章滚动受限）。方向：方向感知 + 异步预排，属引擎手术需整套回归。
- ⏳ **外置 QuickJS 预处理（科技渲染地基）**：集成 QuickJS-Android 封装 KaTeX/abcjs（文本→SVG）；XHTML 扫描识别 LaTeX/ABC/Mermaid 替换为 SVG；维护「原始文档 ↔ 预处理后 DOM」位置映射保证 CFI 不漂移 + 单测。
- ⏳ **卷页动画**：页面位图管线（LRU/回收/热切换）+ 卷页接入（可与滑动并存）。
- ⏳ **标注与修订**：高亮/下划线/笔记 + Room 持久化（overlayer + CFI 反向映射）；选区→CFI 回溯、修订/还原。
- ⏳ **科技渲染**（重计算前置到 QuickJS）：公式（LaTeX/MathML）、ABC 乐谱、Mermaid 图表链路；代码高亮（Prism 前置统一）；防断裂 CSS。
- ⏳ **网络与工具**：OPDS 1/2、WiFi 传书、SAF 书籍目录（后端能力与阅读内核解耦）。
- ⏳ **边界回归与性能**：50+ 复杂科技 EPUB 回归、Android 12–15 碎片化、内存/句柄泄漏、低端调优。

---

## 9. 决策录（含已拍板项）

**已对齐确认**
- 科技渲染：外置 QuickJS 预处理 → 干净 HTML 喂 foliate（§7），见「待办 · 外置 QuickJS 预处理」。
- 卷页动画：先滑动稳定、再卷页——已定。
- 位图 pager：位图管线作为卷页与动画的地基——已定。
- 定位：CFI 以原始 XHTML 为基准 + 位置映射表，随「外置 QuickJS 预处理」落实——已定。

**剩余一个小决策点（不阻塞当前进度）**
- **文本交互 vs 动画的取舍**：后续走「位图 pager（滑流畅、文本交互有限）」，做卷页动画时再评估是否引入每页独立 WebView 的提升文本交互路径。

---

> 本文件为设计稿 + 工作清单。逐项实现见上文清单，每条完成后更新 `PROJECT_STATUS.md`。