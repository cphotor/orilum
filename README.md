# Orilum

> 注：Orilum 自身代码的授权尚未确定，除下述第三方组件外不提供任何使用/分发许可。

Orilum 是一款面向平板与手机的 **EPUB 阅读器**（Android 原生壳 + WebView 排版引擎）。

采用 **「Kotlin 数据层 + foliate-js 渲染」** 的内核架构：自建解析/定位/持久化/字体/样式分层（Kotlin），排版、分页、翻页与 CFI 交给 foliate-js（`flow=paginated` 单栏整页 × 连续滚动双模式原生支持）。

---

## 功能特性

### 阅读
- **排版引擎**：foliate-js 原生 `flow=paginated` 单栏整页排版、按屏分页。
- **翻页**：屏幕左/中/右三区点击（前页 / 工具栏 / 后页）；左右滑动（foliate 两页分栏 + 平滑动画）；整屏无漂移翻页动画。
- **进度存读**：每次定位上报 `locator`（CFI）落库，冷启动（如启用「自动续读」）或手动打开精确恢复到上次位置。
- **目录（TOC）**：多级树、缩进、点击跳转、当前章高亮、遮罩关闭。

### 样式系统（UI 常驻控件层）
- **排版主题**：`原书设置` / `现代模式` / `传统模式` 列表单选（传统正文衬线、首行缩进两字符）。
- **字号滑块**：以正文 px 步进（9–36px），全书按根锚（正文）等比缩放。
- **行距 / 段间距 / 疏密**：行距（min 1）；垂直留白分两类——「段间距」管段落间距可归零（配首行缩进区分段落），「疏密」管标题/引用/代码等结构块的垂直边距缩放（永不归零）。
- **四向独立页边距**：上下左右滑块 + 数值实时生效，翻页不漂移（左右边距折叠进列间 gap）。
- **日夜整套配色 + 独立覆盖前景/背景色**：日夜 = 背景 + 正文字色成套切换；另可单独覆盖前景/背景色（自绘取色滑块）。

### 字体
- **五分类字体**（正文/标题/代码/粗/斜）+ 中英分离开关；选择列表按**字体自身字形**渲染名称
- 自解析 TTF/OTF/TTC（`FontClassifier` 按覆盖归 中文/拉丁/通用/符号/无效），指定安全目录批量导入私有化落盘。

### 亮度与护眼
- **亮度**：跟随系统（偏移微调）/ 手动画（`-50..100`，0..100 写系统物理背光）。
- **亮度手势**：左/右单指、任意区双指上下滑，可多选，实时生效、退出还原系统亮度。
- **护眼**：暖橙遮罩降蓝光，「蓝光过滤量」上限 50%。

### 沉浸式阅读
- 阅读时隐藏顶/底工具栏与系统状态栏（保持全屏）；点屏幕中键同时唤出。
- **系统状态栏与顶部工具栏一体化**：同一颜色 `#303030`，无边界、同帧滑入滑出（动画时长 `--bars-anim` 与系统状态栏对齐）。

> 功能全景与设计取舍详见 [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md)（进度）与 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（设计）。

---

## 技术栈

| 层 | 选型 |
|---|---|
| UI | Kotlin 2.0.20 · Jetpack Compose（BOM 2024.09.03）· Material3 |
| 渲染 | WebView（AndroidX WebKit）+ **foliate-js 1.0.1** |
| 本地资源 | `WebViewAssetLoader` 走 https 虚拟域（`https://appassets.androidplatform.net/assets/...`），满足 ES Module 动态导入与 fetch 的跨域要求 |
| 数据 | Room 2.6.1（书库 / 进度 / 字体），KSV 2.0.20-1.0.25 |
| SAF | `androidx.documentfile`（书籍导入、字体目录遍历） |
| 构建 | AGP 8.5.2 · Kotlin 2.0.20 · Gradle |
| 平台 | minSdk 23 · targetSdk 35 · compileSdk 35 · Java 17 |

---

## 目录结构

