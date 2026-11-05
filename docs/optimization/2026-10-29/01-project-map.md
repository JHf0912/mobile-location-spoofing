# 01 - 跨领域项目地图（Project Map）

**日期**: 2026-10-29
**状态**: 共享脊柱；02–04 的公共背景
**方法**: 只读源码与配置复核；构建/设备结果未重跑
**证据图例**: 见 `00-index.md` 第 3 节

本文件回答三个问题：系统由哪些模块组成、一次会话如何流动、哪些不变量和危险点跨越多个领域。领域细节分别见 `02`、`03`、`04`。

---

## 1. 模块图（Module Graph）

### 1.1 实际依赖

```text
:app
  +--> :core:model        (pure JVM)
  +--> :core:geo          (pure JVM)  ---> :core:model
  +--> :core:data         (Android lib) ---> :core:model
  +--> :core:location     (Android lib) ---> :core:model
                                      ---> :core:geo

:core:geo      -> :core:model
:core:data     -> :core:model
:core:location -> :core:model, :core:geo
:core:model    -> (none)
```

证据：

- `app/build.gradle.kts:98-101`：`:app` 依赖 `:core:model`、`:core:geo`、`:core:data`、`:core:location`。
- `core/location/build.gradle.kts:12-13`：`api(project(":core:model"))`、`api(project(":core:geo"))`。
- `core/data/build.gradle.kts:17`：`api(project(":core:model"))`，**没有** `:core:geo`。
- `core/geo/build.gradle.kts:6`：`api(project(":core:model"))`。
- `core/model/build.gradle.kts:1-3`：只应用 `mobilespoofing.kotlin.jvm`。

### 1.2 依赖注释已过时

`settings.gradle.kts:21-23` 写的是：

```text
Dependency direction is one-way: :app -> :core:* and (location|data) -> geo -> model.
```

实际是 `data -> model`，不是 `data -> geo`。这是 Phase 0 的文档修正项（P0.4），本轮不修改构建文件。

### 1.3 纯 JVM 边界

`:core:model` 与 `:core:geo` 只应用 `mobilespoofing.kotlin.jvm`（`core/model/build.gradle.kts:1-3`、`core/geo/build.gradle.kts:1-3`）。该 convention plugin 不引入 Android 插件，因此 classpath 上没有 `android.jar`，编译器会拒绝 `import android.*`。这是项目最强的结构保证：几何与模型可以用普通 JUnit + 虚拟时间测试。

`build-logic/convention/src/main/kotlin/com/snake/mobilespoofing/buildlogic/KotlinJvmConventionPlugin.kt:11-45` 说明了这一意图，并把 toolchain 设为 JDK 17（`:23-29`）。

### 1.4 运行时组件图

```text
MainActivity / MainRoute
  |
  +--> MainViewModel
         |-- FavoriteRepository ---------> Room (SpoofingDatabase)
         |-- StartMockUseCase -----------> SessionConfigStore
         |                                 + startForegroundService(ACTION_START)
         |-- StopMockUseCase ------------> startService(ACTION_STOP)
         |-- MockStatusStore <------------ status flow
         |-- MockPermissionChecker ------> AppOpsManager
         |
MockLocationService (FGS, location type)
  |-- SessionConfigStore.pending
  |-- MockSessionRunner
  |     |-- MockLocationEngine (Composite)
  |     |     |-- FusedMockLocationEngine --> FusedLocationProviderClient
  |     |     +-- LocationManagerMockEngine -> LocationManager test providers
  |     +-- MockStatusStore
  |-- NotificationManager + WakeLock
  |
MockLocationTileService
  |-- runner.isRunning
  |-- SessionConfigStore.pending
  +-- MockLocationService intents
```

证据：`MainActivity.kt:30-92`、`MainViewModel.kt:34-42`、`MockLocationService.kt:41-52`、`LocationModule.kt:40-62`、`MockLocationTileService.kt:31-41`。

---

## 2. 会话生命周期（Session Lifecycle）

### 2.1 启动

