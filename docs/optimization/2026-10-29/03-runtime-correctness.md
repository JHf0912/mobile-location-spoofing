# 03 - 运行时正确性、性能与耗电（Runtime Correctness / Performance / Battery）

**日期**: 2026-10-29
**来源**: Explore B，经独立审计修订
**范围**: 会话生命周期、FGS/Doze/OEM、引擎启停、provider 残留、性能与耗电
**方法**: 只读源码复核 + 既有测试复核；设备行为未重跑
**证据图例**: 见 `00-index.md` 第 3 节

本文件假定读者已读 `01-project-map.md` 第 2–4 节（会话生命周期、数据流、不变量）。这里只展开运行时细节和证据。

---

## 1. 运行时地图（Runtime Map）

### 1.1 参与组件

| 组件 | 职责 | 证据 |
|---|---|---|
| `MainViewModel` | 编辑状态、权限刷新、启动/停止入口、status 收集 | `MainViewModel.kt:34-279` |
| `StartMockUseCase` / `StopMockUseCase` | 写 spec / 发服务 intent | `StartMockUseCase.kt:14-36`、`StopMockUseCase.kt:12-31` |
| `MockLocationService` | FGS、通知、WakeLock、服务生命周期 | `MockLocationService.kt:41-312` |
| `MockSessionRunner` | 注入循环、取消/teardown、session supersede | `MockSessionRunner.kt:28-181` |
| `CompositeMockLocationEngine` | 扇出到 fused + LocationManager | `CompositeMockLocationEngine.kt:17-109` |
| `FusedMockLocationEngine` | GMS `setMockMode`/`setMockLocation` | `MockLocationEngine.kt:33-54` |
| `LocationManagerMockEngine` | 系统 test provider 注册/推送/清理 | `LocationManagerMockEngine.kt:27-182` |
| `MockStatusStore` | 进程内状态流 | `MockStatusStore.kt:18-26` |
| `SessionConfigStore` | 下一次会话的 spec | `SessionConfigStore.kt:22-35` |
| `MockLocationTileService` | QS 磁贴启停/状态 | `MockLocationTileService.kt:31-141` |
| `MockPermissionChecker` | mock AppOp 查询 | `MockPermissionChecker.kt:17-45` |

### 1.2 线程/调度模型

- Service 的 `scope = SupervisorJob() + Dispatchers.Main.immediate`（`MockLocationService.kt:44`）。
- Runner 的循环跑在构造参数 `dispatcher` 上，默认 `Dispatchers.Default`（`MockSessionRunner.kt:31-33`）；Hilt 手工构造时不传，使用默认。
- 状态流 `MutableStateFlow` 在主线程收集（`MainViewModel` 的 `viewModelScope`，`observeStatus`/`observeFavorites`）。
- 通知 collector 也在 service 的 Main scope（`MockLocationService.kt:205-225`）。
- `LocationManager`/`FusedLocationProviderClient` 调用是 suspend/ binder；fused 通过 `kotlinx-coroutines-play-services` 的 `await()`（`MockLocationEngine.kt:8,39-49`）。

含义：fused 的 `await()` 在 runner 的 `Dispatchers.Default` 上挂起，不会阻塞主线程；但 runner 的循环会停在 `engine.startMockMode()`/`push()` 上，且 teardown 的 `NonCancellable` 会等同一个 Task。

---

## 2. 已验证：之前的修复确实成立（Verified Prior Fixes）

以下条目由源码 + 现有测试共同支持。`verified`。

