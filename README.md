# Orilum

> 注：Orilum 自身代码的授权尚未确定，除下述第三方组件外不提供任何使用/分发许可。

Orilum 是一款 EPUB 阅读器应用（Android / Kotlin + Compose + WebView + foliate-js）。

## 排版引擎

- **foliate-js** — 排版/分页/翻页引擎，遵循 **MIT License**，Copyright (c) 2022 John Factotum。
  - 本项目对 `foliate-js/paginator.js` 做了本地定制（四向独立页边距 + 无漂移整屏翻页动画），并已排除官方版本覆盖。
  - 完整 MIT 许可证文本参见 [licenses/FOLIATE-JS-LICENSE](licenses/FOLIATE-JS-LICENSE)。