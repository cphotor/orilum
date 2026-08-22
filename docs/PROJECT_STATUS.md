# Orilum — 项目进度

> 本文档记录**阶段进度**（做了什么、做到哪、还差什么）。设计原理与取舍见 [`ARCHITECTURE.md`](ARCHITECTURE.md)。
> 更新规则：完成阶段任务/重大变化/新增待办时追加记录，进度以此文档为唯一溯源。

## 当前阶段：渲染闭环 + 样式系统

> `foliate-js 渲染 + 自建 Kotlin 数据层` 主链路已闭环：SAF 选书 → 解析 → 渲染 → 翻页 → 进度存读 → 目录 → 设置面板 → 字体。
> 当前正推进「样式系统（UI 常驻控件层）」，见下方「样式系统 · 工程进度」。

---

## 已完成

### 数据层（自建 Kotlin）
- **EPUB 解析器**：`EpubParser`（zip → container.xml → OPF/spine/manifest → EPUB3 nav / EPUB2 NCX），产出 `EpubBook`。单测覆盖标准 EPUB3/EPUB2、路径大小写容错、目录关联。
- **定位模型**：`Location`（章节 + 章内偏移）+ `ProgressConverter`（章节↔全书进度互转、越界钳制），纯逻辑可单测。
- **书库层**：`Book` / `BookReadingState`（Room），`BookDao` / `AppDatabase` / `BookRepository`。

### WebView + foliate-js 渲染
- **依赖拉取**：Gradle 任务 `downloadFoliateJs` / `fetchFoliateJs` 构建期从 npm 镜像下载 foliate-js 1.0.1 到 `assets/foliate-js`。
- **示例书**：`sample-epub-src/` 源码 + `makeSampleEpub` 打包成合法 EPUB3 到 `assets/sample/sample.epub`，含一长章节验证多屏分页。
- **渲染管线**：`reader.html` 引入 `<foliate-view flow="paginated">`，fetch 书 → open → init；三区点击翻页；`relocate` 上报；JSBridge `EPUBBridge` 上报 Kotlin。`ReaderActivity` 用 `WebViewAssetLoader` 走 `https://appassets.androidplatform.net/assets/...` 解决 ES Module/fetch 跨域。
- **真机验证**（平板 1840×2800，Android 14）：单栏整页排版、三区翻页、长章多屏推进均可用。

### 阅读闭环（书架 → 阅读）
- **SAF 选书 → 私有化落盘**：`BookImporter` 拷贝 epub 进 `filesDir/books/`，`Book.filePath` 存本地副本（Room v1→v2 迁移 `sourceUri`→`filePath`），脱离 SAF content:// 授权生命周期。
- **`WebViewAssetLoader` 请求拦截修正**：`/book/` handler 的 `path` 无前导斜杠，修正后书籍字节送达 foliate-js，根治「空白屏幕」。
- **内置文件日志**：`FileLogger` 落盘 `filesDir/logs/`，绕开 vivo/OPPO 不可关闭的 logcat 限流。
- **进度存读恢复**：reading_states 新增 `locator` 列（DB v2→v3），JS `EPUBBridge.onLocation` 上报、`EPUBBridge.getSavedLocator` 回传 `init({ lastLocation })` 精确定位；真机验证 `settled fraction == saved fraction`。
- **退出路径修复**：顶部「‹ 书架」按钮（`EPUBBridge.back() → finish()`），系统返回键统一 `finish()`，翻页只走三区点击，根治「恢复前进一页」。
- **章号显示修复**：relocate 改用 `lastLocation.section.current`。
- **四向独立页边距 + 全屏翻页动画（无漂移）**：左右边距折叠进列间 gap（列宽 = `size−l−r`、列 gap = `l+r`、推进 = `size`），根治「增右边距拉入下一页」的漂移；全屏滑动动画（上下边距作每列内边距）。`build.gradle.kts` 与 `reader.html` 排除官方 `paginator.js` 覆盖，保留定制版并 force-add 入版本控制。
- **翻页动画开关 ✅**：设置面板「翻页动画」开关（`settings.pageAnim`），关时松手 `next()/prev()` 即时跳转（缺 `animated` 属性），实现「无翻页动画」；开时平滑滑动。
- **打开应用自动续读 ✅**：MainActivity `maybeContinueLastBook()`——`autoContinue` 开关开启且记录过最后阅读书（`KEY_LAST_BOOK_ID`）时，冷启动自动进入该书阅读器；关或书已删则回书架。

