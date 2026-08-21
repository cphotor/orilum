# Orilum — 项目进度

> 本文档记录阶段进度与关键决策，配合 `docs/ARCHITECTURE-FOLIATE.md` 使用。
> 更新规则：完成阶段任务/重大变化时追加记录。

## 当前阶段：样式系统（UI 控件常驻层）

> 目标：`foliate-js 渲染 + 自建 Kotlin 数据层` 闭环已跑通（渲染/翻页/进度/目录/设置面板/字体）。
> 当前推进「样式系统」工程（见下方「样式系统 · 工程进度」）。历史已完成工作见 `ARCHITECTURE-FOLIATE.md` §8「已完成」清单。

### 已完成

#### 数据层（自建）
- **EPUB 解析器**：纯 Kotlin 实现 `EpubParser`，解析 zip → container.xml → OPF (metadata/manifest/spine) → EPUB3 nav / EPUB2 NCX 目录，产出 `EpubBook` 模型。单元测试覆盖标准 EPUB3/EPUB2、路径大小写容错、目录关联场景。
- **定位模型**：`Location`（章节 + 章内偏移）+ `ProgressConverter`（章节↔全书进度互转、越界钳制），纯逻辑、可单测。
- **书库层**：`Book` / `BookReadingState`（Room），`BookDao` / `AppDatabase` / `BookRepository` 封装存取。

#### WebView + foliate-js 渲染（本次完成）
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

### 进行中（闭环已就绪）
- **书架闭环 ✅**：SAF 选书 → 自建 `EpubParser` 解析 → 交 foliate-js 渲染。
  - **SAF 选书 → 私有化落盘 ✅**：`BookImporter` 导入时把 epub 拷贝进 `filesDir/books/`，`Book.filePath` 存本地副本路径（Room v1→v2 迁移 `sourceUri`→`filePath`，清理失效旧行）。书一经导入即应用持有，彻底脱离 SAF content:// 授权生命周期（真机验证重启后仍可读）。
  - **`WebViewAssetLoader` 请求拦截修正 ✅**：`/book/` handler 收到的 `path` 无前导斜杠（`current.epub`），修正判断后书籍字节成功送达 foliate-js，「空白屏幕」根治。assets 静态资源经 https 虚拟域正常加载。
- **内置文件日志模块 ✅**：`FileLogger` 落盘 `filesDir/logs/`，仅 DEBUG 构建启用，按天分文件、超 1MB 轮转、单条 ≤4KB；覆盖 WebView 请求拦截/错误、foliate-js `EPUBBridge.log`、console。绕开 vivo/OPPO 平板不可关闭的系统 logcat 限流（`run-as` 可读）。
- **进度存读恢复 ✅**：reading_states 新增 `locator` 列（foliate `lastLocation` JSON，DB v2→v3 迁移）。每次 relocate 由 JS `EPUBBridge.onLocation` 上报整份 locator 落库（`chapter`=section.current、`progress`=全书 fraction 供轻量展示）；重开时 ReaderActivity 同步预载 locator，经 `EPUBBridge.getSavedLocator` 回传 `init({ lastLocation })` 精确定位。真机验证 `settled fraction == saved fraction`（如 0.0332=0.0332）精确恢复。
  - **退出路径修复 ✅**：原「恢复前进一页」根因是无返回按钮、系统手势返回被吞成翻页污染了保存值。新增顶部「‹ 书架」按钮（`EPUBBridge.back() → finish()`），`#btn-back` 单独 `pointer-events:auto`（`#bars` 为穿透）；系统返回键统一为 `finish()` 退出，翻页只走三区点击。
- **章号显示修复 ✅**：relocate 改用 `lastLocation.section.current`（此前取 `e.detail.index` 为 undefined）。

