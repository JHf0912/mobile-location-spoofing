<div align="center">

# 模拟定位工具

**Mobile Location Spoofing** —— 基于 Android 官方 Mock Location 机制的开发者定位测试工具

[![CI](https://github.com/JHf0912/mobile-location-spoofing/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/JHf0912/mobile-location-spoofing/actions/workflows/ci.yml)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-green)

**无需 Root** · **单点 / 路线** · **中文 / English**

</div>

> 面向开发调试与功能测试：在设备上注入确定性的位置，验证地图、导航、LBS 类功能。
> 只使用系统公开的模拟位置能力，不做系统破解，也不尝试绕过任何应用的风控。

---

## 能力边界

本工具只使用 Android 公开的 Mock Location 机制：把自己注册成 test provider，再按固定间隔推送坐标。能力与限制都来自这套机制本身，不涉及系统破解、Root、Hook，也不做任何检测对抗。

| ✅ 可以做 | 🚫 做不到 |
| --- | --- |
| 通过 `LocationManager` test provider 与 Google Fused 客户端双通道注入 | 修改基站、Wi-Fi、IP 等其它定位来源 |
| 单点、直线路线、四档速度、三种到达行为 | 绕过应用自身的反作弊、完整性校验或服务端定位 |
| 进程崩溃 / 强杀后，下次启动自动清理残留 provider | 保证所有第三方应用都生效 |
| 地图选点、WGS84 坐标输入、本地收藏、快捷设置磁贴 | 隐藏 mock 标记，或让会过滤模拟位置的应用稳定生效 |

**四条硬边界：**

1. **注入点就是 mock，且会被系统标记。** provider 以系统 test provider 注册，`dumpsys location` 会显示 `gps provider [mock]` / `network provider [mock]`，`Location.isMock()` 为 `true`。本工具不修改、也不隐藏这个标记。
2. **对会过滤或校验模拟位置的应用，无 Root 无法稳定生效。** 高德地图 App 已实测会主动过滤（`dumpsys` 能看到它注册在我们的 mock provider 上并收到坐标，界面仍显示真实位置）；部分考勤、风控、金融、运动类应用也会做同类校验。每个 App 的策略不同，本工具不对任何 App 承诺生效。
3. **不提供、也不计划提供任何检测对抗或绕过能力。** 不做 Hook / 注入、不伪造 GNSS 原始观测、不改机、不隐藏 mock 标记，也不协助规避应用或服务端风控。
4. **严禁用于伪造考勤、刷单、欺诈等用途。** 仅用于开发调试、自有 App 功能测试与个人研究。

> **范围说明**：本项目的启停健壮性改进，提升的是「开发调试 / 自有 App 功能测试」场景下的稳定性；它不改变上面四条边界，也不改变第三方 App 是否接受 mock 位置。
>
> 完整说明与证据见 [docs/capability-boundary.md](docs/capability-boundary.md)。

## 功能

- **两种模式**：单点（静止）与路线（≥ 2 个途经点，支持撤销 / 清空）。
- **路线参数**：步行 / 跑步 / 骑行 / 驾车四档速度；到达终点后停止 / 环线 / 往返。
- **三种选点方式**：高德地图点选（GCJ02 自动转 WGS84）、`lat,lon` 或 `geo:` 坐标输入（WGS84）、收藏点。
- **启动即定位**：首次进入自动申请定位权限，并以当前位置作为默认选点。
- **会话状态机**：待命 / 启动中 / 模拟中 / 降级运行 / 停止中 / 异常。开始、停止按钮由状态机驱动，避免双击重入。
- **可靠的启停**：provider 租约、冷启动清理、引擎超时、连续失败自动终止。
- **后台可控**：前台服务通知带「停止」动作，另有快捷设置磁贴。
- **中英双语、深色模式、减少动画**，界面默认中文并跟随系统语言。

## 使用流程

1. 打开应用，授予定位与通知权限。
2. 「准备」清单会列出三项：定位权限、模拟位置应用、电池白名单；点击每一行直接跳到对应设置。
3. 选择「单点」或「路线」，在地图上点选，或直接输入坐标。
4. 点「开始模拟」。顶部状态胶囊与计时器会显示会话状态。
5. 在应用内、通知或快捷设置磁贴中停止。

## 环境要求

| 项 | 要求 |
| --- | --- |
| Android | 8.0+（`minSdk 26`，`targetSdk 37`） |
| JDK | 21（CI 使用 Temurin 21）；字节码目标 Java 17 |
| Android Studio | 2025.3+（项目锁定 AGP 9.1.0） |
| Gradle | 9.3.1（wrapper 自带，国内走腾讯镜像） |
| 地图 | 高德 3D 地图 SDK 10.0.600，仅提供 ARM 原生库（`arm64-v8a` 与 32 位 ARM），无 x86 |

> x86 / x86_64 模拟器无法加载高德原生库，地图区域会显示兜底卡片；此时用**坐标输入**选点，其余功能均可用。

## 快速开始

### 1. 配置高德 Key

```bash
cp local.properties.example local.properties
```

在 `local.properties` 中填写：

```properties
sdk.dir=/path/to/Android/Sdk
AMAP_API_KEY=你的高德Key
```

Key 在高德开放平台创建，**包名必须是 `com.snake.mobilespoofing`**，并绑定签名证书的 SHA-1（debug 与 release 的 SHA-1 不同，需要分别绑定）。没有 Key 时构建仍然成功，只是地图不渲染。

### 2. 构建与安装

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 设备准备

1. 设置 → 关于手机 → 连点「版本号」开启开发者选项。
2. 开发者选项 → **选择模拟位置信息应用** → 选择「模拟定位工具」。
3. 打开应用，授予定位权限（首次启动会主动询问）。

> 清单中声明了 `ACCESS_MOCK_LOCATION`。它不会被真正授予（签名级权限），但正是这条声明让应用出现在「模拟位置信息应用」列表中。

### 4. 验证注入

```bash
adb shell dumpsys location | grep -A3 "mock"
```

运行中应能看到 `gps provider [mock]` / `network provider [mock]`，以及本应用的 identity。

## 项目结构

```text
mobile-location-spoofing/
├── app/                  Compose 界面、前台服务、快捷磁贴、Hilt 入口
├── core/
│   ├── model/            GeoPoint、SessionState 等纯 JVM 模型
│   ├── geo/              坐标转换、路线几何、移动源
│   ├── location/         注入引擎、会话循环、provider 租约
│   └── data/             Room 收藏存储
├── build-logic/          Gradle convention 插件
├── docs/                 设计与优化归档
└── gradle/               版本目录（libs.versions.toml）
```

模块依赖关系（`core:model` / `core:geo` 的 classpath 上没有 `android.jar`，纯 JVM 边界由编译器强制）：

```text
app ──▶ model, geo, data, location
location ──▶ geo ──▶ model
data ──▶ model
```

## 测试与构建

```bash
./gradlew verify                                # Debug APK + 全部 JVM 单元测试（CI 同款）
./gradlew lint                                  # Android Lint
./gradlew :app:lintVitalRelease                 # Release 专用致命检查
./gradlew :app:assembleRelease                  # R8 + 资源压缩（无 keystore 时为未签名 APK）
./gradlew :core:data:connectedDebugAndroidTest  # 需设备 / 模拟器
```

| 模块 | JVM 测试 | 覆盖内容 |
| --- | ---: | --- |
| `:app` | 101 | UI 状态门控、坐标输入、格式化、服务启动守卫、会话结束归属 |
| `:core:location` | 77 | 会话循环、双引擎、超时、provider 租约、冷启动协调 |
| `:core:geo` | 47 | 坐标转换、球面几何、路线与移动源 |
| `:core:data` | 2 | 坐标类型持久化 |
| **合计** | **227** | 0 失败 / 0 错误 |

另有 4 个 Room 仪器化测试（`core/data/src/androidTest`），需要真机或模拟器；CI 不运行。

## 架构要点

- **单一事实来源**：`SessionState` 同时服务 runner、服务、通知、磁贴和 UI，任何一方都不自行推断阶段或计时。
- **双引擎**：`LocationManager` 先启动（可靠兜底），Fused 后启动；停止时反向。每个引擎独立超时（启动 5 s / 推送 1.5 s / 停止 2 s），超时按失败处理，而不是当作取消。
- **provider 租约**：注册 test provider 前先落盘租约，确认移除后才清除；进程被杀后，下次冷启动清理残留。
- **坐标系**：内部一律 WGS84，地图边界做 GCJ02 转换；`GeoPoint` 携带 `CoordType`，避免几百米级偏移。
- **性能**：`Running` 不携带逐秒时间戳，静态会话只发一次状态，计时器由会话 epoch 推导。

完整设计见 [DESIGN.md](DESIGN.md)。

## 已知限制

- **注入点始终带 mock 标记**：系统以及任何读取 `Location.isMock()` 的应用都能识别，本工具不隐藏该标记。
- **过滤 / 校验类应用不保证生效**：高德地图 App 会过滤 mock 位置；部分考勤、风控、金融、运动类应用也有同类校验。未 Root 没有稳定方案，本项目不提供绕过。
- **只覆盖 Android 定位 API**：不改基站、Wi-Fi、IP，也不伪造 GNSS 原始观测或系统定位服务。
- **地图仅 ARM**：高德 10.0.600 不提供 x86 原生库，模拟器上只能使用坐标输入。
- **AMap 与 16 KB page size**：SDK 的 `.so` 仍按 4 KB 对齐，尚未在 16 KB 设备上验证。
- **320 dp / 1.3x 字号**：目前是设计目标，没有自动化截图验证。
- **仪器化测试不在 CI**：Room 测试需要本地设备手动运行。
- **不做检测对抗**：不隐藏 mock 标记，不提供 Hook / 注入 / 改机 / 绕过检测的任何能力，也不接受此类需求。

## 隐私与合规

- 只保存用户手动收藏的经纬度与名称，存储在本机 Room 数据库。
- 不采集通讯录、短信、相册、设备标识等无关信息。
- `allowBackup="true"`：收藏数据可能进入系统云备份或换机迁移；不需要时可在系统备份设置中关闭本应用。
- 地图由高德提供。首次加载前必须同意《高德地图隐私政策》，未同意不会创建 `MapView`。
- 本工具仅供开发调试、功能测试与个人研究；**严禁**用于伪造考勤、刷单、欺诈或规避任何应用/服务端的安全与风控机制。

## 文档

| 文档 | 内容 |
| --- | --- |
| [DESIGN.md](DESIGN.md) | 模块架构、会话状态机、注入管线、UI 设计系统与验收标准 |
| [docs/README.md](docs/README.md) | 文档索引 |
| [docs/capability-boundary.md](docs/capability-boundary.md) | 能力边界与用途约束（mock 标记、过滤类应用、非目标） |
| [docs/optimization/2026-10-29/00-index.md](docs/optimization/2026-10-29/00-index.md) | Phase 0 / Phase 1 优化与审计归档 |

## License

[MIT](LICENSE) © 2026 JHf0912