| 修复 | 证据 | 测试 |
|---|---|---|
| 重启竞态：旧 teardown 不会拆新会话 | `MockSessionRunner.kt:36-54`（sessionId/accepting）、`:62-80`（previous.cancel + launch join）、`:167-172`（只有 current 才 Idle/onFinished） | `restarting supersedes the old session without stopping the new one`（`MockSessionRunnerTest.kt:206-243`） |
| 启动期取消仍走 teardown | `finally { withContext(NonCancellable) { stopMockMode() } }`（`MockSessionRunner.kt:151-165`） | `cancelling during start still tears the engine down`（`:245-270`） |
| LM 引擎在 enable 前记录 `touched`，add 成功后 enable 失败也能清理 | `LocationManagerMockEngine.kt:48-57`（add 后立即 `touched += provider`） | 由代码结构保证；无专门单元测试 |
| Composite 全引擎失败会抛错，runner 不再谎报 Running | `CompositeMockLocationEngine.kt:40-42`（start 全失败）、`:74-76`（push 全失败）；`MockSessionRunner.kt:100-107,123-135` | `CompositeMockLocationEngineTest.kt` 的 5 个用例；`MockSessionRunnerTest` 的失败用例 |
| provider 清理覆盖 `touched` 而不只是 `enabled` | `LocationManagerMockEngine.kt:101-115`（stop 用 `touched`）、`:105-110` | 由代码结构保证 |
| FGS 权限/AppOp 检查 | Service `:91-100`；ViewModel `:156-167` | 无 app 测试 |
| `startForeground` 异常捕获 | `MockLocationService.kt:104-114` | 无 app 测试 |
| WakeLock 获取/释放 | `MockLocationService.kt:172-196`（`setReferenceCounted(false)`、`release` 兜底） | 无 app 测试 |
| 深睡眠 tick 对齐 | `MockSessionRunner.kt:139-144`（锚定 `startedAt`，跳过 missed interval） | `a long clock jump skips missed ticks instead of replaying them`（`:272-298`） |
| 主 ABI 地图降级 | `AMapPicker.kt:86-93`（检查 `Build.SUPPORTED_ABIS.firstOrNull()`，不是任意匹配） | 无测试；`reported` 设计意图 |
| 双引擎任一失败不拖垮另一个 | `CompositeMockLocationEngine.kt:29-42`、`:60-76` | `CompositeMockLocationEngineTest` |

注意：这些是"代码形状正确"，不等于"设备上已验证"。R2/R6/R9 的运行时部分仍需 `dumpsys`/真机。

---

## 3. 剩余缺口（Remaining Gaps）

严重度按 Explore B 原始排序；"审计"列给出最终判定。

### 3.1 R2：进程死亡后系统级 test provider 残留（最高危）

**机制**

1. `LocationManagerMockEngine.startMockMode()` 调 `addTestProvider(gps/network)`（`LocationManagerMockEngine.kt:48-57`）。
2. provider 注册在 `system_server`，不随 app 进程退出（`LocationManagerMockEngine.kt:15-26` 注释明确写了这一点）。
3. 进程被 crash/LMK/force-stop 后，没有任何代码再调用 `removeTestProvider`：
   - `START_NOT_STICKY`（`MockLocationService.kt:59-79`）；
   - 没有 `onTaskRemoved`（grep 无结果）；
   - `SpoofingApp` 没有 `onCreate` 清理（`SpoofingApp.kt:6-8`）；
   - 没有持久化"会话活跃/provider 已注册"标记。
4. 新进程的 `MockStatusStore` 从 Idle 开始（`MockStatusStore.kt:20`），UI 显示"待命"，但系统定位仍是假的。

**影响**：所有读取 `LocationManager` 的 App 继续收到最后一次假位置，直到重启或另一个 mock app 清理。这是本项目最严重的用户可见故障。

**证据级别**：代码层 `verified`；运行时需 `needs-device`（`dumpsys location` + force-stop/relaunch）。

**审计**：CONFIRMED（代码层）；运行时需 `dumpsys` 验证。审计强调 provider 名是全局的，租约只能做启发式门槛，不能证明归属。

### 3.2 R6：引擎 start/stop 无超时；fused 先启动

**机制**

- `CompositeMockLocationEngine.startMockMode()` 按 `engines` 顺序启动（`CompositeMockLocationEngine.kt:23-38`）。
- `LocationModule` 传的是 `listOf(fused, locationManager)`（`LocationModule.kt:45`），所以 **fused 先启动**。
- `FusedMockLocationEngine.startMockMode()` 是 `setMockMode(true).await()`（`MockLocationEngine.kt:39-41`），没有超时。
- 如果 fused 的 GMS Task 永不完成，`startMockMode()` 永远不返回，后面的 LM 引擎永远不会启动。
- 更糟的是 teardown：runner 在 `NonCancellable` 中 `engine.stopMockMode()`（`MockSessionRunner.kt:157-165`）；Composite 的 stop 会遍历所有引擎（`CompositeMockLocationEngine.kt:79-96`），fused 的 `setMockMode(false).await()`（`MockLocationEngine.kt:47-49`）如果也挂起，LM 的 `removeTestProvider` 永远不跑。
- 审计补充：**per-push 也没有超时**。fused 的 `setMockLocation(...).await()`（`MockLocationEngine.kt:43-45`）挂起时，LM 永远收不到这一次 push，整个 loop 静默停摆（`CompositeMockLocationEngine.kt:56-77` 是顺序 fan-out）。

**审计判定**：PLAUSIBLE / overstated —— 只有 fused 真的挂起才会阻塞 LM；正常情况下 LM 可用。但阻塞机制是真实的，而且 per-push 漏报是审计新增的。

