# Edge 与 Chrome 兼容验收

日期：2026-10-10。用户主要使用 Edge，偶尔使用 Chrome；沿用可靠自用工作区及验证后自动合并授权。

目标：在本机真实 Edge 和 Chrome 中复用完整 26 个浏览器场景，验证桌面和手机尺寸下的观看、逐集、列表、收藏和 WebDAV 操作；每个用例仍创建随机 MySQL 库，结束核对清理。

方案：Playwright 默认继续使用锁定的 Chromium。`playwright.config.cjs` 增加可选 `OTAKULOG_BROWSER_CHANNEL`，仅接受 `msedge` 和 `chrome`；未设置时沿用原行为，非法值明确失败。浏览器使用专用临时上下文，不读取用户日常窗口、Cookie 或配置。CI 保留默认 Chromium，不把本机品牌浏览器验收冒充远程覆盖。

技术：原生 JS、Playwright 1.64.0、Java 17、随机隔离 MySQL；复用现有干净 JAR。无业务变更时不调整缓存或数据库版本。

任务：

- [x] 检查已完成阶段和剩余边界；Docker 引擎仍无连接，用户指定 Edge 优先。
- [x] 修改浏览器通道配置；`node --check playwright.config.cjs`，分别列出默认、Edge 和 Chrome 的 26 场景，非法值在启动前退出 1。
- [x] `OTAKULOG_BROWSER_CHANNEL=msedge` 运行完整浏览器回归，Edge 154.0.4258.62 的 26 个场景通过，逐库清理检查通过。
- [x] `OTAKULOG_BROWSER_CHANNEL=chrome` 运行同一套完整回归，Chrome 154.0.8037.99 的 26 个场景通过，逐库清理检查通过。
- [x] Java 编译、JS 语法与空白检查通过，README 和浏览器说明已更新；独立审查无必要修复。无需修改业务代码、缓存或迁移。

发布流程继续按阶段入口执行：提交推送后核对开发 CI，成功再同步并合并推送 main，核对主 CI；最后复用或重启当前版本预览，只读核对业务未变。实际发布状态记录在外部阶段入口和项目记忆，不预写成功结论。

执行命令（PowerShell）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.18'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:OTAKULOG_BROWSER_CHANNEL='msedge'
npm run test:e2e
$env:OTAKULOG_BROWSER_CHANNEL='chrome'
npm run test:e2e
Remove-Item Env:OTAKULOG_BROWSER_CHANNEL
```

边界：这证明指定版本在 Windows 上的 Edge/Chrome 桌面浏览器及手机尺寸页面通过，不证明真实 Android/iOS 浏览器、Safari、Firefox、离线启动、真实网盘或 Docker 部署已验收。安装路径和版本以本机检查结果为准，不自动更新或重装用户浏览器。发布最终状态记录在外部阶段入口与记忆，避免预写成功结论。
