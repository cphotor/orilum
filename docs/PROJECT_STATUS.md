# FolioEpub — 项目进度

> 本文档记录阶段进度与关键决策，配合 `docs/ARCHITECTURE-FOLIATE.md` 使用。
> 更新规则：完成阶段任务/重大变化时追加记录。

## 当前阶段：M0 地基

目标：`foliate-js 渲染 + 自建 Kotlin 数据层` 的最小可运行闭环。

### 已完成

#### M0 · 数据层（自建）
- **EPUB 解析器**：纯 Kotlin 实现 `EpubParser`，解析 zip → container.xml → OPF (metadata/manifest/spine) → EPUB3 nav / EPUB2 NCX 目录，产出 `EpubBook` 模型。单元测试覆盖标准 EPUB3/EPUB2、路径大小写容错、目录关联场景。
- **定位模型**：`Location`（章节 + 章内偏移）+ `ProgressConverter`（章节↔全书进度互转、越界钳制），纯逻辑、可单测。
- **书库层**：`Book` / `BookReadingState`（Room），`BookDao` / `AppDatabase` / `BookRepository` 封装存取。

#### M0 · WebView + foliate-js 渲染（本次完成）
- **依赖拉取**：Gradle 任务 `downloadFoliateJs` / `fetchFoliateJs` 从 npm 镜像下载 foliate-js 1.0.1 到 `assets/foliate-js`（构建期自动完成）。
- **示例书**：`sample-epub-src/` 源码 + 构建任务 `makeSampleEpub` 打包成合法 EPUB3（mimetype 首条目 + STORED）到 `assets/sample/sample.epub`，含两个章节，第一章较长以验证「长章节多屏分页」。
- **渲染管线**：
  - `assets/reader.html`：引入 foliate-js `<foliate-view flow="paginated">`，fetch 示例书 → `open` → `init(showTextStart)`；三区点击翻页（左/右 1/3 = 前后页，中 1/3 = 切换工具栏）；`relocate` 上报 → 底部首章节+进度条；JSBridge `EPUBBridge` 上报 Kotlin。
  - `ReaderActivity`：`WebViewAssetLoader` 以 `https://appassets.androidplatform.net/assets/...` 服务本地资源（解决 ES Module 动态导入与 fetch 的跨域问题）；沉浸式；`addJavascriptInterface` 接收 relocate/日志。
  - `MainActivity`：占位书架 + 「打开示例书」按钮 → 起 ReaderActivity。
- **真机验证**：平板（1840×2800，Android 14）：
  - 单栏整页排版 ✅
  - 三区左右翻页、章节切换 ✅
  - 加长第一章后能拆成多屏逐页推进 ✅（解决了「章节太短看不出超一屏分页」）

### 进行中（M0-loop 已闭环，接近 M0 完成）
- **M0-loop · 书架闭环 ✅**：SAF 选书 → 自建 `EpubParser` 解析 → 交 foliate-js 渲染。
  - **SAF 选书 → 私有化落盘 ✅**：`BookImporter` 导入时把 epub 拷贝进 `filesDir/books/`，`Book.filePath` 存本地副本路径（Room v1→v2 迁移 `sourceUri`→`filePath`，清理失效旧行）。书一经导入即应用持有，彻底脱离 SAF content:// 授权生命周期（真机验证重启后仍可读）。
  - **`WebViewAssetLoader` 请求拦截修正 ✅**：`/book/` handler 收到的 `path` 无前导斜杠（`current.epub`），修正判断后书籍字节成功送达 foliate-js，「空白屏幕」根治。assets 静态资源经 https 虚拟域正常加载。
- **内置文件日志模块 ✅**：`FileLogger` 落盘 `filesDir/logs/`，仅 DEBUG 构建启用，按天分文件、超 1MB 轮转、单条 ≤4KB；覆盖 WebView 请求拦截/错误、foliate-js `EPUBBridge.log`、console。绕开 vivo/OPPO 平板不可关闭的系统 logcat 限流（`run-as` 可读）。
- **M1 · 进度存读恢复 ✅**：reading_states 新增 `locator` 列（foliate `lastLocation` JSON，DB v2→v3 迁移）。每次 relocate 由 JS `EPUBBridge.onLocation` 上报整份 locator 落库（`chapter`=section.current、`progress`=全书 fraction 供轻量展示）；重开时 ReaderActivity 同步预载 locator，经 `EPUBBridge.getSavedLocator` 回传 `init({ lastLocation })` 精确定位。真机验证 `settled fraction == saved fraction`（如 0.0332=0.0332）精确恢复。
  - **退出路径修复 ✅**：原「恢复前进一页」根因是无返回按钮、系统手势返回被吞成翻页污染了保存值。新增顶部「‹ 书架」按钮（`EPUBBridge.back() → finish()`），`#btn-back` 单独 `pointer-events:auto`（`#bars` 为穿透）；系统返回键统一为 `finish()` 退出，翻页只走三区点击。
