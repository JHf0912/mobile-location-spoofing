# 能力边界与用途约束

本文是项目能力边界的稳定说明。README 只保留摘要，细节与证据放在这里；两者冲突时，以代码和本文的证据标注为准。

## 一句话结论

本工具通过 Android 公开的 Mock Location 机制注入坐标。**注入点会被系统标记为 mock，且不提供任何隐藏标记或绕过检测的能力**；对会过滤或校验模拟位置的应用（如高德地图、部分考勤 / 风控 / 金融类应用），无 Root 无法稳定生效。

## 1. 工作机制

- 应用在「开发者选项 → 选择模拟位置信息应用」中被选中后，通过 `LocationManager.addTestProvider` 注册 `gps` / `network` 两个 test provider，并同时启动 Google Fused 客户端通道，按固定间隔推送坐标。
- 内部坐标统一为 WGS84；GCJ02 只在高德地图边界出现并立即转换。
- 不修改系统定位服务，不注入其它进程，不伪造 GNSS 原始观测、基站、Wi-Fi 或 IP。

## 2. 可以保证与不能保证

| 可以保证 | 不能保证 |
| --- | --- |
| 注入坐标进入系统 `gps` / `network` provider 与 Fused 通道 | 所有第三方应用都会采用该坐标 |
| `dumpsys location` 能看到 `gps provider [mock]` / `network provider [mock]` 与本应用 identity | mock 标记不被应用或服务端识别 |
| 停止后移除 test provider；进程被杀后下次冷启动清理残留 | 清理在所有 OEM / force-stop / Doze 场景下都成立（`needs-device`） |
| 单点与路线坐标、速度、到达行为的确定性 | 任何应用层反作弊、完整性校验或服务端定位被绕过 |

### 关于 mock 标记

注入的 provider 在系统侧以 test provider 存在，`dumpsys location` 输出 `[mock]`，框架侧 `Location.isMock()` 返回 `true`。这是系统对"模拟位置"的显式标注，本工具不改写、不隐藏。

- 证据：`docs/optimization/2026-10-29/12-final-verification.md` §4.1（`verified`，x86_64 AVD，API 37.1）。
- `Location.isMock()` 的行为属于 Android 框架公开契约（`inferred`，未在本仓库写断言测试）。

## 3. 会过滤或校验模拟位置的应用

| 应用类型 | 观察 | 证据级别 |
| --- | --- | --- |
| 高德地图 App | 会主动过滤 mock 位置：`dumpsys` 可见其注册在本项目的 mock provider 上并收到坐标，界面仍显示真实位置 | 项目设备实测记录（README 早期结论；原始 `dumpsys` 未归档，引用前应重新验证） |
| 部分考勤 / 风控 / 金融 / 运动类应用 | 普遍会读取 `isMock()`、校验 provider 来源或做服务端交叉验证 | `inferred`（行业常见做法，本项目未逐一举证） |
| 普通地图 / 自研调试 App | 通常直接采用系统定位结果 | `inferred`，取决于具体实现 |

结论：**本工具不对任何第三方 App 承诺"生效"**；能否被采用由目标 App 自身的策略决定。

## 4. 非目标（明确不做）

- 不隐藏 mock 标记，不伪造"非模拟"的定位来源。
- 不做 Hook、进程注入、Xposed / LSPosed 模块、改机或虚拟化环境伪装。
- 不伪造 GNSS 原始观测、基站、Wi-Fi、IP 等其它定位来源。
- 不针对任何应用或服务端做检测对抗、风控规避或完整性校验绕过。
- 不提供上述能力的配置项、插件或路线图。

## 5. 用途约束

仅用于开发调试、自有 App 功能测试与个人研究。**严禁**用于伪造考勤、刷单、欺诈，以及规避任何应用或服务端的安全与风控机制。

README 中提到的启停健壮性改进（租约、冷启动清理、超时、失败终止等），提升的是开发调试场景下的稳定性；它们不改变本文的任何边界，也不改变第三方 App 是否接受 mock 位置。

## 6. 维护约定

- 新增"某 App 可用 / 不可用"的结论前，必须留下 `dumpsys location` 或等效设备证据，并标注 `verified` / `inferred` / `needs-device`。
- 没有两台 OEM 的设备证据，不得把 provider 清理写成"已修复"。
- 本文与 [README.md](../README.md#能力边界) 的摘要保持一致；修改边界表述时两处同步。