```text
用户点击 Start
  -> MainViewModel.startMock()                         MainViewModel.kt:149-189
     -> 重新检查定位权限 + mock AppOp                  :156-167
     -> buildSpec(state)                              :191-205
     -> StartMockUseCase(spec)                        :188
        -> SessionConfigStore.set(spec)                StartMockUseCase.kt:25
        -> context.startForegroundService(ACTION_START) :27
MockLocationService.onStartCommand(ACTION_START)       MockLocationService.kt:59-79
  -> handleStart()                                     :81-135
     -> 读 SessionConfigStore.pending                  :82
     -> 缺 spec / 无权限 / 非 mock app -> Error + stopSelf  :83-100
     -> startForeground(...)                           :104-114
     -> observeStatusForNotification()                 :116
     -> acquireWakeLock()                              :117
     -> spec.toMovementSource()                        :119-127
     -> runner.start(scope, source, config, onFinished) :129-134
MockSessionRunner.start()                              MockSessionRunner.kt:62-80
  -> previous?.cancel(); job = launch { previous?.join(); run(...) }
MockSessionRunner.run()                                :90-174
  -> engine.startMockMode()                            :100
  -> 循环：source.fixAt(elapsed) -> engine.push(fix)   :111-145
          -> statusStore.emit(MockStatus.Running(point, now)) :125
  -> 到达终点 -> break                                 :137
  -> finally (NonCancellable):
       engine.stopMockMode()                           :157-165
       if current && !failed -> emit(Idle)             :167-172
       if current -> onFinished()                      :171
```

### 2.2 停止

```text
用户点击 Stop / 通知 Stop / 磁贴 Stop
  -> StopMockUseCase()                                StopMockUseCase.kt:17-31
     -> startService(ACTION_STOP)
MockLocationService.onStartCommand(ACTION_STOP)        MockLocationService.kt:68-72
  -> handleStop()                                     :137-142
     -> runner.stop()                                 MockSessionRunner.kt:83-88
        -> accepting = false; job?.cancel()
     -> finishSession() 立即执行                       MockLocationService.kt:144-150
        -> 取消通知 collector、释放 WakeLock、
           stopForeground、stopSelf
  -> onDestroy()                                      :152-158
     -> runner.stop(); releaseWakeLock(); scope.cancel()
```

关键点：`handleStop` 不等待 `run()` 的 `finally` teardown；`onDestroy` 也不 join。清理在 `NonCancellable` 中异步执行，而服务已经 `stopSelf()`。这是 R9 的来源（审计判定：正常路径安全，风险在未来调用者/慢 OEM）。

### 2.3 错误与自然结束

- 启动失败：`MockSessionRunner.kt:100-107` 发 `Error` 后 `return`；`finally` 仍执行 teardown；`failed = true`，所以不重置为 Idle，也不隐藏错误（`:167-172`）。
- 单次 push 失败：`MockSessionRunner.kt:123-135` 发 `Error`（每个失败 tick 都发），循环继续；下一次成功会再发 `Running`。
- source 抛错：`:113-121` 发 `Error` 并 `return`，teardown 执行。
- 路线自然结束：`:137` break；`failed` 为 false，发 `Idle` 并 `onFinished()`。
- Service 侧失败：`MockLocationService.kt:86-113`（缺 spec/权限/AppOp/startForeground 异常）、`:124-126`（坏 spec）都会发 `Error` 并 `finishSession()`。

### 2.4 进程死亡

当前没有跨进程死亡的保护：

- `START_NOT_STICKY`，null intent 直接 `stopSelf()`：`MockLocationService.kt:59-79`。
- 没有 `BOOT_COMPLETED` receiver（`AndroidManifest.xml:1-81` 无该 receiver）。
- 没有 `onTaskRemoved` 覆写（grep 无结果）。
- `SpoofingApp` 只有 `@HiltAndroidApp`：`SpoofingApp.kt:6-8`。
- `SessionConfigStore` 是内存单例：`SessionConfigStore.kt:22-35`；进程重启后为空，service 的 null-intent 路径会停止。
- `MockStatusStore` 是进程内单例：`MockStatusStore.kt:10-26`；新进程从 Idle 开始。
- 系统 test provider 注册在 `system_server`，不随 app 进程死亡消失：`LocationManagerMockEngine.kt:15-26` 自己记录了该风险，但没有实现冷启动清理。

结果：进程被 crash/LMK/force-stop 后，UI 新进程显示 Idle，但系统定位可能仍在返回最后一次假位置。R2 是本轮最高危的运行时问题。

---

## 3. 数据流（Data Flow）

### 3.1 坐标数据

