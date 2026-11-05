# 08 - Phase 1 设计契约（Phase 1 Design Contract）

**日期**: 2026-10-29
**状态**: 设计已确认；**实现 pending**（尚未开始写码）
**来源**: 计划文件 `Phase 1 设计修订（已确认，2026-10-29）` + `Phase 1 / Workstream A/B/C` + 审计修订
**权威性**: 本文是 Phase 1 实现的契约；与 `06-roadmap.md` 的草案条目冲突时，以本文为准；与 `05-audit.md` 的审计判定冲突时，先解决冲突再写码
**证据标签**: 本文是设计文档，不是运行结果；所有设备行为、OEM 行为、fused 晚到 Task、provider 清理效果均标 `needs-device` 或 `inferred`

本文不改变 Phase 1 的范围，只把实现细节固定下来。`00-index.md`/`06-roadmap.md` 只负责链接和状态；源码、构建、CI、README、DESIGN 均不在本文件改动范围。

---

## 1. 范围（Scope）

Phase 1 只做三组工作，按 **A -> audit -> B -> audit -> C -> audit** 执行：

| 工作流 | 内容 | 主要目标 |
|---|---|---|
| **A. Teardown ownership** | provider lease、冷启动清理、引擎契约、timeout-safe 启停、连续失败终止、AppOp 复检 | 进程死亡/慢引擎/provider 冲突下不留下系统级 test provider |
| **B. Session state** | `SessionState` 原子 cutover、phase/epoch、UI/service/tile/notification 迁移 | 单一状态源，显式 Starting/Stopping，静态会话只发一次 Running |
| **C. Coordinate fallback** | WGS84 坐标输入/`geo:` 解析、datum 标签、通知拒绝提示 | 无地图设备可用；不破坏 GCJ02/WGS84 边界 |

### 1.1 非目标（Non-goals）

Phase 1 **不做**：

- 不改变 Room schema；不做 v1 -> v2 migration、路线收藏、历史。
- 不做 GPX/KML、地理编码、第二套地图栈、MapLibre、全球坐标模式。
- 不做 Compose 性能重写、stable/immutable state、收藏虚拟化、地图单实例/keyed diff、baseline profile、ABI split。
- 不做 320dp/1.3x、plurals/RTL、对比度/夜间地图、Material 移除。
- 不做备份加密/开关；不改变运行中编辑语义（仍只影响下一次会话）。
- 不拆 `:core:location`/`:core:session`；不做完整 ViewModel 去 Android 化。
- 不保留 `MockStatus` 兼容 alias；不做双状态源过渡。
- 不提交代码，除非用户明确要求。

---

## 2. 五条不变量（Five Invariants）

这五条是 Phase 1 的验收底线；任何实现选择违反其中一条都必须退回设计。

| # | 不变量 | 含义 | 违反后果 |
|---|---|---|---|
| I1 | **Lease-before-add** | 在 `addTestProvider` 前必须持久化 `ProviderLease`；lease 写入失败则不注册 provider | 进程死亡后无记录，provider 残留无法定位 |
| I2 | **Cancellation never decides cleanup** | 真正的协程取消必须 rethrow；teardown 始终走 `NonCancellable`；超时是失败，不是取消 | 取消被吞/被误判，teardown 被跳过或服务永久运行 |
| I3 | **Runner single authority for phase/epoch** | 只有 runner/service 能推进 `SessionState` 和 `sessionEpoch`；UI/tile/notification 只读，不推断计时器 | 计时器错误、双状态源、磁贴过期 |
| I4 | **Engine timeout is a failure, not cancellation** | 引擎超时用 `withTimeoutOrNull` 归类为 `TimedOut`；`TimeoutCancellationException` 不得外泄 | 现有 rethrow 分支把超时当取消，跳过 `onFinished()` |
| I5 | **UI/tile/notification derive from one phase** | 一个 `StateFlow<SessionState>`；所有入口按 `phase` 决策，不直接读 `runner.isRunning` | 启动窗口/停止窗口不一致，磁贴/通知撒谎 |

---

## 3. SessionState（B 的核心模型）

### 3.1 类型定义

放在 `core:model`，纯 JVM，不依赖 Android：

```kotlin
enum class SessionPhase { Idle, Starting, Running, Degraded, Stopping, Error }

enum class SessionDegradedReason {
    PushRejected,
    PermissionRevoked,
    ProviderConflict,
    EngineTimeout,
}

enum class SessionErrorReason {
    MissingSpec,
    PermissionDenied,
    MockAppNotSelected,
    EngineStartFailed,
    SourceInvalid,
    TooManyConsecutiveFailures,
    ServiceStartFailed,
    TeardownTimeout,
    Unknown,
}

// Phase 1 reason semantics (coordinator decision 2026-10-30):
// - TeardownTimeout is the only non-terminal Error emitted.
// - SessionDegradedReason.PushRejected is the only Degraded reason emitted in Phase 1;
//   PermissionRevoked / ProviderConflict / EngineTimeout are reserved.
// - AppOp revocation is terminal Error(SessionErrorReason.MockAppNotSelected).

sealed interface SessionState {
    val phase: SessionPhase

    data object Idle : SessionState {
        override val phase = SessionPhase.Idle
    }

    data class Starting(
        val sessionId: Long,
        val startedAtElapsedMs: Long,
    ) : SessionState {
        override val phase = SessionPhase.Starting
    }

    data class Running(
        val sessionId: Long,
        val sessionEpochElapsedMs: Long,
        val lastPointWgs84: GeoPoint? = null,
    ) : SessionState {
        override val phase = SessionPhase.Running
    }

    data class Degraded(
        val sessionId: Long,
        val sessionEpochElapsedMs: Long,
        val lastPointWgs84: GeoPoint?,
        val reason: SessionDegradedReason,
        val consecutiveFailures: Int,
    ) : SessionState {
        override val phase = SessionPhase.Degraded
    }

    data class Stopping(
        val sessionId: Long,
        val sessionEpochElapsedMs: Long,
    ) : SessionState {
        override val phase = SessionPhase.Stopping
    }

    data class Error(
        val sessionId: Long?,
        val reason: SessionErrorReason,
        val terminal: Boolean = true,
    ) : SessionState {
        override val phase = SessionPhase.Error
    }
}
```