**证据级别**：代码结构 `verified`；挂起概率/频率 `needs-device`。

### 3.3 R7 + R15：持续失败永不结束；AppOp 撤销后永久错误循环

**机制**

- 单次 push 失败时，runner 发 `MockStatus.Error` 但继续循环（`MockSessionRunner.kt:123-135`）。这是刻意的"单 tick 警告"，但没有连续失败计数。
- 如果 mock AppOp 被撤销、provider 被抢、Play services 挂掉，每次 push 都失败，每秒发 Error，但 loop/WakeLock/通知/服务永久运行。
- AppOp 只在 start 时检查：Service `:96`、ViewModel `:157`；运行中不复查。
- `LocationManagerMockEngine.removeTestProviderQuietly` 吞掉 `SecurityException`（`:150-160`）；`setTestProviderEnabledQuietly` 也一样（`:162-170`）。AppOp 撤销后 provider 可能留到重启。
- provider 被另一个 mock app 移除/替换后，`push` 全失败会 `error(...)`（`LocationManagerMockEngine.kt:96-98`），runner 继续每秒重试；没有 remove/add/enable 重注册逻辑。
- 磁贴/通知/服务没有"终止"路径；只有用户手动 Stop 才结束。

**审计**：R7 CONFIRMED；R15 CONFIRMED。建议"连续失败计数 + AppOp 复检 + 终止并清理"进入 Phase 1，但注意不要用裸 `withTimeout`（见 `05-audit.md` 第 7 节）。

**证据级别**：`verified`（代码层）。

### 3.4 R8：缺少 Starting/Stopping 状态

**机制**

- `MockStatus` 只有 `Idle`/`Running`/`Error`（`Models.kt:23-34`）。
- `startForegroundService`（`StartMockUseCase.kt:27`）到第一个 `Running` tick（`MockSessionRunner.kt:125`）之间，状态仍是 Idle（或旧 Error）。
- `MainUiState.canStartSession` 只排除 `MockStatus.Running`（`MainUiState.kt:44-48`）；在启动窗口内，UI 仍认为可以再次 Start。
- 磁贴直接读 `runner.isRunning`（`MockLocationTileService.kt:51`），启动窗口内为 false，可以再次启动。
- Runner 能安全 supersede（`MockSessionRunner.kt:62-80`），但会重启路线、重建 provider、重发通知。用户看到的是"双击导致会话重启"。

**审计**：CONFIRMED。建议用一个原子 `SessionState`（phase + telemetry）而不是两个 store；`canStartSession` 排除 Starting/Stopping/Running，允许 Idle/terminal Error；`canStop` 在 terminal Error 下禁用。

**证据级别**：`verified`。

### 3.5 R9：`handleStop` 立即 `finishSession`，onDestroy 不 join teardown

**机制**

- `handleStop()` 调 `runner.stop()` 后立即 `finishSession()`（`MockLocationService.kt:137-142`）。
- `finishSession()` 取消通知 collector、释放 WakeLock、`stopForeground`、`stopSelf`（`:144-150`）。
- Runner 的 teardown 在取消后的 `finally` 中异步执行（`MockSessionRunner.kt:151-165`）。
- `onDestroy()` 调 `runner.stop()` 后立即 `scope.cancel()`（`MockLocationService.kt:152-158`）；不等待 teardown。
- 正常路径下，teardown 会在 `NonCancellable` 中跑完；但快速进程终止/OEM 慢销毁可能跳过清理。

**审计**：CONFIRMED，但窗口小；正常路径安全，风险在未来调用者。建议 `stopAndJoin(timeout)` + best-effort，但**不能用 bounded join 超时后继续启动新会话**（会重演旧 bug）；更安全的是停在 `Stopping` 并拒绝新 start。

**证据级别**：`verified`（代码）；实际竞态窗口 `needs-device`。

### 3.6 R4：计时器在 ViewModel 推断

**机制**

- `MainViewModel.observeStatus()`（`MainViewModel.kt:244-260`）：
  - `Running`：`startedAt = state.sessionStartedAtElapsedRealtimeMs ?: status.elapsedRealtimeMs`；
  - `Idle`：`null`；
  - `Error`：保留旧值。
- 后果：
  - Error 时计时器继续走（`StatusHero.kt:49` 的 `rememberElapsedLabel` 仍在计时）。
  - 失败后重新开始会继承旧起点，直到下一个 Running tick 触发 `?:` 逻辑？实际上 `Running` 分支保留旧值，所以新会话的第一个 Running 不会重置起点，计时器继续从旧起点走。
  - ViewModel 在会话已运行时创建（进程重建/配置变化后）会以最新 tick 的 `elapsedRealtimeMs` 为起点，计时器归零。
