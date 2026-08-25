# Orilum

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Version](https://img.shields.io/github/v/tag/cphotor/orilum?sort=semver&label=version)](https://github.com/cphotor/orilum/tags)

Orilum 是一款面向安卓平板与手机的 EPUB 阅读器，计划是做**最尊重原版样式的** EPUB 阅读器——适合对书籍排版有苛刻要求的阅读者。

## 产品特点

- **尊重原书排版**：默认「原书设置」，最大程度还原出版方在 EPUB 中定义的版式，不为统一美学而牺牲原版版式。
- **排版风格一键切换**：`原书` / `现代` / `传统` 三套排版风格一键切换，满足不同用户的排版审美。

### 规划中（Roadmap）

- **排版数学公式 + SVG 矢量插图**：以 LaTeX → HTML（**MathJax / KaTeX**）方式渲染科技类书籍的公式与矢量插图，适合阅读数学、物理、工程类书籍。
- **用户自定义 CSS 排版风格**：支持编写并套用专属排版 CSS。
- **划选文字生成选择器**：为用户选择的文字，自动分析出最精准、最稳定的 CSS 选择器，作为后续定制样式的锚点。
- **多级样式修改**：UI 设置 + 人工指定 CSS 样式分层叠加（UI 层优先级最高，可精调任意细节）。
- **指定字体目录 + 任意原书字体替换**：任何原书字体均可指定本地字体逐一替换。

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

### 亮度与护眼
- **指定偏移量的自动亮度调节**：在系统自动调节亮度的基础上，指定正/负偏移量微调
- **支持低于系统最低亮度的调节**：当系统处于最低亮度时，通过叠加黑色遮罩进一步降低亮度
- **多手势手动亮度调节**：可同时或分别指定通过屏幕左侧、右侧单指单指上下滑移或任意区域双指上下滑移调节亮度
- **护眼**：叠加可选暖橙遮罩降低蓝光，并允许用户调节“蓝光过滤量”。

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
  build.gradle.kts           # 含 foliate-js 拉取任务
  src/main/
    assets/
      reader.html            # 阅读器 WebView 壳（foliate-view + 工具栏 + 设置面板 + JS 逻辑）
      foliate-js/            # 构建期自动下载（paginator.js 为本地定制引擎版，见「对引擎的本地定制」）
    java/com/orilum/
      MainActivity.kt        # 书架
      ui/reader/ReaderActivity.kt  # WebView 宿主：资源拦截、JSBridge、沉浸式/状态栏、亮度
      data/                  # 解析层 / 书库 / 字体 / 进度 / 设置（Room）
      util/FileLogger.kt     # 内置文件日志（绕开 OEM 系统 logcat 限流）
    res/
  gradle/libs.versions.toml  # 依赖版本目录
  docs/                      # 设计与进度文档
  licenses/FOLIATE-JS-LICENSE  # foliate-js 的 MIT 许可证
  LICENSE                    # 本项目（Orilum 自身代码）的 MIT 许可证
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
```

构建期自动执行的 Gradle 任务（勿手动干预）：
- `downloadFoliateJs` / `fetchFoliateJs` —— 从 npmmirror 下载并解包 foliate-js 到 `assets/foliate-js`（**排除官方 `paginator.js`**，保留本地定制版；该文件已 force-add 纳入版本控制）。

### CI 自动构建（GitHub Actions）

打 `v*` tag（如 `vX.Y.Z`）即触发云端编译，自动产出**已签名 APK** 并挂到对应 GitHub Release。密钥通过 GitHub Secrets 注入，**永远不入库**：

| 触发 | 行为 |
|---|---|
| 推送 `v*` tag | 云端 `assembleRelease` → 签名 → 签名 APK 挂到 Release |
| 手动 `workflow_dispatch` | 仅构建，APK 存为 Actions artifact，便于验证 |

签名机制：
1. `settings → Secrets and variables → Actions` 配置 4 个 Secrets：`KEYSTORE_B64`（jks 转 base64）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
2. 构建时 workflow 从 `KEYSTORE_B64` 解码出**临时 jks**（仅存在于云端 `runner.temp`），经环境变量注入 `app/build.gradle.kts` 的 `release` 签名配置。
3. 构建结束虚拟机销毁，临时 jks 随之消失，仓库与 Release 均不残留密钥文件。

> 本地无 Secrets 时，`assembleRelease` 会回退用 debug 签名，仅作安装调试用；正式分发请走 CI 的 Release 产物。

---

## 排版引擎

- **foliate-js** — 排版/分页/翻页引擎，遵循 **MIT License**，Copyright (c) 2022 John Factotum。
  - 开源主页：<https://github.com/johnfactotum/foliate-js>
  - 完整 MIT 许可证文本参见 [licenses/FOLIATE-JS-LICENSE](licenses/FOLIATE-JS-LICENSE)。

### 对引擎的本地定制

本项目对 `foliate-js/paginator.js`（`Paginator` 类）做了引擎级定制，并排除官方版本覆盖（该文件 force-add 纳入版本控制，构建任务 `fetchFoliateJs` 不会覆盖它）：

- **四向独立页边距 + 无漂移整屏翻页动画**：左右边距折叠进列间 gap（列宽 = `size − l − r`、列 gap = `l + r`、滚动步长 = `size`），上下边距作每页正文内边距。
- **多 View 三章缓存拼接**：水平分页下按「前章 + 当前章 + 后章」窗口预载，各章以负重叠方式拼接（`left_{i+1} = left_i + w_i − overlap`），左右缓冲真正折叠、各章正文列首尾相接，实现**跨章无缝翻页**（章末→下一章开头、章首→上一章末尾连续动画，无空白页）。
- **滚动/恢复定位吸附列起点**：`#scrollToRect` 把锚点（CFI/区间）对齐到其所在列起点（`col = floor((docX − left)/size)`），与翻页定位一致，保证任意入口（打开恢复、目录跳转、书签）都**首屏即保留左边距**，重复定位不漂移。
- **封面整屏显示**：识别「首页一张大图/矢量封面为主」的章节为封面页（兼容 `<img>` 与 `<svg><image>`），取消其四向页边距；svg 封面重写 `viewBox` 为内嵌 `<image>` 全图尺寸避免出版社裁切视口漏画；封面章节把网格列铺满整屏，去左右白边。
- **三窗口自适应两端**：全书首章无前导空白缓冲（内容落 `scroll=0`），末章尾随空白不纳入滚动总宽——书首/书末都不再出现可滚的空白屏。
- **尺寸漂移修复**：`View.expand()` 在分页步长 `size` 变化而 `pageCount` 未变时也重设 iframe/element 尺寸，避免内容按新尺寸撑开却溢出旧裁剪区（封面横切、章末半屏错位的共同根因）。
- **单一滑动阈值 + rest 基准翻页边界**：翻页触发统一为一个固定像素阈值——未过阈值回弹当前页、超过则动画继续到相邻页（`snap()` 只回弹，不再做重复的半页/方向判定）；`#scrollNext/#scrollPrev` 的边界早退改按「起手指前静止页」判定，根治「过半屏滑到末页卡在中间」。

> 上述引擎改动均为满足「全屏无漂移翻页」与「跨章无缝」目标而做的本地定制；其余排版/分页/CFI 逻辑保持 foliate-js 原版语义。

---

## License

- **Orilum 自身代码**（Kotlin 层、`reader.html` 壳与对 `paginator.js` 的定制）采用 **MIT License**，见根目录 [LICENSE](LICENSE)。
- **第三方组件**遵循各自许可：
  - `foliate-js`（内置排版引擎）— MIT，[licenses/FOLIATE-JS-LICENSE](licenses/FOLIATE-JS-LICENSE)
  - AndroidX / Jetpack Compose / Room 等 — Apache 2.0 等，见其原始分发渠道。

---

## 项目文档

三份文档各自职责分离，互不交叉：

| 文档 | 职责 |
|---|---|
| [README.md](README.md) | 对外总览：定位、功能、技术栈、构建与许可 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | **设计稿**：内核分层、样式系统设计、选型与取舍 |
| [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) | **进度日志**：已完成 / 进行中 / 待办（唯一溯源） |

> 文档更新规则：完成阶段任务/重大变化/版本发布时在 `PROJECT_STATUS.md` 追加进度记录；引入新设计在 `ARCHITECTURE.md` 补设计。