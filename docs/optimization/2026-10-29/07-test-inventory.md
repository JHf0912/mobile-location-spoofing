# 07 - 测试清单（Test Inventory）

**日期**: 2026-10-29
**状态**: 审计时基线 + Phase 0 后更新
**方法**: 用 `grep -rho "@Test"` 计数；读取 app 测试源码；读取 `app/build/test-results/testDebugUnitTest/*.xml` 核对通过/失败；本轮没有重新运行 Gradle（XML 是 Phase 0 执行产物）
**证据图例**: 见 `00-index.md` 第 3 节

本文件区分三个时点/状态，避免把 Phase 0 后的结果倒写成审计时的结论：

1. **审计时基线**（本归档开始时的快照）：64 个 JVM 测试，`:app` 0 个。
2. **Phase 0 后当前**（2026-10-29 测试 agent 完成后）：92 个 JVM 测试，`:app` 28 个。
3. **Phase 1 计划测试**：仍未实现，单独列在第 4 节。

---

## 0. 时点对照（Snapshot Matrix）

| 时点 | JVM 总数 | `:core:geo` | `:core:location` | `:core:data` | `:app` | Room instrumented | `:app:testDebugUnitTest` |
|---|---:|---:|---:|---:|---:|---:|---|
| 审计时基线（archive start） | 64 | 47 | 15 | 2 | 0 | 3（local-only） | no-op |
| Phase 0 后当前（2026-10-29） | **92** | 47 | 15 | 2 | **28** | 3（local-only） | 运行 28 个，全部通过 |

结论：

- 92 = 47 + 15 + 2 + 28。
- 3 个 Room instrumented 测试**没有变化**，仍然只在本地设备运行，CI 不跑。
- `:app` 的 28 个测试全部是纯 JVM，不需要 Robolectric/设备。
- `:app:testDebugUnitTest` 已不再是 no-op；`verify` 现在会运行这 28 个测试。

---

## 1. 审计时基线（Archive Baseline）

> 这一节是历史快照，用于对照审计结论；不要用它描述当前工作树。

| 模块 | JVM 测试 | 测试类 | 类型 | CI 是否运行 |
|---|---:|---|---|---|
| `:core:geo` | 47 | 5 | 纯 JVM（JUnit 4） | 是（`verify` -> `test`） |
| `:core:location` | 15 | 3 | JVM + `kotlinx-coroutines-test` | 是（`verify` -> `test`） |
| `:core:data` | 2 | 1 | JVM | 是（`verify` -> `test`） |
| `:app` | 0 | 0 | 无 `src/test`、无 `src/androidTest` | `testDebugUnitTest` 是 no-op |
| **JVM 合计** | **64** | **9** | | |
| `:core:data` instrumented | 3 | 1 | `AndroidJUnit4` + Room | **否**（CI 不跑 androidTest） |

补充：

- 64 = 47 + 15 + 2，归档开始时用 grep 复核。
- 3 个 instrumented 测试在本地 Pixel_8 通过（0.56s），来源是 Explore 报告（`reported`），本归档未重跑。
- `:app` 在审计时没有 test source set；`./gradlew verify` 里的 `:app:testDebugUnitTest` 不会失败，因为当时没有测试。

---

## 2. 审计时基线明细（Baseline Detail）

### 2.1 `:core:geo` — 47

| 测试类 | 测试数 | 覆盖内容 | 文件 |
|---|---:|---|---|
| `GeoMathTest` | 20 | haversine、destination、bearing、插值、antimeridian、NaN/退化 | `core/geo/src/test/kotlin/com/snake/mobilespoofing/core/geo/GeoMathTest.kt` |
| `RouteTest` | 9 | 路线构造、去重、闭环、长度、非法输入、positionAt clamp | `core/geo/src/test/kotlin/com/snake/mobilespoofing/core/geo/RouteTest.kt` |
| `RouteMovementSourceTest` | 8 | STOP/LOOP/PING_PONG、速度、时间、非法参数 | `core/geo/src/test/kotlin/com/snake/mobilespoofing/core/geo/RouteMovementSourceTest.kt` |
| `CoordinateTransformerTest` | 8 | WGS84/GCJ02/BD09 双向、境外 passthrough、精度 | `core/geo/src/test/kotlin/com/snake/mobilespoofing/core/geo/CoordinateTransformerTest.kt` |
| `StaticMovementSourceTest` | 2 | 固定点、非 WGS84 拒绝 | `core/geo/src/test/kotlin/com/snake/mobilespoofing/core/geo/StaticMovementSourceTest.kt` |
| **合计** | **47** | | |