- 起点应由 runner/session 状态提供，而不是 UI 推断。

**审计**：CONFIRMED。建议 `Running(pointWgs84, sessionEpoch)`，epoch 整个会话恒定；Idle/terminal Error 清除，Degraded 保留。

**证据级别**：`verified`。

### 3.7 R5：`SessionConfigStore` 只在 start 写入，`clear()` 无调用者

**机制**

- `SessionConfigStore.set(spec)` 只在 `StartMockUseCase.kt:25` 调用。
- 编辑选点/路线后不更新 store。
- `clear()`（`SessionConfigStore.kt:32-34`）没有调用者（grep 无结果）。
- 磁贴读 `sessionConfigStore.pending.value`（`MockLocationTileService.kt:53`）重启"最后一次 start 的 spec"，而屏幕显示的是当前编辑状态。

**后果**：磁贴可能重启旧位置；进程重启后 store 为空，磁贴会打开 App（`:53-58`），这是刻意的防呆。

**审计**：CONFIRMED。P1.7 只同步 idle 编辑；运行中编辑是否生效见决策 D5。

**证据级别**：`verified`。

### 3.8 R14：通知权限被拒绝未处理

**机制**

- `MockLocationService.kt:216-221` 捕获 `notify` 的 `RuntimeException`，注释说"QS 磁贴仍是停止入口"。
- 但 Android 13+ 如果 `POST_NOTIFICATIONS` 被拒绝，FGS 通知不可见；应用内没有"通知被关闭"的提示或引导。
- `SetupChecklist` 只有定位权限、模拟位置应用、电池白名单三项（`SetupChecklist.kt:57-79`），没有通知步骤。
- 磁贴是可选替代，不是所有用户都会添加。

**审计**：通知拒绝 CONFIRMED；"`ensureChannel` 每次 rebuild"被夸大（只在 `buildNotification` 时调用，Running 文本不变时不会每秒重建；Running/Error 抖动时可能多次/秒）。

**证据级别**：`verified`。

### 3.9 R15：provider 冲突后不会重新注册

见 3.3。补充：

- `LocationManagerMockEngine.startMockMode()` 每次先 `removeTestProviderQuietly` 再 add（`:48-57`），所以下一次 start 能恢复。
- 但运行中不会重新注册；另一个 mock app 移除/替换 gps/network 后，每秒失败直到手工会话重启。

**证据级别**：`verified`。

### 3.10 审计新增漏报：`previous?.join()` 无界

`MockSessionRunner.start()` 在启动新 job 前 `previous?.join()`（`MockSessionRunner.kt:77`），没有超时。如果旧 teardown 挂起（例如 fused stop 永不完成），新 start 会永久等待，而 `accepting` 已经为 true、`isRunning` 可能为 true。UI 可能显示 Running 但实际没有注入。

**审计**：漏报，进入 Phase 1。修复方向是停在 `Stopping` 并拒绝新 start，而不是 bounded join 超时后继续。

**证据级别**：`verified`（代码结构）。

### 3.11 审计新增漏报：`StopMockUseCase` 在 `startService` 失败时覆盖运行中状态

`StopMockUseCase.invoke()` 捕获异常后 `statusStore.emit(MockStatus.Error(...))`（`StopMockUseCase.kt:22-30`）。如果会话本来在 Running，`startService` 因为后台限制失败，状态会被覆盖成 Error，但会话还在跑。UI 的 Start 可能重新可用（`canStartSession` 只排除 Running），用户可能误点重启。

**审计**：漏报，进入 Phase 1。

**证据级别**：`verified`。

### 3.12 R18：无地图时无路可走

- `AMapPicker` 在主 ABI 不是 arm64/armv7 时显示 placeholder（`AMapPicker.kt:86-93`）。
- 没有手动经纬度输入/粘贴/`geo:` 入口；`selectedPoint` 只能来自地图点击（`MainViewModel.kt:57-68`）或收藏（`:133-147`）。
- x86_64 模拟器/AMap native 加载失败的设备能安装、能打开，但无法选点、无法创建收藏、无法开始会话。

**审计**：CONFIRMED。坐标兜底是 Phase 1 独立项 C1；必须 datum-explicit，不能复用假定 GCJ02 的 `onMapPointSelected`。

**证据级别**：`verified`。

### 3.13 R23：datum 默认与边界不强制