These names mirror `core/model/Models.kt` as implemented. Alignment with the implemented enum names was requested by the B-1 re-audit G7 (2026-10-30). Do not add ad-hoc reasons without updating both `SessionDegradedReason` and `SessionErrorReason` (and the UI/service localization mapping).

关键约束：

- `Running` **没有** `elapsedRealtimeMs`/`lastFixAtElapsedMs` 字段。静态会话只发一次 `Running`；路线会话只在 `lastPointWgs84` 变化时发。
- `sessionEpochElapsedMs` 是整个会话恒定的起点，不是 per-tick 时间戳。
- `Degraded.reason` 是 `SessionDegradedReason`；`Error.reason` 是 `SessionErrorReason`。`TeardownTimeout` 是唯一非终止 Error；`SessionDegradedReason.PushRejected` 是 Phase 1 唯一 emit 的 Degraded reason；其余 Degraded reason 保留。`detail` 只用于日志/通知文案，不放进 `SessionState`。
- `MockStatus` 不再存在；`MockStatusStore` 的 state 类型改为 `StateFlow<SessionState>`；不允许 `typealias MockStatus = SessionState` 或兼容 shim。

### 3.2 转移矩阵（Transition Matrix）

| 当前 | 事件 | 下一状态 | 守卫/说明 |
|---|---|---|---|
| `Idle` | 用户/磁贴请求 start，cheap checks 通过 | `Starting` | 权限 + mock AppOp + spec 可选；service 在 `startForeground` 前 emit |
| `Idle` | preflight 失败（缺 spec/权限/AppOp，service 启动失败） | `Error(terminal=true, sessionId=null)` | 没有会话需要 teardown；Start 仍可用 |
| `Starting` | 至少一个引擎 `Ok`，首个 push `Ok` | `Running` | epoch 在 `startMockMode` 成功后捕获一次；部分引擎失败只进 `EngineHealthReporter` |
| `Starting` | 至少一个引擎 `Ok`，首个 push `AllRejected` | `Degraded` | 仅 push 失败触发；`consecutiveFailures` 从 1 开始 |
| `Starting` | 所有引擎 `Failed/TimedOut`，或 start 超时 | `Error(terminal=true, sessionId=id)` | 不注册 provider；teardown best-effort |
| `Starting` | 用户 Stop | `Stopping` | 允许；runner 取消启动 |
| `Running` | 连续 push 失败 < 5 | `Degraded` | 仅 push 失败触发；部分引擎失败保持 Running + health |
| `Running` | 用户 Stop | `Stopping` | 进入 teardown |
| `Running` | 连续失败 >= 5 / AppOp 撤销 / source 失败 | `Error(terminal=true)` | AppOp 撤销用 `MockAppNotSelected`；break 让 `finally` teardown |
| `Degraded` | push 恢复 `Ok` | `Running` | 清除连续失败计数 |
| `Degraded` | 用户 Stop | `Stopping` | 进入 teardown |
| `Degraded` | 连续失败 >= 5 / AppOp 撤销 | `Error(terminal=true)` | AppOp 撤销用 `MockAppNotSelected`；terminal |
| `Stopping` | teardown 完成（所有 provider `removed=true` 或本来不存在） | `Idle` | 清除 epoch/lease |
| `Stopping` | teardown budget 用尽（3s） | `Error(terminal=false, reason=SessionErrorReason.TeardownTimeout)` | 保留 runner stuck-teardown flag；`canStartSession=false`、`canStop=false`；不声称 provider 已清理 |
| `Error(SessionErrorReason.TeardownTimeout, terminal=false)` | stuck teardown 最终完成 | `Idle` | 清除 stuck flag/epoch/lease |
| `Error(terminal=true)` | 用户再次 start | `Starting` | 仅在 capabilities/`canStart` 满足且没有活跃 teardown 时 |
| `Starting/Running/Degraded/Stopping` | 再次 start | 保持当前 | UI/use case 拒绝 supersede；runner 仍以 `sessionId`/join 兜底 |

`Stopping` 的 teardown budget 用尽适用于**正常 stop、`onTaskRemoved`、`onDestroy`** 三条路径；三条都 emit 非终止 `Error(TeardownTimeout)`，不是 terminal Error。`Error(TeardownTimeout, terminal=false)` 不是活跃会话：`isSessionActive=false`、`canStop=false`、`canStartSession=false`（因为 gate 只允许 Idle 或 terminal Error）。

禁止的转移：

- `Idle -> Running`：必须经过 `Starting`。
- `Running -> Starting`：必须先 `Stopping`/terminal `Error`。
- `Stopping -> Starting`：拒绝，直到 teardown 完成或进入 terminal Error。
- `Error(terminal=false)`：Phase 1 **只允许 `TeardownTimeout`**；其他错误一律 terminal。非终止 Error 不得覆盖 `Starting/Running/Degraded/Stopping`；`TeardownTimeout` 完成时必须回 `Idle` 并清 stuck flag。

### 3.3 Epoch 规则

- `Starting.startedAtElapsedMs` 是启动请求时间，仅用于诊断/超时，不是会话计时起点。
- `sessionEpochElapsedMs` 在 `engine.startMockMode(sessionId)` 成功后由 **runner** 捕获一次，整个会话恒定。
- `Degraded` 保留 epoch；`Stopping` 保留 epoch 直到 `Idle`；`Idle`/terminal `Error` 清除。
- `Error(TeardownTimeout, terminal=false)` 没有活跃会话：epoch 清除（计时器停止），`sessionId` 保留用于诊断；stuck-teardown flag 在 runner 内单独保留，完成时回 `Idle` 并清 flag。
- `rememberElapsedLabel` 只消费 `sessionEpochElapsedMs`，不再从 `Running.elapsedRealtimeMs` 或 ViewModel 推断。
- 计时器每秒仍走：UI 用 `elapsedRealtime()` 减 epoch，不依赖状态流每秒发射。

### 3.4 Terminal vs Degraded

