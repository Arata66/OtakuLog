# 本地图标资源

使用 [Phosphor Icons Web](https://github.com/phosphor-icons/web) 的固定 2.1.1 版，遵循同目录 MIT 许可。仅收录项目使用的常规字重，关键按钮同时显示文字。

- 原始样式：https://cdn.jsdelivr.net/npm/@phosphor-icons/web@2.1.1/src/regular/style.css
- 字体：https://cdn.jsdelivr.net/npm/@phosphor-icons/web@2.1.1/src/regular/Phosphor.woff2
- 许可：https://cdn.jsdelivr.net/npm/@phosphor-icons/web@2.1.1/LICENSE

`regular.css` 保留全部常规图标映射，字体来源精简为同目录 WOFF2，并去除上游说明注释。现代浏览器直接加载本地字体，不依赖外部图标服务，无新增运行时包或前端构建步骤。升级时同步样式、字体、许可、此说明与静态缓存版本，再核对浏览器实际字体加载。

文件名使用ASCII，避免Spring Boot 3.2.0的多字节ZIP条目读取问题；说明内容仍为中文，原MIT许可保持在同目录。上游问题见[Spring Boot #38751](https://github.com/spring-projects/spring-boot/issues/38751)。