- `GeoPoint` 默认 `CoordType.GCJ02`（`Models.kt:17-21`）。
- `MainViewModel.onMapPointSelected` 假定输入是 GCJ02 并立即转 WGS84（`:57-58`）。
- `Fix` 文档声明 always WGS84（`Fix.kt:1-9`），但 `LocationExt.toLocation` 不校验 datum（`:16-35`）。
- `StaticMovementSource`/`Route` 强制 WGS84（`StaticMovementSource.kt:16-22`、`Route.kt:83-92`），所以正常路径安全；风险在未来新 MovementSource、GPX/粘贴入口、或直接构造 `Fix` 的调用者。

**审计**：CONFIRMED（正常路径安全，风险在未来调用者）。P1.8 的 `geo:`/粘贴坐标按 WGS84 处理，**不能复用** `onMapPointSelected`。

**证据级别**：`verified`。

### 3.14 R22：进程死亡丢失 UI 路由状态

- `MainUiState` 没有 `SavedStateHandle`（`MainViewModel.kt:34-42`）。
- 只有收藏名（`MainScreen.kt:67`）和地图 Dialog 可见性（`MapCard.kt:44`）用了 `rememberSaveable`。
- mode/selectedPoint/routeWaypoints/travelSpeed/endBehavior 在进程死亡后丢失。

**审计**：CONFIRMED；P1.11 用 `SavedStateHandle` 保存这些字段，并限制路线长度。

**证据级别**：`verified`。

### 3.15 其他审计漏报（进入 Phase 1 或记录）

| 漏报 | 证据 | 影响 |
|---|---|---|
| 启动时可能重发旧 Error 通知 | `MockLocationService.kt:105-106` 设 `lastNotificationText = running`；collector `:205-225` 立即收到 store 里的旧 Error，文本不同 -> notify 旧错误 | 启动瞬间通知翻回旧错误 |
| terminal Error 后计时器继续走、Stop 仍可点 | `MainViewModel.kt:254` 保留起点；`MainScreen.kt:76` `canStop = status !is Idle` | 用户误以为会话仍在运行/可停止 |
| 没有 `onTaskRemoved` 决策 | grep 无结果 | 从最近任务划掉后的行为未定义 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 的 Play 政策风险 | `AndroidManifest.xml:13` | 侧载可接受；上架需评估 |
| 备份包含坐标且 UI 无开关 | `AndroidManifest.xml:34`、`backup_rules.xml:8-10`、`data_extraction_rules.xml:4-12` | README 已披露；Phase 4 决策 |
| 没有诊断日志 | grep 无 `android.util.Log` 调用 | 现场失败不可观察；R8 还会混淆引擎类名 |
| `startForegroundService` 后 `stopSelf` 在 OEM 上未验证 | `MockLocationService.kt:81-100`（缺 spec/权限/AppOp 时直接 `stopSelf`，未先 `startForeground`） | 经典 `RemoteServiceException` 形状；需 OEM 验证 |
| Phase 2 Compose 测试依赖与 Phase 3 才加依赖矛盾 | `06` 草案阶段顺序 | 测试依赖必须提前 |

---

## 4. 性能与耗电（Performance / Battery）

### 4.1 1 Hz StateFlow 是主要成本

- `MockSessionRunner.report(Running)` 每个 tick 都发 `MockStatus.Running(fix.point, clock.nowMs())`（`MockSessionRunner.kt:125`）。
- `Running` 的 `elapsedRealtimeMs` 字段（`Models.kt:27-31`）让 data class 每秒都不相等，`MutableStateFlow` 的去重失效。
- 静态会话每天 86,400 次发射（`verified` from code + 1 Hz `SessionConfig.DEFAULT_INTERVAL_MS`，`SessionConfig.kt:18`）。
- 每次发射唤醒：status store、ViewModel collector、通知 collector、Compose 的 `collectAsStateWithLifecycle`。

**审计**：R1 是性能问题，不是正确性 bug；"3,600 次重组/小时"只在有帧时成立。修复杠杆最高，但在 Phase 2。

### 4.2 Binder / 主线程分发

Explore 估算（`inferred`，基于 1 Hz + 两 provider + fused）：

- 稳态 3 IPC/s：两次 `setTestProviderLocation`（gps + network，`LocationManagerMockEngine.kt:83-93`）+ 一次 GMS Task（`FusedMockLocationEngine.push`，`MockLocationEngine.kt:43-45`）。
- 约 259,200 次注入 IPC/天；86,400 次主线程状态分发/天。
- 通知 collector 只在文本变化时 `notify`（`MockLocationService.kt:214`），但每次 `buildNotification` 都 `ensureChannel()`（`:227-229,270-284`）；Running/Error 抖动时可能每秒多次。
- 审计：channel 缓存是次要问题，不要当主要成本。

