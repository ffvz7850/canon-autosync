# 佳能备份 Canon-Backup

手机直连佳能相机 Wi-Fi，通过**佳能官方 CCAPI 协议**实现拍照后秒级自动备份（JPG / RAW(CR3) / 视频）到手机相册。

## 功能特性

- **事件驱动自动备份**：采用官方事件接口（`event/polling` 轮询 + `event/monitoring` 事件流），按下快门后新文件**秒级**自动下载到手机
- **断点续传 + 完整性校验**：`Range` 字节级续传，`Content-Length` 大小校验通过后才写入相册，中断自动恢复
- **相机繁忙自适应**：相机 HTTP 服务忙（502/503/504/429）时指数退避重试，自动降并发，恢复后继续
- **后台持续备份**：前台服务 + WakeLock + 悬浮窗保活，退到后台/锁屏仍继续备份
- **多格式支持**：JPG / HEIF / CR3 / CR2 / MP4 / MOV，RAW 保留原名存入「下载/CanonBackup」
- **缩略图预览**：官方 `kind=thumbnail` 接口，本地缓存
- **双向对账**：本地已备份文件自动标记，删除后解除标记，重装 App 后可跳过已备份文件
- **网络诊断**：一键检查 Wi-Fi 网段 / TCP 端口 / HTTP 接口，日志一键复制

## 技术架构

- **WebView 壳 + 原生网络桥**：UI 为 `assets/www/index.html`（H5），网络请求全部走 Java 原生层（`CameraBridge` 注入），彻底规避浏览器 CORS 限制
- **官方协议对齐**：事件端点由相机 `topurlfordev` 自报决定（不做 HTTP 探测），事件帧按官方 `OrgFormatDataSet`（binary-unit）解析，事件循环对齐官方 `EventThread`

## 官方 CCAPI 实现说明（对齐 1.4.0f Sample）

| 模块 | 官方实现 | 本项目对应 |
|---|---|---|
| 端点发现 | `topurlfordev` 自报 → `setAPIDataList` | 连接时拉取 `ver100/topurlfordev`，按自报定事件模式 |
| 事件轮询 | `EventThread` POLLING_LONG（ver110 默认 `timeout=long`） | `eventWatch()`，60s 双超时 + `Accept-Encoding:""` |
| 事件流 | `HttpCommunication.getChunkResponse/readChunk` | `monitorWatch()`：原生 Socket + chunked 分块 + 逐帧回调 |
| 帧解析 | `OrgFormatDataSet`（FF00+type+size+FFFF，Big-Endian） | 1:1 移植 |
| 停止监听 | `stopThread` → DELETE event/polling 或 monitoring | `stopEvent()` |
| 列表分页 | `?kind=list&page=N` / `?kind=number` | `listDir()` 满 100 条自动分页 |
| 缩略图 | `?kind=thumbnail` | `thumb()` 串行下载 + 本地缓存 |

## 构建

- JDK 17 · Android SDK 34（compileSdk/targetSdk）· Gradle 8.7
- 最低支持 Android 10（minSdk 29）

```bash
gradle assembleDebug
```

## 使用方法

1. 相机开机并保持 CCAPI 界面（屏幕显示 `http://IP:8080/ccapi`）
2. 手机连相机 Wi-Fi（相机热点或同一路由器）
3. App 中填入相机屏幕显示的 IP（默认 `192.168.0.126:8080`），点「连接相机」
4. 开启「拍照后自动备份」：按下快门，照片自动存入相册「CanonBackup」相簿

## 权限说明

| 权限 | 用途 |
|---|---|
| 网络 | 直连相机 CCAPI |
| 照片/视频 | 写入系统相册 CanonBackup 相簿 |
| 所有文件访问（可选） | RAW(CR3) 防重与目录级检测，建议开启 |
| 通知/前台服务/悬浮窗/电池白名单 | 后台持续备份保活 |

## 版本

v1.2.84（versionCode 95）

## 免责声明

本项目基于佳能 CCAPI 开放接口实现，仅用于个人摄影工作流自动化。请遵守佳能 CCAPI 使用条款及当地法律法规。