### 待办
- ~~一屏一页翻页：左右点击已可用，滑动翻页默认开启~~ ✅ **滑动翻页完成**：走 foliate 原生两页分栏滚动——拖动 `renderer.scrollBy` 实时露出相邻列（两页同屏），松手 `next()/prev()` 提交、`animated` 属性平滑滑动；三区点击翻页保留，滑动方向已修正（左滑下一页/右滑上一页）。
- **翻页动画开关 ✅记TODO**：滑动是翻页动画之一，需要一个后台开关控制「滑动/无」动画，**暂不实现**，先默认滑动 <code>reader.html</code> 内 TODO(settings)。
- **打开应用自动打开当前书 ✅记TODO**：需要一个「启动时自动打开上次阅读的书」开关，**暂不实施**（当前仍从书架点开）。
- ~~目录导航（TOC 面板）。~~ ✅ **已完成**：目录树渲染（多级/缩进）、点击跳转 `goTo`、当前章高亮、遮罩/✕ 关闭。
- **页码显示 ✅记TODO**：分栏 paginated 模式下计算当前页/总页码并显示在工具栏中，**暂不实现**（推进至后续），需先确定 span/页计数方案。
- **设置面板 ✅（已完成）**：右侧抽屉 + HTML 单页面板 + 多级下钻页面栈。底部工具行 ⚙ 打开，与目录抽屉互斥；顶层平铺所有设置项（字号/行距/页边距/字体/阅读背景 + 翻页动画/自动续读/页码开关），可下钻项带 ›、二级页 ‹ 返回/✕ 关闭；内容超高时列表内滚动。现有设置项均暂全局生效，全局/按书作用域区分**待定**。
  - **数据层接通 ✅**：`ReaderSettings`/`ReaderSettingsStore` 双套机制（默认套内置常量 + 用户套 `filesDir/settings/reader.json`，原子写、一键重置删用户套回默认套）；`EPUBBridge.getSettings/saveSettings/resetSettings` 实现 JS↔Kotlin 双向同步。
  - **控件交互 ✅**：字号/行距用「− 滑块 +」细则；页边距/主题/字体用选项（chip/网格）选择，改动即注入 `renderer.setStyles(buildReadingCSS())` 实时生效并回写持久化；字体五分类（正/题/码/粗/斜）各下钻独立选择页（内置宋/黑/楷/苹方/等线 + 跟随原书，`跟随原书样式` 总开关下不注入字号行距字体）。
  - **启动加载 ✅**：书打开后从后端 `getSettings()` 读取已存用户设置并应用，重开保留（此前只改不回读）。
  - **已修复**：`themePage` 构建期引用后定义常量导致模块加载 TDZ 崩溃；字体原用 `window.prompt` 且行内 `onStep` 从不触发导致选不了——改为页面栈下钻选择。
- ~~scrolled（滚动）模式切换。~~ ⏸ **暂搁（代码已移除）**：曾用 foliate 原生 `flow="scrolled"` 实现，存在两个根因问题，推到后续专项解决：
  - **行宽/折行差异（存疑）**：切换翻页↔滚动时个别章节折行略微变化。初步怀疑两模式正文区宽/边距取法不同（翻页 `columnize` 动态列宽 vs 滚动 `body max-width:720px`）。我两次估算差异几十~百像素，但你实机观感仅几个像素——结论是**必须加诊断日志取真实 columnWidth/渲染宽度，禁止再盲猜**。
  - **跨章滚动受限（根因确凿）**：foliate-paginator 同一时刻只加载一个章节 iframe，滚动到章尾触发新章节加载，无法跨章无缝连续滚动。
  - **后续方向（你的设计，已认可）**：跨章滚动采用“方向感知 + 异步预排”——进章即预排上一章防向上滚，临近章尾/开始下滚时异步预排下一章，滚到下一章开头立即接续；长章用异步 `view.load()` 避免卡滚动。此乃引擎手术（多 view 虚拟拼接），会触碰 CFI/进度映射，需整套回归。
- 进度在书架列表的展示（续读百分比）。

### 字体功能（进行中）
- **字体池 / 解析 / 分类 ✅（后端完成）**：
  - `FontParser`（纯 Kotlin）解析 TTF/OTF/TTC 的 cmap(name 表) → 提取家族名 + CJK(U+4E00–9FFF)/拉丁(U+0000–02FF)覆盖权重；字节序自适应（BE/LE），新增「健康 CJK 码元数」判据区分正确字节序与倒序乱码。
  - `FontClassifier` 按覆盖阈值归为 中文/拉丁/通用/符号/无效，`isUsable` 过滤杂字体（符号/低覆盖不入池）。
  - `FontRoom` 表 + DB 迁移；`SystemFontScanner` 扫系统字体；`FontRepository` 系统字体缓存入库 + 用户指定目录导入（`DocumentFile` 遍历 → 拷贝私有化进 `filesDir/fonts/` → 解析分类入库）。
  - `ReaderActivity` 暴露 `listFonts()` + `EPUBBridge.pickFontDirectory()`；WebView `/fonts/` handler 提供导入字体字节流。
