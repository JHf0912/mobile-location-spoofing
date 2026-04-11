# MobileLocationSpoofing (Android)

官方合规、无需 ROOT 的 Android 模拟定位工具（开发测试用途）。

## 能力边界
- 仅使用官方 Mock Location 机制，不做系统破解、Root、绕过风控。
- 可影响大多数使用系统定位 API 的场景，不能承诺所有三方 App 生效。

## 环境要求
- Android Studio Iguana+
- Android 8.0+
- 在设备中开启开发者选项并将本应用设为 `模拟位置信息应用`

## 快速开始
1. 复制 `local.properties.example` 为 `local.properties`，填写 `AMAP_API_KEY`。
2. 在 Android Studio 打开项目并同步依赖。
3. 安装到真机后，先授权定位和通知权限。
4. 打开开发者选项，设置本应用为模拟位置应用。
5. 地图选点 -> 点击“开始模拟”。

## 关键模块
- `app/src/main/java/com/snake/mobilespoofing/service/MockLocationService.kt`：前台服务注入。
- `app/src/main/java/com/snake/mobilespoofing/location/CoordinateTransformer.kt`：坐标系转换。
- `app/src/main/java/com/snake/mobilespoofing/ui/MainViewModel.kt`：业务状态与交互。
- `app/src/main/java/com/snake/mobilespoofing/data/*`：收藏位置 Room 存储。

## 测试
- `CoordinateTransformerTest`：坐标转换基础精度。
- `MockStatusStoreTest`：状态流转。

## 合规说明
- 仅保存用户手动收藏的经纬度和名称。
- 不采集通讯录、短信、相册、设备标识等无关信息。
