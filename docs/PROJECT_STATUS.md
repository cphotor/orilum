# Orilum — 项目进度

> 本文档记录**阶段进度**（做了什么、做到哪、还差什么）。设计原理与取舍见 [`ARCHITECTURE.md`](ARCHITECTURE.md)。
> 更新规则：完成阶段任务/重大变化/新增待办时追加记录，进度以此文档为唯一溯源。

## 当前阶段：渲染闭环 + 样式系统

> `foliate-js 渲染 + 自建 Kotlin 数据层` 主链路已闭环：SAF 选书 → 解析 → 渲染 → 翻页 → 进度存读 → 目录 → 设置面板 → 字体。
> 当前正推进「样式系统（UI 常驻控件层）」，见下方「样式系统 · 工程进度」。

## 0.3 阶段功能批次（v0.3 排版引擎定案后 · 实施中）

> 排版引擎三窗口缓存定案（v0.3）后，集中实现此前规划的功能。**每项独立分支，逐个合并回 main，
> 每完成一项语义化版本末尾 +1（如 v0.3.1、v0.3.2…）。** 顺序不预设固定编号，按下述清单推进。

- ~~**字体替换**~~ **✅ 已完成（v0.3.1）**：正文/标题/代码「三档元素类型」替换，取代旧五分类。扫描书内 `font-family` 声明按元素类型归槽、注入 `!important` 全局强制，空档（「跟随原书」）保留原书。（对应样式系统 ⑤）
- **书架双视图 / 编辑 / 排序**：书架封面/列表双视图切换（现仅列表），底部工具已有切换按钮但内容区未实现格子大图；编辑（删除已有 / 重命名 / 分组）与排序（书名 / 添加时间 / 进度）。
- **样式层叠与排版主题完善**：逐条手写选择器（选择器 × 属性 × 值列表，可单行删除，覆盖优先级最高）；废弃迁移（移除「中英分离 + unicode-range」旧实现，数据迁移到新主题体系）。
- **排版距离设置统一面板**：将页边距 / 行距 / 段间距 / 字号等排版间距设置合并到同一面板（现有部分已入抽屉，进一步收口）。
- **排版设置时当前页面顶部内容稳定**：调节字号 / 行距 / 边距等排版参数时，保持视口「当前页面顶部」内容不跳动（涉及 paginator 锚定，改动敏感）。
- **卷页翻页动画**：实现卷页（curl）翻页动画（当前仅 `pageAnim` 开关 + `#scrollTo` 缓动；卷页为预留方向，需同架构动画层）。
- **书架续读百分比展示**：书架列表按 `BookReadingState.progress` 显示续读%。
- **页码显示**：工具栏显示当前页 / 总页（先定 span/页计数方案）。

### 语义化版本记录（0.3 阶段内按完成顺序递增）
- `v0.3`：排版引擎方案定案——三窗口缓存（已发布）。
- `v0.3.1`：字体替换简化——正文/标题/代码三档元素类型替换（取代旧五分类）。
- （后续每次合并补齐，如 `v0.3.2`、`v0.3.3`…）

## 当前渲染模型 · 三窗口缓存（最终方案）

> foliate-js 是最新定案：**长条只保留「当前章 ± 相邻 1 章」共 ≤3 章**（三窗口缓存），
> 分栏完成即装配进 flex 长条，翻页用 scrollLeft + snap，跨章走 `#goToEdge` 换 primary 并重新收敛窗口。
> 取代此前反复失败的全书长带 / 页数缓冲窗口方案（见下方「演进里程碑」）。

- **三窗口判定 = 纯章序差**：`#isWithinBuffer(index)` 仅判断 `|index - primary| ≤ 1`，**不依赖任何章的 contentPages**。
  - 这是根治此前"跨章回跳一页 / 预排↔销毁震荡"的关键：因为判定输入与排版无关，**预排和销毁对同一章永远给出同一结论**，
    不再出现"排前估1页=界内、排后真实N页=界外"的翻转。