```text
AMap MapView (GCJ02)
  -> onMapClick(LatLng)                               AMapPicker.kt:202
  -> AMapPicker.onPointClick(LatLng)                  :109-116
  -> MapCard.onPointSelected(LatLng)                  MapCard.kt:55-62,120-123
  -> MainViewModel.onMapPointSelected(lat, lon)       MainViewModel.kt:57-68
       -> CoordinateTransformer.toWgs84(... GCJ02)    :58
       -> MainUiState.selectedPoint / routeWaypoints  :61-66
  -> buildSpec -> MovementSpec.Static / RouteSpec     :191-205
  -> SessionConfigStore.set(spec)                     StartMockUseCase.kt:25
  -> spec.toMovementSource()                          MovementSpec.kt:37-45
       -> StaticMovementSource / RouteMovementSource
  -> MockSessionRunner.push(fix)                      MockSessionRunner.kt:124
  -> engine.push(fix)                                 CompositeMockLocationEngine.kt:56-77
       -> LocationManagerMockEngine.push -> Location  LocationManagerMockEngine.kt:79-99
       -> FusedMockLocationEngine.push -> Fused Task  MockLocationEngine.kt:43-45
```

反向（显示）：

```text
MockStatus.Running(pointWgs84, elapsedRealtimeMs)
  -> MockStatusStore.status                           MockStatusStore.kt:20-25
  -> MainViewModel.observeStatus -> MainUiState       MainViewModel.kt:244-260
  -> StatusHero: formatPoint(WGS84)                   StatusHero.kt:35-36
  -> AMapPicker: WGS84 -> GCJ02 for rendering         AMapPicker.kt:361-364
```

### 3.2 收藏数据

```text
FavoriteSection input -> MainScreen favoriteName     MainScreen.kt:67,168-179
  -> MainViewModel.saveFavorite(name)                MainViewModel.kt:93-117
     -> FavoriteRepository.save -> FavoriteDao.insert FavoriteRepository.kt:29-38
  -> Room favorite_locations
  -> FavoriteRepository.observeAll -> MainViewModel.observeFavorites
     -> MainUiState.favorites                        MainViewModel.kt:262-278
```

### 3.3 状态数据

- 唯一状态源：`MockStatusStore`（进程内 `@Singleton`，`MockStatusStore.kt:10-26`）。
- 服务、ViewModel、磁贴都读它；磁贴另外直接读 `runner.isRunning`（`MockLocationTileService.kt:51,106`）。
- 通知 collector 只在文本变化时 `notify`，但每次 `buildNotification` 都调用 `ensureChannel()`（`MockLocationService.kt:205-225,227-229,270-284`）。
- 磁贴不观察状态流，只在 `onStartListening()`/`onClick()` 时刷新（`MockLocationTileService.kt:43-46,75,104-114`）。

---

## 4. 不变量（Invariants）

| 不变量 | 当前实现 | 证据 | 风险 |
|---|---|---|---|
| UI 中的位置是 WGS84 | `MainUiState` 注释声明；地图入口立即转换 | `MainUiState.kt:12-16`、`MainViewModel.kt:53-68` | `GeoPoint` 默认是 GCJ02（`Models.kt:17-21`），任何忘记传 datum 的新代码都会静默偏移几百米 |
| 注入的 `Fix` 是 WGS84 | `Fix` 文档声明；两个 movement source 强制 | `Fix.kt:1-9`、`StaticMovementSource.kt:16-22`、`Route.kt:83-92` | `LocationExt.toLocation` 不校验 datum（`LocationExt.kt:16-35`）；未来新 source 可绕过 |
| 路线 waypoints 至少 2 个且 WGS84 | `Route.of` require | `Route.kt:83-92`、`:109-111` | UI 的 `MIN_ROUTE_WAYPOINTS=2` 与之对齐（`MainUiState.kt:50-52`） |
| 同一时刻只有一个会话 | `sessionId` + `accepting` + `previous.join()` | `MockSessionRunner.kt:36-54,62-80` | `previous?.join()` 无界；旧 teardown 挂起会永久阻塞新 start（审计漏报） |
| 旧 teardown 不拆新会话 | `sessionId` 检查 + `onFinished` 只在 current 时调用 | `MockSessionRunner.kt:167-172,176-180` | `stop()` 不增加 id，正在停止的会话仍会发 Idle/onFinished（刻意） |
| 取消时仍清理 provider | `withContext(NonCancellable) { engine.stopMockMode() }` | `MockSessionRunner.kt:151-165` | `stopMockMode` 内部若 `await()` 永不完成，清理永远不跑（R6） |
| 服务只在一个进程 | `MockStatusStore` 单例注释明确要求 | `MockStatusStore.kt:10-17`、`AndroidManifest.xml:58-62`（无 `android:process`） | 未来给 service 加 `android:process` 会让 UI 永远 Idle |
| 服务不 sticky | 所有路径 `START_NOT_STICKY` | `MockLocationService.kt:59-79` | 进程重启不会自动恢复；这是刻意的安全设计 |
| tick 锚定会话起点，跳过错过的 interval | `nextTickAt = startedAt + ((elapsed / interval) + 1) * interval` | `MockSessionRunner.kt:139-144` | 深睡后不会补发 3,600 个 tick；`delay(wait)` 只在需要时等待 |
| `Running` 每秒都不相等 | `Running` 带 `elapsedRealtimeMs` | `Models.kt:23-34`、`MockSessionRunner.kt:125` | 这是 R1 的根因：StateFlow 去重失效，1 Hz 唤醒全链路 |
| Room v1 schema 不可变 | `version=1`、`exportSchema=false` | `SpoofingDatabase.kt:8-12` | 没有 schema JSON/migration 路径；D3 已决定按已分发处理 |