- **JS 设置面板接入 ✅**：`reader.html` `listFonts()` 动态拉取设备真实字体 → 字体五分类（正/题/码/粗/斜）各下钻网格选择页；「指定目录导入…」入口，导入完成 `onFontsChanged` 回调刷新。
- **字体选择列表按实际字体效果渲染 ✅（已确认 + 已实现）**：字体选择页每行用**所选字体自身的字形**渲染该字体名称（动态注入临时 `@font-face` + `font-family`），用户所见即所用，无需凭名字脑补长相；「跟随原书」用默认族渲染区分。此交互在新样式系统中保留。
- **vivo SAF 目录选择无法确认 ✅（已修）**：根因是阅读器沉浸式全屏（隐藏导航栏）宿主下，vivo DocumentsUI 误算 insets，把「选择此文件夹」按钮压到屏幕外。修复：`pickFontDirectory` 前 `leaveImmersive()` 退出全屏，SAF 关闭 `onResume` 里 `reapplyImmersive()` 恢复。真机已验证可正常导入。
- **中英文字体分离开关 ✅（本次完成）**：iTerm2 式开关。字体二级页新增「中英文字体分离」开关（`useCjkFont`），默认关闭（保持现状：每分类一个字体运用于全部字符）；开启后每个分类（正/题/码/粗/斜）各自可**分别指定中文字体（CJK）与英文字体（主字体）**，选择页拆成中文/英文两段独立多选，顶层显示 `英:A 中:B`。
  - 实现：`defaultSettings` 增 `useCjkFont` + `fontBodyCjk` 等五分类中文字段；`buildReadingCSS` 用 `unicode-range`（CJK 区段 U+3000–FFEF）叠加——中文字体 `@font-face` 仅声明 CJK 区段，西文字符自动回退主字体。settings 为 JSON 透传持久化，无需改动 Kotlin。
  - 架构人机交互对比（iTerm2 开关式 vs VS Code 字体列表栈式）：选 iTerm2 式——低门槛、贴合阅读场景；VS Code 手输字体列表有专业门槛且字体名填错会静默失效。UI 上每分类两段多选，清单只在分类选择页内拆段、总页仅多一个开关行，避免清单过长。
  - ⚠️ **已被取代**：此「中英分离 + 五分类双字体 + unicode-range」方案在 2026-08-21 的架构收敛中被整体取代，见下方「样式系统设计（已确认 · 待实现）」。后续实现新系统时将弃用本条目（砍中英分离，改主题列表 + 逐条输入）。保留在此仅作历史记录。
- **待办（明天）**：
  - 字体中文名显示为英文（霞鹜文楷→LXGW WenKai）：`readFamilyName` 已改为「优先含中文字符的记录」+ 字节序用健康 CJK 判据，**代码已改 + 单测已跑通**（`FontParserTest` 6 用例全绿），仍需真机确认中文名展示（此前 `Source Han Serif` 被判成乱码，判据已改，待机型复核）。
  - 系统字体太多太杂（Noto Sans / Vivo Sans 全保留）：`isUsable` 只按覆盖量过滤，中文候选里混入大量拉丁/通用系统字体。需定取舍策略（是否保留系统通用字体 / 按语言分组折叠 / 优先展示中文字体）。

---

## 关键决策录
- 渲染只交给 foliate-js（排版/分页/CFI），解析/定位/持久化/动画/字体分层全在 Kotlin。
- ES Module / fetch 依赖跨域问题 → 用 `WebViewAssetLoader` 走 https 虚拟域，不用 `file://`。
- 定位以「章节 + 章内比例」与 CFI 兼容为主，进度直接来自 foliate `relocate` 上报。
- **书源改存应用私有目录副本**（对齐 Moon+/ReadEra）：不长期依赖 SAF content://。启动授权生命周期在重启后不可靠（vivo 实测 openInputStream 返回 null → 空白屏），故导入即拷贝进 `filesDir/books/`。
- **平板上不要依赖系统 logcat 定位问题**：vivo/OPPO 系统层日志限流不可关闭、AS 日志面板也丢日志。关键调试信息统一走内置 `FileLogger` 落盘文件，命令 `adb shell run-as com.orilum cat files/logs/sec_*.txt` 读取。

---

## 样式系统（已确认 · 待实现）
> 架构收敛结论。取代当前「中英分离 + 五分类双字体 + unicode-range」方案。设计完整描述见下方「设计细则」。

### 工程进度（按实现顺序）
- [x] **① 编写主题 css ✅（已确认 + 已实现）**：三套主题已写入 `reader.html` 的 `LAYOUT_THEMES`（注入 `buildReadingCSS`，位于字体规则之前、作为默认样式层）：
  - `原书设置`（original）：不注入排版规则，仅缺字体兜底。
  - `现代模式`（modern）：无首行缩进，段落靠段间距区隔。
  - `传统模式`（traditional）：首行缩进两字符，无段间距。**正文采用衬线字体（已确认并落地，`body, p { font-family: serif; }`）**。
  - 主题用列表单选，原书也作为一个选项（见②）。预留模板扩展位（段首放大等）。