### 4.3 WakeLock

- `PARTIAL_WAKE_LOCK` 在会话期间一直持有（`MockLocationService.kt:172-186`），没有超时、低电量停止、息屏暂停选项。
- 这是 1 Hz 精确注入的代价；没有 WakeLock 时，Doze 下 loop 会延迟/暂停。
- Explore 粗估 10–30 mA vs Doze 1–3 mA（`hypothesis`，未实测）。电池白名单不是保持 CPU 醒着的东西。
- 审计：Phase 2 的测量协议（`dumpsys batterystats`/Battery Historian，息屏静态 1 小时）之前，不要移除 WakeLock 或改超时。

### 4.4 分配 / GC

- 每 tick 约 5–10 个小对象（`Location`、`Fix`、`Running`、字符串格式化等）；1 Hz 下可忽略。
- 热成本是 WakeLock 驻留和 UI 重组，不是算术。

### 4.5 Doze / 深睡

- WakeLock 持有时深睡对齐路径基本用不上；仅当 acquire 失败或 OEM 强行挂起时有意义（`MockSessionRunner.kt:139-144`）。
- 仍值得保留：它防止醒来后补发几千个 tick（有测试覆盖）。

### 4.6 启动

- Hilt/Room 懒加载；`MainViewModel.init`（`MainViewModel.kt:47-51`）和 `onResume`（`MainActivity.kt:44-50`）各做 AppOps/电池 IPC，几毫秒，可接受但可测。
- 首次 Compose 组合会在主线程构造 `MapView`/加载 native（`AMapPicker.kt:187-205`），是 3s 启动目标的主要成本。
- 没有 baseline profile 覆盖 app 类；先测再优化（见 `02` 第 3.4 节）。

### 4.7 原生地图内存

- 内联卡 + 全屏 Dialog 同时持有两个 `MapView`（`MapCard.kt:55-62,65-71,124-134`）。
- debug APK 约 54.3 MB（`reported`），其中 arm64 AMap `.so` 20.0 MB、armv7 13.4 MB。
- 每次途经点变化移除并重建全部 marker/polyline（`AMapPicker.kt:218-249`）；100 点路线单次点击约 100 次 remove + 101 次 add，两个 MapView 各做一遍。
- polyline 宽度是 `10f`（px）而不是 dp（`:228-232`）。
- 相机状态不保存；`map.clear()` 被刻意避免（`:215-217`），这是对的。

### 4.8 测量状态

本轮**没有**执行任何耗电/启动/帧率测量。以下都是待测假设：

- 静态会话 1 小时息屏耗电（`dumpsys batterystats --charged` + Battery Historian）。
- 冷启动是否满足 3s（Macrobenchmark）。
- Compose 重组次数/稳定性报告。
- 100 收藏时是否只组合可见行（Compose UI test）。
- fused 挂起概率（真机 + Play services 版本矩阵）。

---

## 5. 排序后的机会（Ranked Opportunities）

### P0（Phase 1 最高危，先设计后写码）

| 机会 | 证据 | 审计修正 |
|---|---|---|
| Provider 租约 + 冷启动清理 | `LocationManagerMockEngine.kt:21-26`；无 `SpoofingApp.onCreate` 清理 | 必须加进程级 gate；`SharedPreferences.commit()` 后台线程先于 `addTestProvider`；`stopMockMode` 返回 per-provider 结果/health；租约是启发式；force-stop 只能下次启动清理 |
| 持续失败终止 + AppOp 复检 | `MockSessionRunner.kt:123-135`；Service `:96` 只查一次 | 拆 `Degraded`（仍在跑）与 terminal `Error`；每 N tick 复检；终止时 break 让 `finally` teardown 跑 |
| 引擎 start/stop 超时 + LM 优先 | `MockLocationEngine.kt:39-49`；`LocationModule.kt:45`；`MockSessionRunner.kt:157-165` | **不要裸 `withTimeout`**；用 `withTimeoutOrNull`/先 catch `TimeoutCancellationException`；LM 先启动、stop 反向；per-push 超时；GMS Task 超时后 generation guard + stop 重试 |
| `previous?.join()` 不能无界 | `MockSessionRunner.kt:77` | 超时后停在 `Stopping` 并拒绝新 start，而不是继续启动 |
| 修 `StopMockUseCase` 覆盖运行中状态 | `StopMockUseCase.kt:22-30` | 失败时不要用 Error 覆盖 Running；保留会话状态 |

