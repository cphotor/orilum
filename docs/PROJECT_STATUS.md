# Orilum — 项目进度

> 本文档记录**阶段进度**（做了什么、做到哪、还差什么）。设计原理与取舍见 [`ARCHITECTURE.md`](ARCHITECTURE.md)。
> 更新规则：完成阶段任务/重大变化/新增待办时追加记录，进度以此文档为唯一溯源。

## 当前阶段：渲染闭环 + 样式系统

> `foliate-js 渲染 + 自建 Kotlin 数据层` 主链路已闭环：SAF 选书 → 解析 → 渲染 → 翻页 → 进度存读 → 目录 → 设置面板 → 字体。
> 当前正推进「样式系统（UI 常驻控件层）」，见下方「样式系统 · 工程进度」。

## 近期更新 · 设置存储分层（设计基线）

> 确立「公共（全局） vs 书籍（私有）」设置分层模型，作为后续实现与功能扩展的设计基线（**本次仅落文档，未改代码**；设置项仍在演进中，过早建模易返工）。

- **读取语义（合并）**：某设置项有效值 = 该书有私有覆盖 → 取该书私有值；否则 → 取全局记忆值。
- **写入语义（逐项回写）**：书内修改某项 → 同时写该书私有副本 + 逐项回写全局记忆该单项（不整份覆盖，避免"最后操作者冲掉其他项"）。
- **新书初始**：不复制全量设置，仅当某被修改时才写该书私有副本；未覆盖项一律吃全局记忆 → 实现"随书漂移、新书沿用"的传染式默认。
- **归属分类**：环境/界面配色/字体资产/阅读习惯 → 纯全局限全局；排版/字体替换映射/阅读主题/翻页动画 → 全局记忆 + 每书私有、逐项回写。
- **存储差距**：`ReaderSettings` 目前仅是全局全量单对象（`filesDir/settings/reader.json`），书籍私有维度未建立（`BookReadingState` 只存进度）；分层方案即补齐"每书私有副本 + 全局记忆逐项回写"两条链路。
- 本方案已详录于 [`ARCHITECTURE.md §6.4 设置存储分层`](ARCHITECTURE.md)。

---

## 近期更新 · CI 自动构建（签名 Release）

> 打通 GitHub Actions 云端编译，打 `v*` tag 自动产出已签名 APK 并挂到 Release。

- **release 签名注入**：`app/build.gradle.kts` 新增 `signingConfigs.release`，从环境变量 `KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD` 读取签名材料；本地未注入时回退 debug 签名，避免本地 release 构建失败。
- **密钥安全**：jks 以 base64 存入 GitHub Secrets（`KEYSTORE_B64`），workflow 运行时解码出临时 jks（仅存云端 `runner.temp`），构建结束虚拟机销毁即消失，仓库与 Release 均不残留密钥文件。`.gitignore` 已忽略 `*.jks`。
- **工作流**：`.github/workflows/build-release.yml` —— `push v*` tag 或 `workflow_dispatch` 触发；`setup-java 17` → 解码密钥 → `assembleRelease` → 上传 APK（artifact + tag 时挂 Release）。

---

## 近期更新 · 沉浸式迁移到 WindowInsets

> 把已 deprecated 的 `SYSTEM_UI_FLAG_*` 系统栏显隐迁移为 `WindowInsetsControllerCompat`（minSdk 23 兼容），消除 targetSdk 35 上的弃用告警，规避未来 Android 版本收紧。

- **四类系统栏操作统一封装**：`enterImmersive()`（隐藏状态栏+导航栏）、`showStatusBarOnly()`（工具栏弹出，仅显示状态栏）、`exitImmersive()`（临时退出全屏，如 SAF 目录选择）。
- **边到边**：onCreate 改用 `WindowCompat.setDecorFitsSystemWindows(window, false)`，内容全屏铺满、系统栏透明叠加。
- **保留项**：`statusBarColor`/`navigationBarColor` 的深灰融合逻辑暂留（实现工具栏与状态栏同色一体；Android 15 强制边到边下会被忽略，旧版本仍生效）。
- **真机验证**：vivo Pad Air（v 栈 API 15）批量回归通过——沉浸式默认、工具栏显隐联动、最近任务预览无杂色条、SAF 目录选择正常。

---

> 针对水平分页的多 View 拼接引擎做了一次系统性修正，解决「章间来回翻出现空白页」与「打开书首屏左边距偏小」两类问题。