- [x] **② 主题选择列表 UI ✅（已实现 + 真机验证）**：设置面板「排版主题」列表单选（现代/传统/原书），每行一个、内容居中、选中高亮。切换即 `applySettings(true)` 注入对应主题 css（`buildReadingCSS` 内置于字体规则之前作默认层）实时生效 + `saveSettings` 持久化（`settings.layoutTheme`，默认 `original`）。已真机验证三类主题切换/往返持久化正常。
- [ ] **③ 界面控件常驻层（下一步工作）**：字号滑块(根 em)/行距/边距，跨主题保持；日夜整套配色切换 + 单独覆盖背景色。
- [ ] **④ 逐条输入（手写选择器）**：左选择题器(h1/p/code/strong/em…) × 右选属性(font-family/line-height…) + 输入值，列表管理，后者覆盖前者；`font-family` 特判为字体选择器。
- [ ] **⑤ 替换字体**：并入逐条层（`原书字体名→可用字体` 特例），只在选「原书」时生效。
- [ ] **⑥ 废弃迁移**：移除「中英分离 + unicode-range + 五分类双字体」旧实现，设置数据迁移到新主题体系。
- [ ] **⑦ 竖排（待评估，低优先级）**：仅古籍/竖排场景使用。核心是 `writing-mode: vertical-rl`，覆盖即可触发，但因涉及**竖排 CJK 标点符号替换**（书名号/引号/破折号等需切换竖排专用字形）、西文/数字横放（`text-combine-upright` / `text-orientation: upright`）、行宽/页边距等横向 `em` 语义翻转，复杂度偏高。定位为**一个可选的排版主题**，与「传统模式」自然融合；方向未定（做成传统模式的可选增强开关，还是独立主题），后续再评估实现范围，暂不展开。

### 优先级（实现时遵循，高 → 低）
```
界面控件     字号滑块 / 行距 / 边距 / 日夜切换 —— 随时可调，最高
逐条输入     手写选择器单条 h1{…} p{…}
css模板      现代 / 传统 /（模板 css）
原书设置     “不覆盖”层，选它 = 完全保留原书
```
- css 主题模板与原书重叠的样式 → 以模板为准；想完全按原书就选“原书设置”作模板。
- 替换字体并入「逐条输入」层（本质逐条特例：`原书字体名→可用字体`，只在选“原书”时才有意义）。

---

### 设计细则

#### 核心原则：复杂度按场景分级（只给需要的人）
三场景对应三档设置密度，互斥入口，而非一套机制通吃所有书：
1. **原书设置**：书内 CSS 生效。字体靠内置三款兜底（衬线/非衬线/等宽），缺字体才替换。
2. **App 统一呈现**：忽略书内 CSS，App 默认排版。零配置。
3. **用户指定样式**：主动改版式者进入，设置极致简洁。

三场景统一用「列表选择」表达——**原书设置也作为一个主题选项**，不用开关，全部走列表单选。

#### 整体样式控制（三层 + 背景）
- **场景一“原书设置”**：App 内置**衬线、非衬线、等宽**三种替换字体，做缺字体兜底。
- **场景二“排版主题”**（列表单选）：
  - 现代模式：无首行缩进，段落靠段间距区隔。
  - 传统模式：首行缩进两字符，无段间距。
  - 其他模式：段首字符放大等，后期由模板 css / 用户自定义 css 补充。
- **场景三“用户自定”**（不用开关，列表管理）：
  - **替换字体**（次高优先）：逐个字体选择替换字体。
  - **手写选择器**（最高优先）：左右两个列表框（选择器 h1/p/code/strong/em… × 属性 line-height/font-family…）+ 输入框填样式值，逐条追加成列表，可单行删除，后者覆盖前者。

#### 字号体系（相对默认缩放，已确认）
- **根锚=正文**：UI 字号滑块设正文字号（根 em），全书字号 = 正文 × 各元素自身 em 比例，根一变化 → 整体等比缩放、不打架。
- **默认比例表**：如 h1≈1.5em、h2≈1.3、p=1em、code≈0.9…（可微调）。
- **“单独改某元素尺寸” = 改该元素的 em 比例值**，不存固定 px。手写选择器属性选 `font-size` 时输入 `em` 相对值；存进列表如 `h2 { font-size:1.3em }`，自动跟随根字号。
- **UI 归一为 0–100 滑块，映射到有限区间 0.5em–2em**：
  - `em = 0.5 + (value/100) × 1.5`；反求 `value = (em−0.5)/1.5 × 100`。
  - **采用“相对默认”语义**：50 档 = 该元素自身默认值（h1 仍约 1.5em、code 仍约 0.9em），滑块是相对默认的缩放系数，不会改丢默认基准。
  - 不用“绝对区间”语义（那会让 code 默认被压到 1.0）。

#### 背景色：深浅成对
- 日夜切换 = **整套配色**（前景文字色 ⇄ 背景色 ⇄ 强调色 ⇄ 容器阴影）成对定义。
- 允许用户**单独覆盖背景色**这一项，再一键在两个整套配色间切换。
- 注意：只切背景不切文字会“夜黑字黑”，故日夜必须是整套双主题。