- `Degraded`：**仅由 push failures 触发**（连续失败 < 5）；`reason` 是 `SessionDegradedReason.PushRejected`；会话仍在跑，至少一个注入路径可用；`canStop=true`，`canStartSession=false`；保留 epoch；UI 显示降级原因，不把 Hero 变成终止错误。
- **部分引擎 start 失败/超时但至少一个引擎启动**：Phase 1 视为 `Running`，失败引擎只通过 `EngineHealthReporter` 报告；**不 emit Degraded**。
- `SessionDegradedReason.PermissionRevoked`/`ProviderConflict`/`EngineTimeout` 是保留的 Degraded reason，Phase 1 暂不 emit。AppOp 撤销是 terminal `Error(SessionErrorReason.MockAppNotSelected)`，不是 Degraded。
- `Error(terminal=true)`：会话已结束；服务/WakeLock/loop 停止；teardown 已尝试；`canStop=false`；`canStartSession` 允许（若 capabilities 满足）；epoch 清除。
- `Error(terminal=false)`：Phase 1 **只用于 `TeardownTimeout`**（正常 stop/`onTaskRemoved`/`onDestroy` 的 bounded join 超时）。它不是活跃会话：`isSessionActive=false`、`canStop=false`、`canStartSession=false`（gate 只允许 Idle 或 terminal Error）；runner 保留 stuck-teardown flag，teardown 最终完成时 emit `Idle` 并清 flag。

### 3.5 Gate 规则

```kotlin
val canStartSession: Boolean
    get() = canStart &&
        permissionGranted &&
        canMock &&
        (state is SessionState.Idle ||
            (state is SessionState.Error && state.terminal))

val isSessionActive: Boolean
    get() = state.phase in setOf(
        SessionPhase.Starting,
        SessionPhase.Running,
        SessionPhase.Degraded,
        SessionPhase.Stopping,
    )

val canStop: Boolean
    get() = isSessionActive
```

- `Starting/Stopping/Running/Degraded` 都不允许新 start。
- terminal `Error` 允许 start，但 Stop 禁用，避免"终止后 Stop 永久可点"。
- 非终止 `Error(TeardownTimeout)` 阻止 start（不在 Idle/terminal Error 集合），且 `isSessionActive=false`/`canStop=false`；stuck teardown 完成前不能启动新会话。
- tile/notification 用同一组 gate；不再直接读 `runner.isRunning`。

### 3.6 一次 cutover（One Cutover）

- 在同一个变更集内删除 `MockStatus`，把 `MockStatusStore` 改成 `StateFlow<SessionState>`，更新 runner、service、tile、use cases、`MainUiState`、`MainViewModel`、`StatusHero`、`StatusPill`、`BottomActionBar`、previews 和全部测试。
- 不保留 `MockStatus` alias、双发射、兼容转换层；编译器错误就是迁移清单。
- `mockStatus` 命名可以保留在 store 类名上，但类型必须是 `SessionState`；如果重命名，和 cutover 同一变更集完成。
- 所有 `when` 必须穷尽 `SessionState`；禁止 `else` 兜底。

---

## 4. Provider Lease（A 的核心）

### 4.1 数据与 API

放在 `core:location`，`ProviderLeaseStore` 是 `@Singleton`：

```kotlin
data class ProviderLease(
    val sessionId: Long,
    val startedAtEpochMs: Long,
    val providers: Set<String>,
    val packageName: String,
)

class ProviderLeaseStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun <T> withLeaseLock(block: suspend () -> T): T
    suspend fun peek(): ProviderLease?
    suspend fun acquire(sessionId: Long, providers: Collection<String>)
    suspend fun release(providers: Collection<String>)
}
```

实现约束：

- SharedPreferences 文件，`MODE_PRIVATE`；建议 key：`sessionId`、`startedAtEpochMs`、`providers`（StringSet）、`packageName`。最终 key 名以实现为准，但字段语义固定。
- 所有写入在 `Dispatchers.IO` 上执行 `commit()`（不是 `apply()`），并在返回前完成；`acquire` 必须在 `addTestProvider` 之前返回。
- `withLeaseLock` 使用 `kotlinx.coroutines.sync.Mutex`；**不可重入**。不要把已经持有锁的调用再包一层（见 §12 不一致 #1）。
- `peek` 只读，不修改 lease；`release(providers)` 只移除指定 provider，全部移除后清空 lease；不删除其他 provider 的记录。
- lease 是**启发式**，不证明 provider 归属；provider 名 `gps`/`network` 是全局的。

### 4.2 写入协议（Lease-before-add）

对 LM 的每个 provider：

1. 在 `withLeaseLock` 内 `acquire(sessionId, listOf(provider))`，等待 `commit()` 成功。
2. 只有 acquire 成功后才 `addTestProvider(provider)`。
3. `addTestProvider` 成功后记录 `touched`；`setTestProviderEnabled(true)` 成功后记录 `enabled`。
4. 任何一步失败，按现有 `LocationManagerMockEngine` 的 `problems` 逻辑处理；已 acquire 的 provider 在 stop 时通过 `ProviderStopResult.removed` 决定是否 `release`。
5. 如果 acquire 本身失败（IO/commit 异常），**不要 add**；该 provider 计为 start failure。

### 4.3 冷启动清理

`SpoofingApp.onCreate`：

- 用 `applicationScope + Dispatchers.IO` 启动 cleanup job；不要在主线程做 IO。
- cleanup 在 gate 内 `peek()`；如果 lease 为空、package 不匹配或没有 provider，直接结束。
- 对每个 lease provider 调 `LocationManagerMockEngine.clearStaleProviders(providers)`。
- 只对 `ProviderStopResult.removed == true` 的 provider 调 `release(providers)`；`removed=false` 保留 lease，下次冷启动重试。
- cleanup 与服务 `handleStart` 必须串行化；见 §4.4。

### 4.4 Start/cleanup gate（实现修订）

计划原文说"服务在 `withLeaseLock` 内启动 runner；引擎在 runner 内注册 provider 时也走同一把锁"。`Mutex` 不可重入，直接嵌套会死锁。08 采用两个层次：

```kotlin
class AppStartupCoordinator @Inject constructor(
    private val leaseStore: ProviderLeaseStore,
    private val locationManagerEngine: LocationManagerMockEngine,
    @ApplicationContext context: Context,
) {
    val cleanupJob: Job                     // applicationScope + Dispatchers.IO
    fun markServiceStarting()               // 设置 in-memory gate，cleanup 看到后放弃
    suspend fun awaitCleanup()              // join cleanupJob，带超时
}
```