---

## 5. 危险点（Hazards）

| ID | 危险 | 影响 | 证据 | 置信度 | 详情 |
|---|---|---|---|---|---|
| R2 | 进程死亡后系统 test provider 残留 | 所有 App 继续收到最后一次假位置，直到重启/其他 mock app 清理 | `LocationManagerMockEngine.kt:21-26`；无冷启动清理 | `verified`（代码层）/ `needs-device`（运行时） | `03` 第 3.1 节 |
| R6 | 引擎 start/stop 无超时；fused 先启动 | GMS Task 挂起会阻止 LM 启动；teardown 在同 Task 上 `await()` 会无限阻塞 LM 清理 | `MockLocationEngine.kt:39-49`、`CompositeMockLocationEngine.kt:29-38`、`LocationModule.kt:45`、`MockSessionRunner.kt:157-165` | `verified`（代码层）/ `needs-device`（挂起概率） | `03` 第 3.2 节 |
| R8 | 无显式 `Starting`/`Stopping` | 启动窗口内双击/磁贴可重复启动；UI/磁贴显示"未运行" | `Models.kt:23-34`、`MainUiState.kt:44-48`、`MockLocationTileService.kt:51` | `verified` | `03` 第 3.4 节 |
| R7/R15 | 持续失败不终止；AppOp 撤销后永久错误循环 | loop/WakeLock/通知/服务永久运行；provider 可能残留 | `MockSessionRunner.kt:123-135`、`MockLocationService.kt:96`、`LocationManagerMockEngine.kt:150-160` | `verified`（代码层） | `03` 第 3.3、3.11 节 |
| R18 | 无地图设备无法选点 | x86_64 模拟器/AMap native 失败设备能装能开但不能开始会话 | `AMapPicker.kt:86-93`；无坐标输入 UI | `verified` | `03` 第 3.12 节、`04` 第 5 节 |
| R1 | `Running` 带时间戳导致全链路每秒工作 | 静态会话每天 86,400 次状态发射/重组；耗电 | `Models.kt:27-31`、`MockSessionRunner.kt:125` | `verified`（性能，不是正确性） | `03` 第 4 节、`04` 第 3 节 |
| R4 | 计时器在 ViewModel 推断 | Error 保留旧起点；新 ViewModel 以最新 tick 为起点；重启继承旧起点 | `MainViewModel.kt:244-260` | `verified` | `03` 第 3.6 节、`04` 第 3.2 节 |
| R5 | `SessionConfigStore` 只在 start 写入 | 磁贴可能重启旧位置；屏幕显示新位置 | `SessionConfigStore.kt:22-35`；`StartMockUseCase.kt:25` 是唯一写入；`clear()` 无调用者 | `verified` | `03` 第 3.7 节 |
| R9 | `handleStop` 立即 `finishSession`，不 join teardown | stopSelf/onDestroy 与 teardown 竞态 | `MockLocationService.kt:137-158` | `verified`（代码）/ `needs-device`（实际窗口） | `03` 第 3.5 节 |
| R23 | datum 默认/边界不强制 | GCJ02 点被当 WGS84 注入，偏移数百米 | `Models.kt:17-21`、`MainViewModel.kt:57-58`、`LocationExt.kt:16-35` | `verified` | `03` 第 3.13 节 |
| R14 | 通知权限被拒绝未处理 | Android 13+ 隐藏 FGS 通知，应用内停止入口消失 | `MockLocationService.kt:216-221`；`MainUiState` 无通知状态 | `verified` | `03` 第 3.8 节 |
| R22 | 进程死亡丢失 UI 编辑状态 | mode/点/路线/速度/到达行为全丢 | `MainScreen.kt:67`、`MapCard.kt:44` 只有局部 `rememberSaveable` | `verified` | `03` 第 3.14 节、`04` 第 5 节 |
| R20 | AMap 10.0.600 ELF 4KB 对齐 | 16KB 设备可能无法加载 native 库 | Explore 报告：`libAMapSDK_MAP_v10_0_600.so` `p_align=0x1000` | `verified`（ELF）/ `needs-device`（运行时） | `03` 第 6.1 节 |
| R21 | 构建声明混杂 | AGP/Gradle 耦合成立；keystore stale-input 可能是误报；`tooling.parallel` 在 9.3.1 无效 | `gradle.properties:26`、`app/build.gradle.kts:37-40` | `MIXED` | `02` 第 2 节、`05` |