### P1

| 机会 | 证据 | 备注 |
|---|---|---|
| 显式 Starting/Stopping | `Models.kt:23-34`；`MainUiState.kt:44-48` | 原子 `SessionState`；tile/通知/`requestListeningState` 跟随 phase |
| session epoch | `MockSessionRunner.kt:125`；`MainViewModel.kt:244-260` | epoch 恒定，不是 per-tick timestamp；同时修复 R1/R4 |
| 拆 `Degraded` / terminal `Error` | `Models.kt:33` | UI retry/dismiss 的前置 |
| bounded teardown join + Stopping gate | `MockLocationService.kt:137-158` | 不要 bounded join 超时后继续启动 |
| 坐标输入 / GPX | `AMapPicker.kt:86-93`；无输入 UI | datum-explicit；`geo:`/粘贴按 WGS84 |
| 通知拒绝处理 | `MockLocationService.kt:216-221`；`SetupChecklist.kt:57-79` | checklist 加通知行；磁贴兜底 |
| provider 冲突重注册 | `LocationManagerMockEngine.kt:96-98` | 全 provider 失败时一次有界 backoff 的 remove/add/enable + 新鲜 AppOp 检查 |
| AppOp 复检 | Service `:96`；ViewModel `:157` | 与 P0 终止逻辑一起 |
| spec 同步 / `SavedStateHandle` | `SessionConfigStore.kt:22-35`；`MainScreen.kt:67` | idle 编辑同步；跨进程死亡持久化是产品决策 |
| 诊断日志/环形缓冲 | 无 `Log` 调用 | debug only；记录 start/stop 延迟、per-engine 失败、tick jitter、teardown 时长 |

### P2

| 机会 | 证据 | 备注 |
|---|---|---|
| 去掉 `Running` 时间戳相等性 | `Models.kt:27-31` | 改成 `Running(pointWgs84, sessionEpoch)`；静态会话只发一次 |
| 通知 channel 缓存 | `MockLocationService.kt:227-229,270-284` | `ensureChannel()` 移到 `onCreate` |
| `requestListeningState` | `MockLocationTileService.kt:75,104-114` | start/stop 后请求刷新；磁贴观察 phase |
| 全屏时释放内联地图 | `MapCard.kt:55-71,124-134` | 单实例/keyed diff |
| 电池策略 | `MockLocationService.kt:172-186` | 先测量再改；不要移除 WakeLock |
| ABI split / 16KB 规划 | `libs.versions.toml:45`；`reported` ELF | 先验证 16KB 运行时；ABI split 主要改善分发 |

---

## 6. 新奇发现 / 可复用线索

1. **AMap 10.0.600 不是 16KB-page 兼容**（Explore `reported`，ELF 证据）：APK 里 `libAMapSDK_MAP_v10_0_600.so` 的 `PT_LOAD p_align=0x1000`，而 16KB 设备要求 `0x4000`；AGP 9 的打包（未压缩、ZIP offset 16KB 对齐）没问题，是 vendor 二进制的问题。
   - Google Play 对 target Android 15+ 的 16KB 要求使这既是分发问题也是运行时问题。
   - `runCatching` 只能捕捉同步的 `UnsatisfiedLinkError`，AMap 可能在 handler 线程异步失败（`AMapPicker.kt:187-205` 的注释也提到这一点）。
   - 这是"必须支持无地图选点"的最强理由。
   - 审计：ELF 对齐确认；**16KB 设备上是否真的运行时失败未验证**（`needs-device`），取决于 Android 16 compat mode 和 AMap 加载路径。