- **M1 · 章号显示修复 ✅**：relocate 改用 `lastLocation.section.current`（此前取 `e.detail.index` 为 undefined）。

### 待办（M1 进行）
- ~~一屏一页翻页：左右点击已可用，滑动翻页默认开启~~ ✅ **滑动翻页完成**：走 foliate 原生两页分栏滚动——拖动 `renderer.scrollBy` 实时露出相邻列（两页同屏），松手 `next()/prev()` 提交、`animated` 属性平滑滑动；三区点击翻页保留，滑动方向已修正（左滑下一页/右滑上一页）。
- **翻页动画开关 ✅记TODO**：滑动是翻页动画之一，需要一个后台开关控制「滑动/无」动画，**暂不实现**，先默认滑动 <code>reader.html</code> 内 TODO(settings)。
- **打开应用自动打开当前书 ✅记TODO**：需要一个「启动时自动打开上次阅读的书」开关，**暂不实施**（当前仍从书架点开）。
- ~~目录导航（TOC 面板）。~~ ✅ **已完成**：目录树渲染（多级/缩进）、点击跳转 `goTo`、当前章高亮、遮罩/✕ 关闭。
- **页码显示 ✅记TODO**：分栏 paginated 模式下计算当前页/总页码并显示在工具栏中，**暂不实现**（推进至后续），需先确定 span/页计数方案。
- **设置 UI 框架 ✅（M1 完成）**：右侧抽屉 + HTML 单页面板 + 多级下钻页面栈。底部工具行 ⚙ 打开，与目录抽屉互斥；顶层平铺所有设置项（字号/行距/页边距/字体/阅读背景 + 翻页动画/自动续读/页码开关），可下钻项带 ›、二级页 ‹ 返回/✕ 关闭；内容超高时列表内滚动。**后续所有设置开关的载体**。现有设置项均暂全局生效，全局/按书作用域区分**待定**；下钻项控件与 M2 数据层待接（现为占位）。
- ~~scrolled（滚动）模式切换。~~ ⏸ **暂搁（代码已移除）**：曾用 foliate 原生 `flow="scrolled"` 实现，存在两个根因问题，推到后续专项解决：
  - **行宽/折行差异（存疑）**：切换翻页↔滚动时个别章节折行略微变化。初步怀疑两模式正文区宽/边距取法不同（翻页 `columnize` 动态列宽 vs 滚动 `body max-width:720px`）。我两次估算差异几十~百像素，但你实机观感仅几个像素——结论是**必须加诊断日志取真实 columnWidth/渲染宽度，禁止再盲猜**。
  - **跨章滚动受限（根因确凿）**：foliate-paginator 同一时刻只加载一个章节 iframe，滚动到章尾触发新章节加载，无法跨章无缝连续滚动。
  - **后续方向（你的设计，已认可）**：跨章滚动采用“方向感知 + 异步预排”——进章即预排上一章防向上滚，临近章尾/开始下滚时异步预排下一章，滚到下一章开头立即接续；长章用异步 `view.load()` 避免卡滚动。此乃引擎手术（多 view 虚拟拼接），会触碰 CFI/进度映射，需整套回归。
- 进度在书架列表的展示（续读百分比）。

---

## 关键决策录
- 渲染只交给 foliate-js（排版/分页/CFI），解析/定位/持久化/动画/字体分层全在 Kotlin。
- ES Module / fetch 依赖跨域问题 → 用 `WebViewAssetLoader` 走 https 虚拟域，不用 `file://`。
- 定位以「章节 + 章内比例」与 CFI 兼容为主，进度直接来自 foliate `relocate` 上报。
- **书源改存应用私有目录副本**（对齐 Moon+/ReadEra）：不长期依赖 SAF content://。启动授权生命周期在重启后不可靠（vivo 实测 openInputStream 返回 null → 空白屏），故导入即拷贝进 `filesDir/books/`。
- **平板上不要依赖系统 logcat 定位问题**：vivo/OPPO 系统层日志限流不可关闭、AS 日志面板也丢日志。关键调试信息统一走内置 `FileLogger` 落盘文件，命令 `adb shell run-as com.folioepub cat files/logs/sec_*.txt` 读取。