### 2.2 `:core:location` — 15

| 测试类 | 测试数 | 覆盖内容 | 文件 |
|---|---:|---|---|
| `MockSessionRunnerTest` | 9 | 每 interval 一次 push、tick 不漂移、取消 teardown、单次 push 失败继续、start 失败、路线自然结束、supersede、启动期取消、深睡跳 tick | `core/location/src/test/kotlin/com/snake/mobilespoofing/core/location/MockSessionRunnerTest.kt` |
| `CompositeMockLocationEngineTest` | 5 | 部分启动、全失败、push 扇出、stop 所有引擎、取消传播 | `core/location/src/test/kotlin/com/snake/mobilespoofing/core/location/CompositeMockLocationEngineTest.kt` |
| `MockStatusStoreTest` | 1 | 状态发射/当前值 | `core/location/src/test/kotlin/com/snake/mobilespoofing/core/location/MockStatusStoreTest.kt` |
| **合计** | **15** | | |

测试技术：

- 手写 fake engine（`MockSessionRunnerTest.kt:32-78`、`CompositeMockLocationEngineTest.kt:23-60`），不依赖 mock 框架。
- `runTest` + `StandardTestDispatcher` + `advanceTimeBy`/`runCurrent`；`ElapsedClock { testScheduler.currentTime }`（`MockSessionRunnerTest.kt:80-298`）。
- `HangingStartEngine` 用 `CompletableDeferred` + `awaitCancellation` 模拟挂起启动（`:62-78`）。
- 覆盖 `NonCancellable` teardown、supersede、深睡跳 tick 等关键路径。

### 2.3 `:core:data` — 2 JVM + 3 instrumented

| 测试类 | 测试数 | 类型 | 覆盖内容 | 文件 |
|---|---:|---|---|---|
| `CoordTypeParsingTest` | 2 | JVM | `parseCoordType` 的已知/未知值（未知回退 WGS84） | `core/data/src/test/kotlin/com/snake/mobilespoofing/core/data/CoordTypeParsingTest.kt` |
| `SpoofingDatabaseTest` | 3 | instrumented | DAO 插入/排序/删除、repository round-trip、file-backed 持久化 | `core/data/src/androidTest/kotlin/com/snake/mobilespoofing/core/data/SpoofingDatabaseTest.kt` |

`SpoofingDatabaseTest` 的三个用例：

1. `daoInsertsOrdersAndDeletes`（`:40-69`）：插入两条，按 `createdAt DESC` 排序，删除一条。
2. `repositoryRoundTripsAFavorite`（`:71-82`）：`FavoriteRepository.save` + `observeAll`。
3. `fileBackedDatabaseOpensAndPersists`（`:84-104`）：真实文件数据库，重启式打开/关闭。

运行条件：需要设备/模拟器（`AndroidJUnit4`）；本地 Pixel_8 报告通过（0.56s，`reported`）；CI 不运行。

### 2.4 `:app` — 0（审计时）

审计时：

- 没有 `app/src/test`。
- 没有 `app/src/androidTest`。
- `MainViewModel`、`MainUiState`、`Format`、Service、Tile、`MockPermissionChecker`、两个真实引擎、DI、AMapPicker/AMapPrivacy、Compose/theme 都未测。
- `ui-test-manifest` 只在 catalog 定义（`libs.versions.toml:71`），没有模块依赖；`ui-test-junit4` 未定义/未使用。

---

## 3. Phase 0 后当前状态（Current After Phase 0）