- `startGate: Mutex` 只保护 "cleanup vs service start" 这一段；service 在 `markServiceStarting()` 后 join cleanup，再释放 gate。
- `ProviderLeaseStore.withLeaseLock` 只保护 lease 存储的短临界区；引擎 `acquire` 使用它，但**不嵌套**在 `startGate` 内。
- 这样既满足"cleanup 与 add 不重叠"，又避免非重入 Mutex 死锁。该修订是对计划原文的实现修正，需在 1A 审计中确认。

### 4.5 `clearStaleProviders`

```kotlin
suspend fun clearStaleProviders(
    providers: Collection<String>,
): List<ProviderStopResult>
```

逐个 provider：

1. `setTestProviderEnabled(provider, false)`；`IllegalArgumentException` 视为已不存在；`SecurityException` 记录失败。
2. `removeTestProvider(provider)`；成功或 `IllegalArgumentException` -> `removed=true`；`SecurityException` -> `removed=false`，reason 记录 "security"。
3. 返回每个 provider 的 `ProviderStopResult`；不因单个 provider 失败抛异常，不 abort 其他 provider。

### 4.6 Force-stop 与所有权限制

- force-stop 不会调用 `onTerminate`；只能下次冷启动清理。这是**已接受限制**，不是"已修复"。
- provider 名 `gps`/`network` 全局共享；lease 只能记录"我们可能注册过"，不能区分其他 mock app 的 provider。
- 冷启动清理可能删掉另一个 mock app 刚注册的 provider；这是全局命名空间的固有风险，需在 1A 审计/设备矩阵记录。
- 没有 `dumpsys location` + 两台 OEM 证据，不得声称清理"已修复"（`needs-device`）。

---

## 5. Engine Contract（A 的核心）

### 5.1 类型与接口

```kotlin
sealed interface EngineStartOutcome {
    data object Ok : EngineStartOutcome
    data class Failed(val detail: String?) : EngineStartOutcome
    data object TimedOut : EngineStartOutcome
}

sealed interface EnginePushOutcome {
    data object Ok : EnginePushOutcome
    data object AllRejected : EnginePushOutcome
}

data class ProviderStopResult(
    val provider: String,
    val removed: Boolean,
    val reason: String?,
)

interface MockLocationEngine {
    suspend fun startMockMode(sessionId: Long): EngineStartOutcome
    suspend fun push(fix: Fix): EnginePushOutcome  // 单引擎：Ok / AllRejected；rethrow CancellationException
    suspend fun stopMockMode(): List<ProviderStopResult> // 不因单 provider 失败抛错；rethrow CancellationException
}
```

约定：

- 实现只对预期失败返回 outcome；`CancellationException` 必须 rethrow，不包装成 `Failed`/`AllRejected`。
- `stopMockMode()` 返回 `ProviderStopResult` 列表；LM 返回 `gps`/`network` 的结果；fused 返回空列表（它没有系统 test provider，其 stop 成功/失败由 `EngineHealthReporter` 记录）。
- `EngineHealthReporter` 最小接口（建议）：

```kotlin
interface EngineHealthReporter {
    fun onStart(engine: String, outcome: EngineStartOutcome)
    fun onPush(engine: String, outcome: EnginePushOutcome)
    fun onStop(engine: String, results: List<ProviderStopResult>)
}
```

### 5.2 顺序与聚合

- **Start 顺序：LM 先，fused 后**。LM 是可靠 fallback，不能被 fused 的 Task 阻塞。
- **Stop 顺序：反向**，fused 先、LM 后；或按启动逆序。
- 每个引擎独立超时：start 5s、push 1.5s、stop 2s。一个引擎超时/失败不能中止其他引擎的 start/stop。
- `CompositeMockLocationEngine.startMockMode(sessionId)`：
  - 任一引擎 `Ok` -> 返回 `Ok`（部分成功；health 记录失败引擎）。
  - 部分引擎失败/超时但至少一个 `Ok`：session phase 仍是 `Running`；失败引擎只进 `EngineHealthReporter`，**不触发 Degraded**。
  - 全部 `Failed` -> `Failed(detail=join)`；全部 `TimedOut` -> `TimedOut`；混合 -> `Failed` 带 timeout 标记。
- `CompositeMockLocationEngine.push(fix)`：
  - 任一引擎 `Ok` -> `Ok`。
  - 全部 `AllRejected` -> `AllRejected`。
  - 单引擎超时按 `AllRejected` 计入该引擎，不取消其他引擎。
  - 只有连续 push failures 触发 `Degraded`；部分引擎 push 失败但另一引擎 `Ok` 时仍是 `Running` + health。
- `CompositeMockLocationEngine.stopMockMode()`：遍历所有引擎，收集 `ProviderStopResult`；单个引擎失败/超时继续；返回扁平列表。

### 5.3 超时包装（withTimeoutOrNull + runCatching）

复合层统一使用一个 helper，避免 `TimeoutCancellationException` 外泄：

```kotlin
private sealed interface Timed<out T> {
    data class Value<T>(val value: T) : Timed<T>
    data class Failure(val error: Throwable) : Timed<Nothing>
    data object Timeout : Timed<Nothing>
}

private suspend inline fun <T> timed(
    timeoutMs: Long,
    crossinline block: suspend () -> T,
): Timed<T> {
    val result = withTimeoutOrNull(timeoutMs) {
        runCatching { block() }
    }
    return when {
        result == null -> Timed.Timeout
        result.isSuccess -> Timed.Value(result.getOrThrow())
        result.exceptionOrNull() is CancellationException ->
            throw result.exceptionOrNull() as CancellationException
        else -> Timed.Failure(result.exceptionOrNull()!!)
    }
}
```

规则：

- `withTimeoutOrNull` 的超时返回 `null`，归类为 `TimedOut`，**不是** cancellation。
- `runCatching` 会捕获 `CancellationException`，所以必须显式 rethrow；真正的 caller cancellation 不能被吞。
- 不要在 `MockSessionRunner`、`Composite`、`Fused` 里直接用裸 `withTimeout` 包引擎调用；现有 catch 会把 `TimeoutCancellationException` 当取消 rethrow，跳过 teardown/`onFinished()`。
- `EngineStartOutcome.TimedOut`/`EnginePushOutcome.AllRejected` 是失败，不是取消；runner 按失败计数/终止处理。