- **`#stripOrder` 章序索引**：长条内实际顺序的章 index 数组。`#getViewOffset / #getPagesBeforeView / #renderedViewSize /
  #detectPrimaryView / #sortedViews` 全部基于它，offset = 前缀 `Σ contentPages × size` 推导，**不实测 `getBoundingClientRect`**，
  确定性、不随布局时序漂移。`#buildView`/`#destroyView` 负责维护它的插入/移除。
- **预排**：`#tickIdlePreload` → `#popNearestPrep` 只取 `#isWithinBuffer` 内的未排章（即 primary±1），近到远，排完即停。
  `prepState[]` 记录全书每章处理状态（0未排/2已排），`#destroyView` 销毁时统一置 0，保证窗口内章总能被补回、不产生空洞。
- **销毁（收敛窗口）**：`#trimDistantViews` 从远到近销毁所有 `|i-primary|>1` 的章；左侧销毁用
  `scroll = preScroll - leftTotal`（`leftTotal` 由 contentPages×size 精确算）保持视口，避免 clamp 竞态。
- **翻页**：拖动跟手 `scrollBy`、松手 `snap`（基于拖动前静止基准 `#scrollBounds[0]`，一次手势至多翻一页）；
  章末越界 → `#goToEdge` 加载邻章换 primary → trim 收敛到新三窗口。
- **跨章回跳一页已根除**：锚定在「判定纯章序差 + offset 确定性前缀和」，不再有页数未知导致的基准错位。
- **真机验证**：连续跨章前/后翻、目录跳转、回翻已销毁历史章，均稳定、无回跳、无空洞。

### 演进里程碑（历史方案，均已取代，仅作决策留痕）
- **parked 屏外驻留** → 因 `position:fixed;left:-9999px` 合成层积压导致"跳两页间卡死"，弃。
- **全书长带（永久驻留全章节）** → 不用 parked、整本拼入长条，稳定但长条越长排版越慢（调字号整本重排 10 分钟），弃。
- **页数缓冲窗口（前后各10页，预排+销毁）** → 因"该章未排页数未知"使预排/销毁对同一章节判定翻转 → 排↔销震荡 + 跨章回跳，弃。
- **三窗口缓存（当前）** → 判定纯章序差、offset 确定，稳定收敛，为最终采用。

## 修复 · 长距离滑动翻两页 + 跟手（双翻与跟手共存方案）

> 现象：长距离滑动一次翻两页，同时跟手预览丢失、拖动不实时。
> 根因：reader.html 与 paginator.js 双方都在处理同一组 touch 事件，导致双重处理；
> 修复为「**单一处理源**」——跟手 scrollBy 与 snap 都由 reader 全权驱动，paginator 自身 touch 处理器短路。

### 根因

1. **双 scrollBy 互相干扰**：reader 与 paginator 各自对同一手指位移调用 scrollBy，滚动距离翻倍、跟手错乱。
2. **双 snap / next/prev 双翻**：touchend 时若 reader 调 `next()/prev()/snap()`、paginator `#onTouchEnd` 又调 `snap()`，各翻一次 → 翻两页。
3. **snap 叠加位移**：若 snap 基于「当前滚动位置 + 速度」计算目标页，touchmove 期间 scrollBy 已推进到预览页，再叠加速度会把预览已推进的一页又算一次 → 翻两页。

### 修复方案（单一处理源）

**reader.html**（`touchstart` / `touchmove` / `touchend` / `touchcancel`）：
- `touchstart`：置 `renderer._readerDrag = true`（握手标记，本次触摸由 reader 全权处理）。
- `touchmove`：横向滑动时调用 `r.scrollBy(-(x - touch.dx), 0)` **实时跟手**露出相邻页（滑动中两页同屏）。
- `touchend`：只调 `r.snap(-vx, -vy)`，重用 paginator 的统一 snap 逻辑；**不再调用 `r.next()/r.prev()`**（强制翻一页会与 snap 叠加 → 双翻）。
- `touchcancel`：若触摸被打断，同样复位 `_readerDrag`。
- 亮度手势仍留在 reader.html 单独处理（纵向判定，与横向翻页正交）。

