# Home Photo Backup

[![Build](https://github.com/NightLemon/photo-backup/actions/workflows/build.yml/badge.svg)](https://github.com/NightLemon/photo-backup/actions/workflows/build.yml)

家庭局域网内的 Android 照片和视频备份工具。Windows 服务端校验并保存原始文件，Android 客户端确认备份完成后才允许清理本地媒体。

## 功能

- 扫码配对、自签名 TLS 公钥固定、设备令牌认证
- 分块上传、SHA-256 校验、断点续传
- 1/2/4/6 路有界并发
- 自动同步：按类型、时间或大小排序
- 手动同步：一次性选择截图、普通图片、视频和日期范围
- 安全清理：保留最近 N 天，删除前重新校验文件内容
- Web 管理：修改存储目录、生成配对码、撤销设备

## 使用

从 [Actions](https://github.com/NightLemon/photo-backup/actions/workflows/build.yml) 下载最新构建产物：

- `home-photo-backup-android`：可覆盖升级的签名 Android APK
- `home-photo-backup-windows`：Windows standalone 服务端

解压 Windows 包后，首次以管理员身份运行 `scripts/allow-standalone-firewall.ps1`，之后运行 `start-standalone.cmd`。管理页会在 <http://127.0.0.1:5444> 打开，可在页面中设置照片目录并生成配对二维码。

手机端要求 Android 10 或更高版本。安装 APK 后授予完整照片和视频权限，扫描管理页二维码即可。

## 构建

- Android：JDK 17、Android SDK 35、Gradle 8.9
- 服务端：Go 1.23+

CI 会运行 Android 单元测试和 Lint、服务端测试，并生成 APK 与 Windows 可执行文件。

## 安全说明

- 管理页只监听本机回环地址；上传 API 仅应开放在受信任的专用网络。
- 配置、证书、数据库和日志保存在用户数据目录，不应提交到仓库。
- 清理依据客户端保存的完成凭据，不会在删除前重新查询服务端；移动或删除服务端文件后不要直接清理手机原件。

协议说明见 [docs/protocol.md](docs/protocol.md)。

## License

MIT