### 5.4 Fused generation guard

`FusedMockLocationEngine` 维护 `AtomicLong generation`：

- `startMockMode(sessionId)` 递增 generation，记录 `gen`，发 `setMockMode(true)`。
- 超时返回 `TimedOut`，同时 generation 递增；安排一次 `setMockMode(false)` 重试（最多一次）。
- 晚到的 `setMockMode(true)` 完成时，如果 `generation != gen`，补一次 `setMockMode(false)`，避免 teardown 后 fused mock mode 仍为 on。
- `stopMockMode()` 递增 generation；stop 超时后最多重试一次；返回空 `ProviderStopResult` 列表，health 记录失败。
- 该行为依赖 GMS Task 的晚到语义，`needs-device`；单元测试用 fake Task/可控 suspend 模拟。

### 5.5 LM 细节

- 保留 `touched`/`enabled`；start 前 `removeTestProviderQuietly` 再 add（现有行为）。
- 每个 provider 在 add 前 `acquire` lease；add 成功后 `touched`；enable 成功后 `enabled`。
- stop 时对 `touched` 逐个 `setTestProviderEnabled(false)` + `removeTestProvider`；按 `ProviderStopResult` 决定 `release`。
- 全 provider 失败时做一次有界重注册：backoff 100ms/500ms/2s，最多 3 次 remove/add/enable，并做新鲜 AppOp 检查；结果进入 `EngineHealthReporter`；失败/永久撤销时限流，避免注册风暴。
- `SecurityException` 表示无法确认移除；`removed=false`，lease 保留。

---

## 6. Runner / Service 行为

### 6.1 Bounded `previous.join()`

`MockSessionRunner` 保留 `start(scope, source, config, onFinished)`，新增：

```kotlin
suspend fun stopAndJoin(timeoutMs: Long)
```

`start` 在启动新 job 前：

1. 取 `previous = job`。
2. 如果 previous 存在，`withTimeoutOrNull(STOPPING_BUDGET_MS) { previous.join() }`；`STOPPING_BUDGET_MS = 3_000L`。
3. join 成功：继续启动新会话。
4. join 超时：**不要**继续注册 provider；emit `Error(sessionId = previousSessionId, reason = SessionErrorReason.TeardownTimeout, terminal = false)`（没有 previous session 时 `sessionId = null`），置 runner 的 stuck-teardown flag，`accepting=false`，拒绝新 start；记录日志。teardown 最终完成时 emit `Idle` 并清 flag；在此之前 `canStartSession=false`、`canStop=false`。
5. 只有 join 成功后才 `sessionId++`、`accepting=true`、launch。

现有 `previous?.join()` 无界是审计漏报；不能简单加超时后继续启动，否则重演旧 teardown 拆新会话的 bug。正常 stop、`onTaskRemoved`、`onDestroy` 也都通过 `stopAndJoin(STOPPING_BUDGET_MS)` 走同一规则：超时 -> 非终止 `Error(TeardownTimeout)` + stuck flag，不是 terminal Error。

### 6.2 连续失败与 AppOp

- `consecutivePushFailures >= 5` -> terminal `Error(SessionErrorReason.TooManyConsecutiveFailures)`；break 让 `finally` teardown 跑；停服务/WakeLock/通知。
- `consecutiveStartFailures >= 5` 或 start timeout -> terminal `Error(SessionErrorReason.EngineStartFailed)`。
- 每 10 tick 用 `MockPermissionChecker`（接口 + Android 实现）复检 mock AppOp；撤销 -> terminal `Error(SessionErrorReason.MockAppNotSelected)`。
- 单次 push 失败 -> `Degraded`（若会话仍活），不立即终止；恢复后回 `Running`。部分引擎失败但另一引擎 `Ok` 时仍是 `Running` + health，不是 Degraded。
- source 抛错 -> terminal `Error(SessionErrorReason.SourceInvalid)`。
- 终止/失败路径必须让 `finally` 的 `NonCancellable` teardown 执行；不能在 catch 里 `return` 绕过。

```kotlin
interface MockPermissionChecker {
    fun canMockLocation(): Boolean
}

class AndroidMockPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : MockPermissionChecker
```

### 6.3 Service

- `handleStart`：cheap checks 通过后 `markServiceStarting()`，join cleanup job（带超时），再 `startForeground`，再启动 runner；Starting 在 `startForeground` 前/后按实现固定，但必须在首个 Running 前 emit。
- `onCreate`：创建 notification channel；`buildNotification` 不再 `ensureChannel()`。
- `handleStop` / `onTaskRemoved` / `onDestroy`：`runner.stop()` + `stopAndJoin(STOPPING_BUDGET_MS)`；超时 emit 非终止 `Error(TeardownTimeout)` 并保留 stuck flag，不无限阻塞主线程；teardown 最终完成时回 `Idle`。
- `onDestroy`：best-effort bounded join；不声称绝对保证；进程被杀时 lease + 冷启动清理是真正保护。
- 通知拒绝（`POST_NOTIFICATIONS`）：checklist/服务提示；QS 磁贴作为停止兜底；`notify` 失败不 crash。
- `requestListeningState` 在 start/stop 后调用；tile 观察 phase store。

### 6.4 Use cases

- `StartMockUseCase`：
  - 写 spec 后 `startForegroundService`；失败时仅在**没有活跃会话**时 emit `Error(terminal=true, sessionId=null, reason=SessionErrorReason.ServiceStartFailed)`；不得覆盖 `Starting/Running/Degraded/Stopping`。
- `StopMockUseCase`：
  - `startService` 失败时**保留当前 phase**；不要用 `Error` 覆盖 Running/Degraded；只记录 message/log，等待通知/磁贴兜底。
- `StartMockUseCase`/`StopMockUseCase` 都不得直接推进 phase；phase 由 service/runner 推进。

---

## 7. Coordinate Fallback（C）

### 7.1 Parser（implemented）