> 这一节描述 2026-10-29 Phase 0 测试 agent 完成后的工作树。计数来自源码 grep；通过/失败来自 `app/build/test-results/testDebugUnitTest/*.xml`。

### 3.1 当前总数 — 92 JVM

| 模块 | JVM 测试 | 测试类 | 类型 | CI 是否运行 |
|---|---:|---|---|---|
| `:core:geo` | 47 | 5 | 纯 JVM（JUnit 4） | 是（`verify` -> `test`） |
| `:core:location` | 15 | 3 | JVM + `kotlinx-coroutines-test` | 是 |
| `:core:data` | 2 | 1 | JVM | 是 |
| `:app` | **28** | **2** | 纯 JVM（JUnit 4） | **是**（`verify` -> `:app:testDebugUnitTest`） |
| **JVM 合计** | **92** | **11** | | |
| `:core:data` instrumented | 3 | 1 | `AndroidJUnit4` + Room | **否**（仍 local-only） |

### 3.2 `:app` 当前明细 — 28

| 测试类 | 测试数 | 覆盖内容 | 文件 |
|---|---:|---|---|
| `FormatTest` | 10 | `formatPoint` 的 5 位小数、负坐标、零、补零、舍入、边界、逗号+空格分隔、locale-independent 小数点 | `app/src/test/kotlin/com/snake/mobilespoofing/ui/FormatTest.kt` |
| `MainUiStateTest` | 18 | `canStart`（STATIC/ROUTE/waypoint 数）、`canStartSession`（权限、mock AppOp、Running、Idle/Error、route） | `app/src/test/kotlin/com/snake/mobilespoofing/ui/MainUiStateTest.kt` |
| **合计** | **28** | | |

通过证据（Phase 0 执行产物，本归档只读取）：

```text
app/build/test-results/testDebugUnitTest/TEST-com.snake.mobilespoofing.ui.FormatTest.xml
  tests="10" skipped="0" failures="0" errors="0"

app/build/test-results/testDebugUnitTest/TEST-com.snake.mobilespoofing.ui.MainUiStateTest.xml
  tests="18" skipped="0" failures="0" errors="0"
```

结论：

- `./gradlew :app:testDebugUnitTest` 不再是 no-op；它运行 28 个测试并全部通过（XML 证据）。
- `verify` 现在会运行 `:app:testDebugUnitTest`，因此 92 个 JVM 测试都在 `verify` 范围内。
- `:app` 仍然是纯 JVM 测试，没有 Robolectric/Compose UI 测试。
- 3 个 Room instrumented 测试没有变化，CI 仍不运行。

### 3.3 Phase 0 计划项的落地情况

| Phase 0 计划项 | 状态 | 证据 |
|---|---|---|
| `Format` 纯 JVM 测试 | **已完成** | `FormatTest.kt` 10 个测试；XML 通过 |
| `MainUiState`（`canStart`/`canStartSession`）纯 JVM 测试 | **已完成** | `MainUiStateTest.kt` 18 个测试；XML 通过 |
| module -> test count -> type 清单 | **已完成（本节）** | 64 -> 92 的时点对照 |
| `ui-test-junit4` | **未加**（Phase 2/3） | catalog 只有 `ui-test-manifest`，无模块依赖 |
| app 的 Service/Tile/ViewModel/Robolectric 测试 | **未做**（Phase 1/2） | 见第 4、5 节 |

---

## 4. Phase 1 计划测试（PLANNED — 尚未实现）

> 以下是 `06-roadmap.md` Phase 1 的验收测试计划。工作树中**还没有**这些测试；不要把它们计入 92。