- **跨章翻页空白（章间 2 屏缝隙）**：`Paginator.#layoutViews` 的负重叠拼接公式把 overlap 少算了一步，导致相邻章节之间恒留 2 屏空白缓冲列——「从章首回翻」必然先看到连续两个空白页才见正文。修正放置公式 `left_{i+1} = left_i + w_i − overlap`，让左右缓冲真正折叠、各章正文列首尾相接。附带入栈即钳位 `Math.max(rect, 3size)`，避免新插入 View 未排版时按 0 宽参与偏移累加。
- **打开书首屏左边距被吃**：恢复/深链用 CFI 定位时，`#scrollToRect` 直接以「文本精确左侧」（已含左内边距）为屏幕基准，把左边距挤出屏幕；翻一页后按列起点定位才恢复正常。修正为把锚点**吸附到其所在列的起点**（`col = floor((docX − left)/size)`），与翻页定位一致，首屏即保留左边距。
- **小幅回翻误判**：`reader.html` touchend 的 `snap()` 速度取反，避免小幅回滑被当成向前翻。
- **现场排查**：WebView console 日志同时镜像到 logcat（原先仅写 `FileLogger` 文件），便于 adb 即时定位。

---

## 近期更新 · 封面整屏与翻页边界稳健

> 针对封面显示与三窗口两端边界做系统性修复，并统一翻页触发为单一阈值。

- **封面整屏**：`Paginator.isCoverLike` 识别「首页一张大图/矢量封面为主」的节为封面（兼容 `<img>` 与 `<svg><image>`），命中后取消该页四向边距并置零 body 边距，令封面图铺满整屏。
- **svg 封面不裁顶/裁右**：许多 svg 封面的 `viewBox` 比内嵌 `<image>` 小（出版社裁切封皮），直接把 `viewBox` 改写为 `<image>` 全图尺寸、保持 `preserveAspectRatio=none`，完整封面拉伸填满，不再漏画右/下内容。
- **封面左右白边**：封面章节把 `#top` 网格列改写为整排宽（`0 0 1fr 0 0`），`#container` 撑满整屏、去左右白边。
- **封面切换闪烁**：封面 view 一进窗口就让网格保持整宽（按 window 内是否有封面色判定），避免「先窄栏渲染再拉宽」的白边闪烁。
- **首章无前导空白缓冲**：全书首章元素整体左移 1 屏（内容落 `scroll≈0`），打开书/回翻到书首不再先见空白或“多翻一页才能见封面”。
- **尺寸漂移修复（封面横切/半屏错位同根）**：`View.expand()` 原来只在 `pageCount` 变化时重设 iframe/element 尺寸；当分页步长 `size` 变化而 `pageCount` 未变时，iframe 停在旧宽、内容按新尺寸溢出旧裁剪区 → 右侧被裁 / 章末半屏。现以记录 `#sizedFor` 检测「size 变了但 pageCount 没变」，一并重设；并把 `#viewWidth` 改为派生值（列数×屏宽+两屏缓冲）而非实测 `getBoundingClientRect`，消除亚像素漂移。
- **单一滑动阈值 + rest 基准翻页边界**：翻页触发统一为一个固定像素阈值——未过阈值回弹当前页、超过则动画按方向继续到相邻页；`snap()` 只回弹，不再做重复的半页/方向判定（原“防连续翻页”的 `dir/THRESH` 复判随之移除）。`#scrollNext/#scrollPrev` 的边界早退改按「起手指前静止页 `rest`」判定，根治「过半屏滑到末页卡在中间」（此前预览把 `this.page` 顶到末页令 `atEnd`/`atStart` 误判、吞掉补足动画）。
- **末页尾随空白**：末章右侧 1 屏尾随空白缓冲不纳入可滚范围，书末不再出现“翻过头进空白页”。
- **现场取证**：一次性诊断桥 `EPUBBridge.diagnosticPaginationInfo`（Kotlin `Log.i("DIAG_PAGINATOR")`，单次输出规避 OriginOS 限流）定位翻页边界；排查后已移除相关诊断代码。

---

## 已完成

### 数据层（自建 Kotlin）
- **EPUB 解析器**：`EpubParser`（zip → container.xml → OPF/spine/manifest → EPUB3 nav / EPUB2 NCX），产出 `EpubBook`。单测覆盖标准 EPUB3/EPUB2、路径大小写容错、目录关联。
- **定位模型**：`Location`（章节 + 章内偏移）+ `ProgressConverter`（章节↔全书进度互转、越界钳制），纯逻辑可单测。
- **书库层**：`Book` / `BookReadingState`（Room），`BookDao` / `AppDatabase` / `BookRepository`。

### WebView + foliate-js 渲染
- **依赖拉取**：Gradle 任务 `downloadFoliateJs` / `fetchFoliateJs` 构建期从 npm 镜像下载 foliate-js 1.0.1 到 `assets/foliate-js`。
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