实现位置是 `app/ui/input/CoordinateInput.kt`（C3 accepted deviation；不在 `core:geo`）。解析只用字符串操作，不依赖 `android.net.Uri`。实际签名与类型：

```kotlin
sealed interface CoordinateParseResult {
    data class Ok(val point: GeoPoint) : CoordinateParseResult
    data class Invalid(val reason: CoordinateParseError) : CoordinateParseResult
}

enum class CoordinateParseError { Empty, Malformed, OutOfRange, NotFinite }

fun parseCoordinateInput(
    raw: String,
    defaultDatum: CoordType = CoordType.WGS84,
): CoordinateParseResult
```

规则（与实现一致）：

- `lat,lon`：使用 `defaultDatum`；`MainViewModel.onCoordinateInput` 调用时使用默认 WGS84。
- `geo:lat,lon` / `geo:lat,lon?z=...`：base pair 按 **WGS84**；`z` 不参与解析。
- `geo:base?q=...`：
  - `q` 是有效坐标对（可带 `(label)`）时，q pair **优先**，按 **WGS84**；
  - `q` 是 label/地址（不是坐标对）时，回退到有效的 base pair；base 为 `0,0` placeholder 时拒绝（`geo:0,0?q=Office` 保持 Invalid）；
  - `q` 看起来像坐标对但越界/非 finite 时，返回该错误，不静默替换成 base；
  - `%2C`/`%2c` 解码为逗号；label 文本本身不解析、不使用。
- 空输入 -> `Empty`；格式不对/缺经纬度 -> `Malformed`；NaN/Infinity -> `NotFinite`；`|lat| > 90` 或 `|lon| > 180` -> `OutOfRange`。
- 返回的 `GeoPoint` 的 `coordType` 来自 `defaultDatum`（plain pair）或 WGS84（geo/q pair）；UI 目前只显示 WGS84 标签（C5）。

### 7.2 UI 接入

- `MainViewModel.onCoordinateInput(raw)` 是新入口，**不得复用** `onMapPointSelected`（后者假定 GCJ02）。
- STATIC：替换 `selectedPoint`；ROUTE：追加途经点；路线已满时拒绝并保留输入。
- UI 在输入/坐标旁显示 `WGS84` 标签；GCJ02-vs-WGS84 差异提示尚未实现（C5，deferred Phase 3）。
- 该入口不依赖 AMap；x86/AMap native 失败设备可用。
- StateFlow conflation 仍有 residual（C6，accepted；cleanup 后续处理）。
- 中心 pin 选择作为可选补充：仍走 WGS84 入口，不经过 GCJ02 转换。

---

## 8. 测试矩阵（Test Matrix）

| 区域 | 测试 | 类型 | 证据标签 |
|---|---|---|---|
| SessionState | 转移矩阵每个合法/非法边；`canStartSession`/`canStop`/`isSessionActive`；partial engine start -> Running（非 Degraded）；Degraded 仅 `SessionDegradedReason.PushRejected`；`SessionErrorReason.TeardownTimeout` 非终止 Error 阻止 start | JVM | `verified` after implementation |
| Epoch | 成功后捕获一次；Degraded 保留；Idle/terminal Error 清除；TeardownTimeout 非终止 Error 清 epoch；ViewModel 重建计时器 | JVM | `verified` after implementation |
| Emission | 静态会话只发一次 `Running`；路线只在坐标变化时发 | JVM | `verified` after implementation |
| Runner | hanging start/stop/push；timeout 不被当 cancellation；`previous.join()` 阻塞/超时；连续失败 5 次；AppOp 每 10 tick；Starting/Stopping 下 supersede；teardown 超时 non-terminal Error + stuck flag -> Idle | 虚拟时间 | `verified` after implementation |
| Composite | LM 先启动；fused 挂起不影响 LM；per-push timeout；反向 stop；单引擎失败聚合（Running + health，不是 Degraded）；真正 cancellation rethrow | JVM | `verified` after implementation |
| Lease | write-before-add；clear-only-after-confirmed-remove；partial stop（gps removed/network SecurityException）；cold-start cleanup 与 start 的 gate 互斥；sessionId 传播 | JVM + fake | `verified` after implementation |
| Fused generation | 晚到 `setMockMode(true)` 补 stop；stop 超时最多重试一次 | JVM fake + 设备 | `needs-device` for real GMS |
| Service/Tile | service start/stop；通知 channel/拒绝；tile phase；`onTaskRemoved`；AppOp 复检 | Robolectric | `verified` after implementation |
| Coordinate | `lat,lon`/`geo:`/空格/负坐标/边界/NaN/Infinity/空/非法 scheme；WGS84 输出；不走 GCJ02 入口 | JVM | `verified` after implementation |
| UI | datum 标签；Start/Stop gate 随 phase；非终止 TeardownTimeout 时 Start 禁用、Stop 禁用；错误/降级文案 | Compose/JVM | `verified` after implementation |
| Device | force-stop/relaunch `dumpsys location`；am kill/LMK；AppOp 撤销；第二 mock app；通知拒绝；tile start/stop；至少两个 OEM | 真机 | `needs-device` |
| Device | 16KB/AMap 不在 Phase 1；不引用旧结论 | 设备 | `needs-device`（不阻塞 Phase 1） |

测试规则：

- 没有 `dumpsys location` + 两台 OEM 证据，不得声称 provider 清理"已修复"。
- 没有真机，不得把 fused 晚到 Task、OEM FGS、AppOp 撤销写成"已验证"。
- `previous.join()` 超时测试必须断言"没有注册 provider、没有启动新会话"。
- `withTimeoutOrNull` 测试必须断言 `CancellationException` rethrow，不被归类为 `Failed`。

---

## 9. 文件级改动清单与顺序

### 9.1 1A（teardown ownership）

新增：

- `core/location/src/main/kotlin/com/snake/mobilespoofing/core/location/ProviderLeaseStore.kt`
- `core/location/src/test/kotlin/com/snake/mobilespoofing/core/location/ProviderLeaseStoreTest.kt`
- `core/location/src/test/kotlin/com/snake/mobilespoofing/core/location/FakeLeaseStore.kt`（或测试内 fake）

修改：