| 类别 | 计划测试 | 目的 |
|---|---|---|
| `MockSessionRunnerTest` 虚拟时间 | hanging start / hanging stop / hanging push；timeout 不被当作 cancellation；`previous.join()` 阻塞；连续失败阈值；AppOp 撤销 | 验证 timeout-safe 启停与终止逻辑 |
| `MockSessionRunnerTest` 状态 | Starting/Stopping 转移；Stopping 期间 supersede 被拒绝；terminal vs Degraded；epoch reset；ViewModel 重建计时器 | 验证原子 `SessionState` |
| lease store fake | write-before-add；clear-only-after-confirmed-remove；冷启动清理与服务启动串行 | 验证 provider 租约 |
| `MockLocationService` / Tile（Robolectric） | service start/stop；通知 channel/拒绝；tile 状态；AppOp 复检 | 验证 Android 壳层 |
| `StopMockUseCase` | `startService` 失败不覆盖运行中状态 | 验证 Error 语义 |
| 坐标输入（纯 JVM） | `lat,lon` / `geo:` 解析；WGS84 datum；范围校验；不与 GCJ02 入口混用 | 验证 P1.8 |
| `SavedStateHandle` / ViewModel | 进程重建后 mode/point/route/speed/end behavior 恢复 | 验证 P1.11 |

标记：**PLANNED**。这些测试不存在于当前工作树；实现后应移到第 3 节并更新计数。

---

## 5. Phase 0 后仍未覆盖（Not Covered After Phase 0）

| 缺口 | 为什么现在没有 | 计划时点 |
|---|---|---|
| `MainViewModel` 测试 | 依赖 `Context`/`PowerManager`/`R.string`；需要先抽接口 | Phase 2（R16） |
| Service / Tile / 通知 / WakeLock 测试 | 需要 Robolectric/Hilt test 基建 | Phase 1（Robolectric） |
| `MockPermissionChecker`、真实引擎、`LocationExt` | Android 依赖；需要 Robolectric/设备 | Phase 1 |
| Compose UI / 截图测试 | 需要 `ui-test-junit4` + 稳定 preview | Phase 2/3 |
| 320dp/1.3x/RTL 截图 | 需要截图基建 + 目标矩阵 | Phase 3 |
| Macrobenchmark / baseline profile | 需要测量协议 | Phase 2 |
| Hilt test 基础设施 | 没有 `hilt-android-testing`/`HiltTestApplication`/custom runner | Phase 1/2 |
| Coverage 工具 | 没有选择工具/阈值 | Phase 2+ |
| Instrumentation in CI | 仍只记录"仅本地"或独立非阻塞 job | Phase 0/1 |

---

## 6. 重新计数命令（Re-count）

在 Git Bash（Windows）或 Linux 中：

```bash
# 每个模块的 @Test 数
grep -rho '@Test' core/geo/src/test | wc -l
grep -rho '@Test' core/location/src/test | wc -l
grep -rho '@Test' core/data/src/test | wc -l
grep -rho '@Test' app/src/test | wc -l

# instrumented
grep -rho '@Test' core/data/src/androidTest | wc -l

# app 测试类与通过状态
find app/src/test -name '*Test.kt'
for f in app/build/test-results/testDebugUnitTest/*.xml; do
  grep -o '<testsuite[^>]*' "$f" | head -1
done
```

PowerShell 等价（可选）：

```powershell
(Get-ChildItem app/src/test -Recurse -Filter *.kt | Select-String -Pattern '@Test' -AllMatches).Matches.Count
```

注意：`grep -c` 统计的是"包含 @Test 的行数"，不是匹配次数；用 `-o | wc -l` 更稳。当前测试每个 `@Test` 独占一行，两者结果一致。

---

## 7. 维护规则（Maintenance）

- 新增/删除测试后，更新第 0 节时点对照、第 1 节审计时基线（历史，不随当前改动）和第 3 节当前状态。
- 审计时基线（64 / 0）是历史快照，**不要**用当前 92 覆盖它；需要时在第 0/1 节并列。
- Phase 1 测试实现后，从第 4 节移到第 3 节，并更新 92 -> 新总数。
- 任何"测试通过"的声明必须附命令/XML/设备/日期；当前 app 28 个测试的通过证据是 `app/build/test-results/testDebugUnitTest/*.xml`，不是本轮重跑。
- 不要把 Robolectric/Compose UI 测试混进 JVM 计数；新增独立小节。