### 字体
- **字体池**：`FontParser`（TTF/OTF/TTC cmap 解析，字节序自适应 + 健康 CJK 码元判据）、`FontClassifier`（中/拉/通/符/无效 + `isUsable` 过滤）、`FontRoom`、`SystemFontScanner`、`FontRepository`（系统缓存 + 指定目录导入）。
- **JS 面板接入**：五分类字体各下钻网格选择；「指定目录导入…」完成 `onFontsChanged` 刷新。
- **选择项按实际字体渲染**：动态注入 `@font-face`，所见即所用。
- **vivo SAF 目录选择无法确认（已修）**：导入前 `leaveImmersive()` 退全屏，SAF 关闭 `onResume` 恢复沉浸式。

### 设置面板与数据层
- **右侧抽屉 + HTML 单页面板 + 多级下钻页面栈**：顶层平铺设置项，可下钻项带 ›，二级页 ‹ 返回/✕ 关闭；与目录抽屉互斥。
- **双套机制**：`ReaderSettingsStore`（默认套常量 + 用户套 `reader.json`，原子写、一键重置）；`EPUBBridge.getSettings/saveSettings/resetSettings` 双向同步。
- **控件交互**：字号/行距「− 滑块 +」；页边距/主题/字体选项选择，改动即注入 `buildReadingCSS` 实时生效；字体五分类。
- **启动加载**：书打开后 `getSettings()` 读取用户设置并应用。
- **已修复**：themePage 引用后定义常量 TDZ 崩溃；字体 `window.prompt` 改成页面栈下钻选择。

### 样式系统工程进度（UI 常驻控件层）
> 设计细节（主题/字号体系/配色原理）见 [`ARCHITECTURE.md`](ARCHITECTURE.md) §6。

- [x] **① 主题 css**：三套 `LAYOUT_THEMES`（原书设置 original / 现代模式 modern / 传统模式 traditional，注入 `buildReadingCSS`）。
- [x] **② 主题选择列表 UI**：设置面板「排版主题」列表单选，切换即 `applySettings(true)` 注入 + `saveSettings` 持久化（`settings.layoutTheme`，默认 `original`）。
- [x] **③ 界面控件常驻层**：字号滑块（根 em）/行距/边距跨主题保持；日夜整套配色 + 单独覆盖背景色。
  - 字号 UI 滑块以正文 px 步进（9–36px，0.1px），拖动换算 `fontScale` 档位存盘；旧套绝对 `fontSize` 自动迁移。
  - 日夜整套配色 `SCHEMES` 同时注入 `html`/`body` 的 `background+color`；`bgOverride` 独立覆盖层；日夜按钮一键切换。
- [ ] **④ 逐条输入（手写选择器）**：左选择题器 × 右选属性 + 输入值，列表管理。
- [ ] **⑤ 替换字体**：并入逐条层，只在选「原书」时生效。
- [ ] **⑥ 废弃迁移**：移除「中英分离 + unicode-range」旧实现，设置数据迁移到新主题体系。
- [ ] **⑦ 竖排（待评估，低优先级）**：`writing-mode: vertical-rl`，涉及竖排 CJK 标点替换、西文横放、em 语义翻转，复杂度较高；方向未定。

---

## 待办

- **页码显示**：计算当前页/总页码显示在工具栏，**暂不实现**，需先定 span/页计数方案。
- **scrolled（滚动）模式**：⏸ 暂搁（代码已移除）。跨章无缝连续滚动需“方向感知 + 异步预排”引擎手术（多 view 虚拟拼接），会触碰 CFI/进度映射，需整套回归。
- **进度在书架列表展示**（续读百分比）。
- **字体控制清理**：系统字体太多太杂（Noto Sans / Vivo Sans 全保留），需定取舍策略。
- **字体中文名真机复核**：`readFamilyName` 已改「优先含中文字符」+ 字节序健康 CJF 判据，单测通过，待真机确认中文名展示。

---

## 关键决策录
- 渲染只交给 foliate-js，解析/定位/持久化/动画/字体分层全在 Kotlin。
- ES Module / fetch 跨域 → `WebViewAssetLoader` 走 https 虚拟域，不用 `file://`。
- 定位以「章节 + 章内偏移/比例」与 CFI 兼容为主，进度直接来自 foliate `relocate`。
- 书源存应用私有目录副本（导入即拷进 `filesDir/books/`），不长期依赖 SAF content://（重启授权不可靠）。
- 平板上不依赖系统 logcat（vivo/OPPO 限流不可关），关键日志走内置 `FileLogger` 落盘。