- `core/location/.../MockLocationEngine.kt`：接口契约 + `FusedMockLocationEngine` outcomes/generation。
- `core/location/.../CompositeMockLocationEngine.kt`：LM-first、timeout、聚合、反向 stop。
- `core/location/.../LocationManagerMockEngine.kt`：lease acquire-before-add、`ProviderStopResult`、`clearStaleProviders`、有界重注册。
- `core/location/.../MockSessionRunner.kt`：bounded join、stuck-teardown flag / 非终止 `TeardownTimeout`、连续失败、AppOp、start timeout；**1A 仍使用旧 `MockStatus`**，不碰 UI state model。
- `core/location/.../MockPermissionChecker.kt`：抽接口 + Android 实现。
- `core/location/.../di/LocationModule.kt`：绑定 lease/checker/health。
- `app/.../SpoofingApp.kt`：applicationScope + cleanup job。
- `app/.../service/MockLocationService.kt`：gate/join cleanup、onTaskRemoved、ensureChannel。
- `app/.../domain/StartMockUseCase.kt` / `StopMockUseCase.kt`：不覆盖运行中状态。
- `core/location/src/test/.../MockSessionRunnerTest.kt`、`CompositeMockLocationEngineTest.kt`：新契约/超时/失败测试。
- `app/src/test/...`：必要的 use case/service 测试（如已有）。

不改：`core:model` SessionState、UI state/Compose 组件、Room、地图。

### 9.2 1B（SessionState cutover）

修改：

- `core/model/src/main/kotlin/com/snake/mobilespoofing/core/model/Models.kt`：新增 `SessionState`/`SessionPhase`/`SessionDegradedReason`/`SessionErrorReason`，删除 `MockStatus`。
- `core/location/.../MockStatusStore.kt`：`StateFlow<SessionState>`（类名可保留）。
- `core/location/.../MockSessionRunner.kt`：emit `Starting/Running/Degraded/Stopping/Error`，epoch 恒定。
- `core/location/.../MockErrorStrings.kt`：改为 reason/detail，不产生 model 展示字符串。
- `app/.../ui/MainUiState.kt`、`MainViewModel.kt`：phase/epoch，不再推断计时器。
- `app/.../ui/MainScreen.kt`、`components/StatusHero.kt`、`StatusPill.kt`、`BottomActionBar.kt`、`MainScreenPreviews.kt`：phase 驱动。
- `app/.../service/MockLocationService.kt`、`qs/MockLocationTileService.kt`：phase 驱动、Stopping、requestListeningState。
- `app/.../domain/StartMockUseCase.kt` / `StopMockUseCase.kt`：phase/gate。
- `app/src/main/res/values/strings.xml`、`values-en/strings.xml`：reason 文案。
- 全部相关测试：`MockSessionRunnerTest`、`MockStatusStoreTest`、`MainUiStateTest`、`FormatTest` 不受影响但需编译通过。

### 9.3 1C（坐标兜底/通知拒绝）

新增：

- `core/geo/src/main/kotlin/com/snake/mobilespoofing/core/geo/CoordinateInput.kt`
- `core/geo/src/test/kotlin/com/snake/mobilespoofing/core/geo/CoordinateInputTest.kt`
- `app/.../ui/components/CoordinateInput.kt`（或现有 location section 内组件）

修改：

- `app/.../ui/MainViewModel.kt`：`onCoordinateInput(raw)`；STATIC 替换、ROUTE 追加。
- `app/.../ui/MainScreen.kt`/`components/*`：输入框、WGS84 标签、datum 提示。
- `app/.../service/MockLocationService.kt`、`components/SetupChecklist.kt`：通知拒绝提示。
- `strings.xml`/`values-en/strings.xml`：坐标错误/通知文案。
- `app/src/test/...`：`CoordinateInputTest`、ViewModel/gate 测试（如果 ViewModel 可测）。

不改：Room、地图 provider、AMap。

### 9.4 顺序与审计门禁

```text
1A implement -> 1A audit (09-phase1a-audit.md) -> fix -> core tests
1B implement -> 1B audit (10-phase1b-audit.md) -> fix -> tests
1C implement -> 1C audit (11-phase1c-audit.md) -> fix -> tests
final: assembleDebug verify lint :app:assembleRelease + device matrix -> docs 08-11
```

规则：

- 每个实现 Agent 只改允许的文件边界；不得提交；不得改 README/DESIGN/CI/Room schema（除非明确属于 C）。
- 每个审计 Agent 只读、不修复；输出 CONFIRMED/PLAUSIBLE/REJECTED + 残余风险。
- 未解决的高危项不得进入下一工作流。
- 1A 保持旧 `MockStatus`，避免 UI 与 engine 同时改；1B 才 cutover。

---

## 10. 风险与回滚（Risks & Rollback）

| 变更 | 风险 | 回滚/缓解 |
|---|---|---|
| Provider lease | 冷启动清理误删其他 mock app 的 provider；lease 写入失败导致 start 失败 | start/cleanup gate；只 release `removed=true`；lease 是启发式；1A 审计 + `dumpsys` 设备矩阵；必要时关闭 cleanup job，保留 lease 读取 |
| 冷启动清理 | 清理与服务启动竞态 | `markServiceStarting` + cleanup join；`startGate`；测试 cold-start vs start 互斥 |
| Engine timeout | 超时被当 cancellation；晚到 GMS Task 使 fused 仍 on | `withTimeoutOrNull` + `Timed` helper；generation guard；stop 重试；虚拟时间测试 |
| 1B cutover | UI/stale state 双源；计时器/通知回归 | 一次 cutover、无 alias；全量 `when`；compiler errors 清单；可回滚整个 1B 变更集 |
| `previous.join` | 超时后继续启动，重演旧 teardown bug | 超时 emit 非终止 `Error(TeardownTimeout)` + stuck flag，拒绝 start；完成回 Idle；测试断言没有 provider 注册/新会话 |
| AppOp 复检 | 误判撤销导致会话提前终止 | 每 10 tick 检查；reason/日志；设备矩阵验证；必要时连续两次失败才终止（实现前确认） |
| 坐标输入 | WGS84 输入走 GCJ02 入口，偏移数百米 | 独立 `parseCoordinateInput`；只返回 WGS84；不复用 `onMapPointSelected`；范围校验；UI datum 标签 |
| 通知拒绝 | 用户失去停止入口 | checklist 提示；磁贴兜底；`notify` 失败不 crash；设备验证 |
| OEM/force-stop | provider 残留、FGS 被杀 | `needs-device`；lease + 冷启动清理；记录接受度；不声称"已修复" |