**paginator.js**（`#onTouchStart` / `#onTouchMove` / `#onTouchEnd`）：
- 三者顶部都 `if (this._readerDrag) return`。reader 接管本次触摸时，paginator 完全短路，
  不再 scrollBy / 不再 snap，避免双处理 → 无双 scrollBy(距离翻倍)、无双 snap(翻两页)。

**paginator.js**（`snap()`）：
- 目标页基于**拖动前的静止基准 `#scrollBounds[0]`**，不是当前位置。
- 拖动距离超过半屏 → 按位移方向翻一页；否则按速度方向翻一页（或回弹当前页）。
- 目标 = `Math.round(rest0 / size) + dir`，一次手势至多翻一页，不叠加位移。

> 实现提交：`c25146e`（`fix(reader): restore finger-following drag with single-owner touch handling`）；
> 双翻与跟手的判定在后续三窗口重组 `b19081c` 中保持有效。另参考 `6390a7c`（早期 prevent double page turn 思路）。

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
- **JS 面板接入**：三档元素类型替换（正文/标题/代码）各下钻网格选择；「指定目录导入…」完成 `onFontsChanged` 刷新。
- **选择项按实际字体渲染**：动态注入 `@font-face`，所见即所用。
- **字体替换机制**：扫描书内样式表、只收 `font-family` 声明，按 `slotOfSelector`（代码 > 标题 > 正文）归槽后注入 `!important`；正文档跳过祖先/容器泄漏源（`body/main/article/.content` 等），避免继承把正文样式扩散到标题；`@font-face` 用 `location.origin` 绝对 URL，规避 foliate 内容 iframe 的 blob baseURI 导致字体加载失败。
- **vivo SAF 目录选择无法确认（已修）**：导入前 `leaveImmersive()` 退全屏，SAF 关闭 `onResume` 恢复沉浸式。

### 设置面板与数据层
- **右侧抽屉 + HTML 单页面板 + 多级下钻页面栈**：顶层平铺设置项，可下钻项带 ›，二级页 ‹ 返回/✕ 关闭；与目录抽屉互斥。
- **双套机制**：`ReaderSettingsStore`（默认套常量 + 用户套 `reader.json`，原子写、一键重置）；`EPUBBridge.getSettings/saveSettings/resetSettings` 双向同步。
- **控件交互**：字号/行距「− 滑块 +」；页边距/主题/字体选项选择，改动即注入 `buildReadingCSS` 实时生效；字体三档。
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
- [x] **⑤ 替换字体**：三档元素类型替换（正文/标题/代码）。扫描书内 `font-family` 声明按元素类型归槽、注入 `!important` 全局强制；空档（「跟随原书」）保留原书。跳过 `body/main/article/.content` 等祖先容器选择器，防 `font-family` 继承把正文字体扩散到标题。
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
- 渲染与翻页动画交给 foliate-js/reader（JS 侧：`#scrollTo` 的 rAF 缓动、reader 的跟手 scrollBy）；解析/定位/持久化/字体解析分层在 Kotlin。
- ES Module / fetch 跨域 → `WebViewAssetLoader` 走 https 虚拟域，不用 `file://`。
- 定位以「章节 + 章内偏移/比例」与 CFI 兼容为主，进度直接来自 foliate `relocate`。
- 书源存应用私有目录副本（导入即拷进 `filesDir/books/`），不长期依赖 SAF content://（重启授权不可靠）。
- 平板上不依赖系统 logcat（vivo/OPPO 限流不可关），关键日志走内置 `FileLogger` 落盘。