---

## 6. 诊断命令（Diagnostic Playbook）

以下命令用于验证 provider 残留、服务/唤醒锁状态和耗电。**本轮没有执行**；它们是 Phase 1 设备矩阵的最小工具集。

| 目的 | 命令 | 期望/判读 |
|---|---|---|
| 查看系统 test provider | `adb shell dumpsys location` | 查找 `gps`/`network` 是否为 `test provider`；force-stop 后不应残留 |
| 查看 mock AppOp | `adb shell appops get com.snake.mobilespoofing android:mock_location` | `MODE_ALLOWED` 才是有效；撤销后应终止会话 |
| 查看服务是否存活 | `adb shell dumpsys activity services com.snake.mobilespoofing` | `MockLocationService` 不应在 Idle 后残留 |
| 查看 WakeLock | `adb shell dumpsys power \| grep -i wake`（Windows Git Bash）或 `findstr /i wake` | 查找 `com.snake.mobilespoofing:mock-location`；停止后应释放 |
| 强制进入 Doze | `adb shell dumpsys deviceidle force-idle` | 验证 WakeLock/深睡 tick 行为 |
| 耗电对比 | `adb shell dumpsys batterystats --charged com.snake.mobilespoofing` | 结合 Battery Historian；记录前后 |
| 强制停止 | `adb shell am force-stop com.snake.mobilespoofing` | 然后重新 `dumpsys location`，验证 R2 |
| 杀进程（非 force-stop） | `adb shell am kill com.snake.mobilespoofing` | 验证 LMK/crash 路径 |
| 日志 | `adb logcat -s <tag>` | 注意：当前代码没有任何 `android.util.Log` 调用（grep 无结果），只能看系统日志 |

判读规则：

- `dumpsys location` 有 test provider + app 进程已死 = R2 复现。
- 撤销 AppOp 后服务仍显示 Running/Error 循环 = R7/R15 复现。
- force-stop 后 provider 仍在，只能靠重启或另一个 mock app 清理 = 当前设计已知残留；D9 记录产品接受度。
- 没有两台 OEM 的 `dumpsys` 证据，不得声称 provider 清理"已修复"（见 `06` 发布门禁）。

---

## 7. 跨领域结论（Cross-Domain Conclusions）

1. **会话生命周期的核心矛盾**：runner 在纯 JVM 层做对了取消/teardown 的大部分形状，但 Android 层没有把"会话是否活跃"持久化，也没有显式 Starting/Stopping；因此进程死亡、慢引擎、重复点击都能绕过 runner 的保护。
2. **datum 纪律是项目最好的不变量，但只在"进入 model"时强**：`Route`/`StaticMovementSource` 强制 WGS84，`GeoPoint` 默认却是 GCJ02；`LocationExt` 不校验。坐标兜底入口必须显式 datum，不能复用 `onMapPointSelected`。
3. **1 Hz 状态是性能问题的单点杠杆**：`Running` 的 `elapsedRealtimeMs` 让 StateFlow 每秒都认为状态变化；去掉它（或改成会话恒定的 `sessionEpoch`）可以同时修复静态会话的耗电/重组和计时器起点问题的一部分。
4. **无地图设备不是边缘情况**：AMap 只有 ARM native 库，x86_64 模拟器是标准开发环境；没有坐标输入就没有可用的开发/测试路径，也无法在 CI 设备上验证 UI。
5. **构建与运行时风险是分离的**：AGP/Gradle 耦合、CI 不跑 instrumentation、release 未运行验证是构建侧问题；provider 残留、fused 挂起、OEM FGS 是运行时问题。两者都需要 Phase 0/1 的不同证据，不能互相替代。