2. **AMap JAR 不能贡献 manifest 权限**（Explore）：merged manifest 没有 `ACCESS_NETWORK_STATE`/`ACCESS_WIFI_STATE`，而 AMap 手动集成文档通常要求。地图可能靠内部容错工作，但值得用 logcat/scratch build 验证（`hypothesis`）。
3. **test provider 是系统状态**：`dumpsys location`、`appops get ... android:mock_location`、`dumpsys activity services`、`dumpsys power | grep -i wake`、`dumpsys deviceidle force-idle`、Battery Historian/`dumpsys batterystats --charged` 是这套系统的关键诊断工具（见 `01` 第 6 节）。
4. **`NonCancellable` + 无界 `await()` 是清理陷阱**：取消安全了，但 Task 永不完成时 LM 清理永远不跑；`withTimeout` 是缺失的另一半，但直接包会吞取消（见 `05` 第 7 节）。
5. **Fused-first 顺序削弱了 composite 的意义**：LM 是可靠 fallback，却要等 fused 返回；Play services 冷启动/更新时可能把 100ms 启动变成数秒或无限。
6. **StateFlow equality + timestamp**：`Running` 带 `elapsedRealtimeMs` 是静态会话全链路 1 Hz 工作的唯一原因，修复杠杆极高。
7. **没有任何 `Log` 调用**（本轮 grep 复核：`verified`）：所有失败只以本地化字符串出现在 UI/通知；R8 还会混淆引擎类名。建议 debug 结构化日志/环形缓冲、`StrictMode`、Macrobenchmark startup。
8. **AMap consent 不进备份是好的合规属性**：备份规则只 include database domain（`backup_rules.xml:8-10`、`data_extraction_rules.xml:4-12`），`AMapPrivacy` 的 `amap_privacy` SharedPreferences 不会在新设备被静默恢复（`AMapPrivacy.kt:14-37`）。
9. **磁贴 API 细节正确**：API 34+ 用 `PendingIntent` overload，34 以下用 `Intent` overload（`MockLocationTileService.kt:116-140`）；stop action 用 `PendingIntent.getService` 而非 `getForegroundService`（`MockLocationService.kt:237-249`）。
10. **OEM 现实**：MIUI/HyperOS、EMUI、ColorOS、OriginOS 可能压制/杀 FGS；`IMPORTANCE_DEFAULT` + WakeLock 是正确缓解（`MockLocationService.kt:272-283`），但"自启动/无电池限制"仍需用户操作。磁贴往往是唯一可靠停止入口，所以磁贴状态 bug 不只是外观问题。
11. **测试线索**：core 有良好虚拟时间覆盖；审计时 `app` 没有 test source set，Phase 0 后已有 28 个纯 JVM 测试（`FormatTest`/`MainUiStateTest`，见 `07`）。Robolectric `ShadowLocationManager` + `ApplicationProvider` 可以在无设备下覆盖 service/tile/notification（Phase 1）。
12. **`setMockLocation` 是否要求 FINE？** 仅 COARSE 的会话是否只覆盖 `LocationManager` 消费者？开放问题（见第 7 节）。

---

## 7. 开放问题

1. AMap 10.0.600 在 16KB-booted 设备上到底失败，还是平台 compat mode 能加载 4KB 对齐库？需要 16KB emulator/真机验证；新版本 AMap 是否 16KB-clean？
2. AMap 是否真的需要 `ACCESS_NETWORK_STATE`/`ACCESS_WIFI_STATE`？设备 logcat + scratch build 可答。
3. QS 磁贴点击在 Android 14–16 各 OEM 上是否可靠授予 while-in-use FGS 资格？tile 的 catch-and-open-app 会掩盖问题（`MockLocationTileService.kt:84-98`），需要 logcat。
4. `startForegroundService` 后在 `startForeground` 前 `stopSelf()` 是否在所有 OEM 上避免 5s `RemoteServiceException`？还是应该先发错误通知？（Service `:81-100`）
5. fused client 是否真的消费 `LocationManager` test-provider fix？如果是，fused 引擎可能只是冗余 flakiness；需要 fused-listener 测试 App 验证是否保留两个引擎。
6. `setMockLocation` 是否要求 FINE？仅 COARSE 的会话是否只覆盖 `LocationManager` 消费者？
7. mock AppOp 撤销后 test provider 是否真的留在 `system_server`，还是系统会随 AppOp 变化移除？`dumpsys location` before/after 可答。
8. 实际耗电多少？WakeLock 在各 OEM 省电模式下行为如何？只能实测。
9. API 37（Android 17 时间线）对 FGS 启动/通知的行为变化？当前 compile/target 领先于可查文档。
10. `previous?.join()` 挂起时 UI 的 `isRunning` 到底是什么？需要 Instrumentation/Robolectric 或真机复现。

---

## 8. 置信度说明

- 会话生命周期、错误路径、超时缺失、provider 清理结构、1 Hz 发射、UI 状态缺失：`verified`（源码 + 测试）。
- provider 残留的运行时可见性、fused 挂起概率、OEM FGS 行为、16KB 运行时失败、耗电数值：`needs-device`/`hypothesis`。
- 259,200 IPC/天、10–30 mA 等数字是 Explore 估算（`inferred`），不是测量值。
- 任何"修复后不再残留"的声明，必须有 `dumpsys location` + force-stop/relaunch + 至少两台 OEM 的证据。