```
app/
  build.gradle.kts           # 含 foliate-js 拉取任务、示例书制作任务
  src/main/
    assets/
      reader.html            # 阅读器 WebView 壳（foliate-view + 工具栏 + 设置面板 + JS 逻辑）
      foliate-js/            # 构建期自动下载（paginator.js 为本地定制版）
      sample/sample.epub     # 构建期自动生成的示例书
    java/com/orilum/
      MainActivity.kt        # 书架占位 + 打开示例书
      ui/reader/ReaderActivity.kt  # WebView 宿主：资源拦截、JSBridge、沉浸式/状态栏、亮度
      data/                  # 解析层 / 书库 / 字体 / 进度 / 设置（Room）
      util/FileLogger.kt     # 内置文件日志（绕开 OEM 系统 logcat 限流）
    res/
  gradle/libs.versions.toml  # 依赖版本目录
  docs/                      # 设计与进度文档
  sample-epub-src/           # 示例书源码（构建期打成合法 EPUB3）
  licenses/FOLIATE-JS-LICENSE
```

核心分包：
- `data/epub/` —— `EpubParser`（zip→container.xml→OPF/spine/manifest/nav）、`EpubResourceReader`（内嵌资源读取）。
- `data/read/` —— `Location`（章节 + 章内偏移）、`ProgressConverter`（章节↔全书进度互转、越界钳制）。
- `data/book/` —— `Book` / `BookReadingState` / `BookImporter` / `BookDao` / `AppDatabase` / `BookRepository`。
- `data/font/` —— `FontParser` / `FontClassifier` / `FontDao` / `FontFace` / `FontRepository`。
- `data/settings/` —— `ReaderSettings` / `ReaderSettingsStore`（默认套 + 用户套双机制）。
- `assets/reader.html` —— 阅读器主体：三区翻页、工具栏、目录抽屉、设置页面栈、样式/亮度注入脚本。

---

## 构建与运行

```bash
# 构建并安装 debug 包到已连接设备
./gradlew :app:installDebug

# 安装后注意：书架「打开示例书」即可体验（无需先 SAF 选书）
```

构建期自动执行的 Gradle 任务（勿手动干预）：
- `downloadFoliateJs` / `fetchFoliateJs` —— 从 npmmirror 下载并解包 foliate-js 到 `assets/foliate-js`（**排除官方 `paginator.js`**，保留本地定制版；该文件已 force-add 纳入版本控制）。
- `makeSampleEpub` —— 把 `sample-epub-src/` 打包成合法 EPUB3（mimetype 首条目 + STORED）到 `assets/sample/sample.epub`。

真机调试提示：vivo/OPPO 平板系统层 logcat 限流且不可关闭，关键日志走内置 `FileLogger` 落盘，读取：

```bash
adb shell run-as com.orilum cat files/logs/sec_*.txt
```

先决环境：JDK 17、Android SDK（buildTools 35）、已连接设备/模拟器。

---

## 排版引擎

- **foliate-js** — 排版/分页/翻页引擎，遵循 **MIT License**，Copyright (c) 2022 John Factotum。
  - 本项目对 `foliate-js/paginator.js` 做了本地定制（四向独立页边距 + 无漂移整屏翻页动画），并已排除官方版本覆盖。
  - 完整 MIT 许可证文本参见 [licenses/FOLIATE-JS-LICENSE](licenses/FOLIATE-JS-LICENSE)。

---

## 项目文档

三份文档各自职责分离，互不交叉：

| 文档 | 职责 |
|---|---|
| [README.md](README.md) | 对外总览：定位、功能、技术栈、构建与许可 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | **设计稿**：内核分层、样式系统设计、选型与取舍 |
| [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) | **进度日志**：已完成 / 进行中 / 待办（唯一溯源） |

> 文档更新规则：完成阶段任务/重大变化/版本发布时在 `PROJECT_STATUS.md` 追加进度记录；引入新设计在 `ARCHITECTURE.md` 补设计。