回滚原则：

- 1A 的 lease/cleanup 如果造成启动回归，先关闭 cleanup job（lease 仍写入，不清理），保留 engine 契约修复。
- 1B 必须整组回滚；不允许保留半套 `MockStatus` alias。
- 1C 可隐藏坐标入口回滚，不影响现有地图路径。
- 任何"已修复"声明必须有设备证据；否则只写"代码层已改，`needs-device`"。

---

## 11. Phase 1 明确不改（Not Changed in Phase 1）

- Room schema / `SpoofingDatabase` v1 / migration / 路线收藏 / 历史。
- GPX/KML、地理编码、第二套地图、MapLibre、全球模式。
- Compose 性能、stable/immutable state、收藏虚拟化、地图单实例、baseline profile、ABI split。
- 320dp/1.3x、plurals/RTL、对比度/夜间地图、Material 移除、字体/主题重设计。
- 备份加密/开关、云同步、运行中编辑实时生效。
- 模块拆分、完整 ViewModel 去 Android 化、Hilt test 基础设施扩展（除非 1A/1B 测试需要）。
- `16KB`/AMap 替换；Phase 1 只记录 `needs-device`，不行动。
- `MockStatus` 兼容别名；双状态源；per-tick `Running` 时间戳。

---

## 12. 不一致与待确认（Inconsistencies / Open Questions）

1. **Mutex 不可重入**：计划原文要求"服务在 `withLeaseLock` 内启动 runner；引擎在 runner 内注册 provider 时也走同一把锁"。同一个 `Mutex` 嵌套会死锁。08 采用 `startGate`（cleanup vs service start）+ `withLeaseLock`（lease 存储）两层；1A 审计必须确认这个偏离是可接受的。
2. **`Error.terminal=false` 语义**：原计划只描述 terminal Error 的 gate；coordinator decision 2026-10-30 明确 `TeardownTimeout` 是唯一非终止 Error，其他错误一律 terminal。非终止 Error 阻止 start，且 `canStop`/`isSessionActive=false`；详见本节末尾。
3. **`MockErrorStrings` 与 `SessionState.Error`**：`MockErrorStrings` 现在映射到 `SessionErrorReason`/`SessionDegradedReason`，但 `Error` 只有 `reason`；08 规定 detail 只进日志/通知，不进入 state。
4. **fused 的 `stopMockMode()` 返回值**：接口要求 `List<ProviderStopResult>`，但 fused 没有系统 test provider；08 规定返回空列表，health 记录 stop 成功/失败。1A 审计确认。
5. **`ProviderLeaseStore` 的 `startedAtEpochMs`**：计划未说明是 wall-clock 还是 elapsedRealtime；08 按字段名使用 wall-clock epoch，仅用于诊断，不参与计时；会话计时用 `sessionEpochElapsedMs`。
6. **`EngineHealthReporter` 未在计划中给签名**：08 给出最小接口；实现可按此或等价接口，但不得把 health 塞进 `SessionState`（避免 per-tick 状态）。
7. **`onTaskRemoved` bounded join 的线程/预算**：计划说 bounded join，未给预算；08 复用 `STOPPING_BUDGET_MS=3s`，在 service scope 上执行，不阻塞主线程。`needs-device` 验证 OEM 行为。
8. **`clearStaleProviders` 的归属**：计划把它放在 `LocationManagerMockEngine`；08 保持该归属，不加入 `MockLocationEngine` 接口（fused 不需要）。
9. **设备结论**：provider 清理、fused 晚到 Task、OEM FGS、AppOp 撤销、tile 可靠性均为 `needs-device`；本文不预写"已修复"。

### Implemented coordinate parser (C) and accepted LOW deviations

`parseCoordinateInput` is implemented in `app/ui/input/CoordinateInput.kt` with `CoordinateParseResult.Ok/Invalid` and `CoordinateParseError { Empty, Malformed, OutOfRange, NotFinite }`. The q-parameter rules and `%2C`/`%2c` decoding are recorded in §7.1; the parser is separate from the GCJ02 `onMapPointSelected` path.

| ID | Deviation | Status |
|---|---|---|
| C3 | Parser lives in `app/ui/input`, not `core:geo` | **Accepted**; parser tests are app JVM tests; `core:geo` stays pure JVM for geometry |
| C4 | Error enum names/granularity differ from the original design (`CoordinateParseResult`/`CoordinateParseError`) | **Accepted**; UI maps `Empty/Malformed/OutOfRange/NotFinite` to strings |
| C5 | Only a WGS84 label; no GCJ02-vs-WGS84 difference prompt yet | **Deferred to Phase 3** |
| C6 | StateFlow conflation residual around coordinate input/state updates | **Accepted**; deferred to cleanup |

### Coordinator decisions (2026-10-30)

These decisions resolve the F1/F5 findings in `10-phase1b-audit.md`; the code fix has landed, and this section was aligned with the implemented enums at the B-1 re-audit G7 (2026-10-30).

1. **Teardown timeout on normal stop / `onTaskRemoved` / `onDestroy`**：emit `SessionState.Error(sessionId, SessionErrorReason.TeardownTimeout, terminal = false)` and keep the runner's stuck-teardown flag. `canStartSession` allows only `Idle` or terminal `Error`, so this non-terminal Error blocks Start; `canStop`/`isSessionActive` is `false` (nothing to stop). When the stuck teardown finally completes, emit `Idle` and clear the flag. This supersedes the earlier "terminal Error on teardown timeout" row/description.
2. **Partial engine start**（one engine fails/times out, at least one starts）：Phase 1 treats the session as `Running` with engine health reported via `EngineHealthReporter`; it does **not** emit `Degraded`. `Degraded` is only for `SessionDegradedReason.PushRejected` in Phase 1. `PermissionRevoked`/`ProviderConflict`/`EngineTimeout` are reserved Degraded reasons; AppOp revocation is terminal `Error(SessionErrorReason.MockAppNotSelected)`. This resolves the §3 matrix vs §5.2 contradiction.
