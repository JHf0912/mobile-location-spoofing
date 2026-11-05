# 06 - 审计修正后的路线图（Audit-Corrected Roadmap）

**日期**: 2026-10-29
**状态**: 决策记录；Phase 0 + Phase 1 complete；final gate passed (with noted device limits)，2026-10-30
**来源**: Plan Agent 草案 + 独立审计修订 + 用户决策
**优先级规则**: 本文件的"审计修订"优先于草案；`05-audit.md` 的判定优先于本文件的阶段描述

本文件是执行入口。它不替代领域报告；实现细节仍以 `02`、`03`、`04` 的 `file:line` 证据为准。

> **执行状态提示**：Phase 0 的测试部分已完成（`app/src/test/` 的 `FormatTest` 10 + `MainUiStateTest` 18，共 28 个测试；XML 显示 failures=0/errors=0）。schema 导出、`lint.xml`、`settings.gradle.kts` 注释、preview 固定时钟也已在工作树出现，但未在本归档验证。本文件保留计划视图；`07-test-inventory.md` 同时记录审计时基线（64 / 0）与 Phase 0 后当前（92 / 28），完成状态以工作树/CI 为准。Phase 0 关闭记录见第 5 节末尾。**Phase 0 + Phase 1 complete；final gate passed (with noted device limits)，2026-10-30；最终验证见 `12-final-verification.md`。**

---

## 1. 路线图定位（Position）

三份探索报告 + 一轮独立审计的共同结论：

- **最高价值的真实问题**：
  1. 进程死亡后系统级 test provider 残留（R2）。
  2. 引擎 start/stop 无超时，fused 挂起可能阻塞 LM 清理（R6）。
  3. 无显式 Starting/Stopping，双击/磁贴可重入（R8）。
  4. 持续失败不终止，AppOp 撤销后永久错误循环（R7/R15）。
  5. 无地图设备无法选点，直接不可用（R18）。
  6. 1 Hz `Running` 时间戳导致全链路每秒工作（R1，性能而非正确性）。
- **审计否决/降级**：R1 不是正确性 bug；R3 不是当前崩溃；R11 "每次击键重组所有可见 item"被夸大；R21 的 keystore stale-input 很可能是误报、`org.gradle.tooling.parallel` 在 9.3.1 无效；R20 的 16KB 只是 ELF 证据，运行时失败未验证。
- **审计发现的关键实现陷阱**：`TimeoutCancellationException` 是 `CancellationException`；GMS Task 取消不会取消底层 Task；provider lease 在 `Application.onCreate` 清理会和服务启动竞态；bounded join 会重新引入旧 bug；`onMapPointSelected` 假定 GCJ02。
- **执行顺序**：Phase 0（验证/快赢）先做；Phase 1 拆"teardown ownership"和"session state"两个工作流，先设计再写码。不要在决策未记录前做 Room 迁移、路线收藏或产品功能。

---

## 2. 已确认决策（Confirmed Decisions）

| ID | 决策 | 选择 | 对计划的影响 |
|---|---|---|---|
| D1 | 本轮范围 | **Phase 0 + Phase 1** | Phase 2–4 只保留在路线图；本轮不做大规模 Compose/地图/产品改动 |
| D2 | 地图/坐标 | **中国优先 + 坐标兜底** | 保留 AMap/GCJ02；P1.8 只做独立的 WGS84 坐标输入/粘贴与 `geo:` 解析；不引入第二套地图、不做全球地理编码 |
| D3 | Room v1 | **已分发/不确定，按已分发处理** | 立即导出 v1 schema；任何后续 schema 变更必须写 v1 -> v2 migration + `MigrationTestHelper` 测试；禁止 destructive recreate；路线收藏/历史继续留在 Phase 4 |

### 2.1 未决事项的保守默认

| ID | 事项 | 本轮默认 | 重新决策时机 |
|---|---|---|---|
| D4 | 备份 | 保持现状 + README 披露；本轮不加密、不加 UI 开关 | Phase 4 |
| D5 | 运行中编辑 | 仍只影响下一次会话；P1.7 只同步 idle 编辑到 `SessionConfigStore` | Phase 1 设备验证后 |
| D6 | 320dp/1.3x | 本轮不做布局修复；Phase 1 设备矩阵记录实际表现；Phase 3 再修 | Phase 3 前明确目标设备/语言 |
| D7 | Play/16KB | 本轮不做 AMap 替换或 ABI 决策；坐标兜底保证无地图设备可用 | 决定上架 Play 前 |
| D8 | fused | 保留双引擎；不因"可能冗余"删除；先用 per-engine health 观察 | Phase 1 稳定后 |
| D9 | force-stop / 通知拒绝 | 实现租约清理与通知拒绝提示；产品接受度在 Phase 1 设备验证后记录 | Phase 1 设备验证后 |

### 2.2 仍需产品/分发输入

- 全球/坐标模式 vs 中国优先（本轮 D2 锁定；全球会影响 Phase 4 搜索与 16KB/ABI 战略）。
- 真实感（道路贴合、jitter、accuracy/altitude）vs 确定性测试（影响 P2.6/P4.3/P4.4 的优先级）。
- 备份加密/开关；运行中编辑是否实时生效；320dp/1.3x 的验收设备和语言。
- Play 分发/16KB；`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 的 Play 政策风险。
- fused 是否保留；force-stop 残留是否可接受；通知拒绝时磁贴作为停止兜底是否可接受。

---

## 3. 发现注册表（Finding Registry R1–R23）

状态含义：`CONSENSUS` = 多份报告/审计一致；`SINGLE-SOURCE` = 单份报告；`NEEDS-VERIFICATION` = 需要设备/复现；`MIXED` = 部分成立。

| ID | 发现 | 来源 | 状态 | 阶段 | 验收/证据 |
|---|---|---|---|---|---|
| R1 | `Running` 带 `elapsedRealtimeMs`，每秒不等 -> store/ViewModel/通知/Compose 全被唤醒 | B,C | CONSENSUS | 2 | 静态会话启动后最多一次状态发射；Compose 测试证明不整屏重组 |
| R2 | 系统 test provider 跨进程死亡存活；无持久租约/冷启动清理 | B,C | CONSENSUS | 1 | `dumpsys location`：force-stop/relaunch 后无残留；两台 OEM |
| R3 | Room v1 + `exportSchema=false`，无 schema/迁移测试 | A,C | CONSENSUS（deflated） | 0 | v1 JSON 存在；下一次变更必须有 v1 -> v2 migration test |
| R4 | 计时器在 ViewModel 推断；Error 保留旧起点，新 ViewModel 以最新 tick 为起点 | B,C | CONSENSUS | 1 | 虚拟时间：Error/重启/新 ViewModel 的 epoch 断言 |
| R5 | `SessionConfigStore` 只在 start 写入，`clear()` 无调用者 -> 磁贴可重启旧 spec | A,B | CONSENSUS | 1 | idle 编辑同步；stop 清理；磁贴读当前 spec |
| R6 | 引擎 start/stop 无超时；fused 先启动，teardown 在 NonCancellable 下 await 可能永不完成 | B | SINGLE-SOURCE（overstated） | 1 | 虚拟时间：hanging start/stop/push；LM 先启动；timeout 不被当作 cancellation |
| R7 | 瞬时失败与终止失败共用 `Error`；AppOp 撤销后永久错误循环 | B,C | CONSENSUS | 1 | 连续失败阈值 -> 终止 + teardown；Degraded/terminal 分离 |
| R8 | 无显式 Starting/Stopping；启动窗口双击/磁贴可再次启动 | B,C | CONSENSUS | 1 | 阶段转移测试；`canStartSession` 排除 Starting/Stopping/Running |
| R9 | `handleStop` 立即 `finishSession`，onDestroy 不 join teardown | B,C | NEEDS-VERIFICATION | 1 | Robolectric/真机：stop 后 teardown 完成；Stopping 期间拒绝新 start |
| R10 | 内联+全屏双 MapView；marker 每次编辑 O(N) 重建；polyline 10px；相机丢失 | B,C | CONSENSUS | 2 | 单实例；keyed diff；`10.dp.toPx()`；CameraPosition 保存 |
| R11 | `favoriteName` 提升到 MainScreen；收藏 forEach 不虚拟化 | C | SINGLE-SOURCE（overstated） | 2 | 击键不重组整屏；100 收藏只组合可见行 |
| R12 | 可访问性：地图预览无语义、无 heading/liveRegion、RouteControls 缺 selectableGroup、对比度/focus | C | SINGLE-SOURCE | 3 | TalkBack/axe/截图；语义断言 |
| R13 | 320dp+1.3x 英文可能溢出顶栏/底栏/同意卡；DESIGN.md 声称支持但无验证 | C | SINGLE-SOURCE | 3 | 320/360/1.3x/英文截图矩阵 |
| R14 | 通知拒绝后失去应用内停止入口；`ensureChannel` 每次 rebuild | B,C | CONSENSUS | 1/2 | checklist 通知行；磁贴兜底；`ensureChannel` 移到 onCreate |
| R15 | provider 被抢/AppOp 撤销后不重注册、不复检 | B | SINGLE-SOURCE | 1 | 全 provider 失败 -> 有界重注册 + 新鲜 AppOp；限流 |
| R16 | 审计时 `:app` 无测试；ViewModel Context-bound + final 依赖；无 Hilt/Robolectric 测试基建 | A,C | CONSENSUS | 0/2 | Phase 0 已补 28 个纯 JVM 测试；Phase 2 接口/测试 seam；Phase 1 Robolectric |
| R17 | CI 三次独立 Gradle 调用；不跑 instrumentation/lintVital；不上传 lint/mapping/APK | A | SINGLE-SOURCE（deflated） | 0 | CI 上传 artifacts；lintVital；instrumentation 独立 job 或记录本地 |
| R18 | 无地图设备无法选点/收藏/开始会话 | B,C | CONSENSUS | 1 | WGS84 坐标输入/paste/`geo:`；x86 AVD 可选点并开始 |
| R19 | 收藏仅单点；路线收藏不可用；无唯一索引；Room 错误会永久终止收藏流 | C | SINGLE-SOURCE | 4 | schema 决策后：路线 entity/JSON + migration；收藏重试 |
| R20 | AMap 10.0.600 ELF 4KB 对齐，16KB 设备风险未验证 | B | NEEDS-VERIFICATION | 0/4 | 16KB emulator/真机加载结果；Play 分发决策 |
| R21 | 构建声明：AGP↔Gradle 耦合、keystore 配置缓存、Material profile、tooling.parallel | A | MIXED | 0/avoid | 两次构建复现；删除/标注 tooling.parallel；不引未复现 profile 数 |
| R22 | 进程死亡丢失 mode/点/路线/速度/到达行为 | B,C | CONSENSUS | 1 | `SavedStateHandle` + 路线长度限制；进程重建测试 |
| R23 | `GeoPoint` 默认 GCJ02，注入要求 WGS84，`toLocation` 不校验；UI 无 datum 标签 | A,C | CONSENSUS | 1 | datum-explicit 入口；范围校验；Hero/地图 datum 标签 |

### 3.1 去重说明

- R1 与 R4 是不同根因：去掉时间戳解决重组，不解决错误起点；移动起点也不解决 1 Hz equality。
- R2/R5/R8/R9/R22 是同一个会话生命周期工作流；先做 provider 租约，因为进程被杀时它是唯一兜底。
- R3 是 R19 的前置门槛，不是功能。
- R16 是 Phase 2 的前置，不是"先多写测试"；必须先解耦 ViewModel。

---

## 4. 依赖关系（Dependencies）

```text
D1-D3 (decisions)
  |
  +--> Phase 0
  |      P0.1 test inventory ----+
  |      P0.2 schema export -----+--> Phase 4 (schema changes)
  |      P0.3 CI artifacts ------+--> release gates
  |      P0.4 docs --------------+
  |      P0.5 quick fixes -------+
  |
  +--> Phase 1
         A1 lease + cold cleanup ----+
         A2 timeout-safe start/stop -+--> A3 terminal failure / Degraded / AppOp
         B1 atomic SessionState -----+--> B2 epoch --> B3 notification/tile/stop
         B4 process/diagnostics -----+
         C1 coordinate fallback (independent, datum-explicit)
         C2 notification denial / docs
         C3 data/backup current
  |
  +--> Phase 2 (needs Phase 1 status model stable)
  |      P2.1 equality/epoch
  |      P2.2 stable state/actions/favorites
  |      P2.3 map single instance/keyed diff
  |      P2.4 notification/tile
  |      P2.5 motion/timer lifecycle
  |      P2.6 startup/baseline/ABI (measure first)
  |      P2.7 measurement protocol
  |
  +--> Phase 3 (needs Phase 2 preview stability + test deps)
  |      P3.1 320dp/1.3x
  |      P3.2 map semantics/alternative
  |      P3.3 heading/liveRegion/selectableGroup
  |      P3.4 contrast/focus/night map
  |      P3.5 plurals/RTL/IME
  |      P3.6 validation
  |
  +--> Phase 4 (needs P0.2 + D3 + product decisions)
         P4.1 route favorites
         P4.2 save current position
         P4.3 GPX/KML
         P4.4 geocoding
         P4.5 history/profiles/shortcuts/onboarding
         P4.6 feature flags
```

硬依赖：

- Phase 1 的 A/B 设计必须先于实现；A 稳定后再合并 B 到同一状态流。
- Phase 2 依赖 Phase 1 的 `SessionState`/epoch 稳定；否则会二次改状态模型。
- Phase 3 依赖 Phase 2 的稳定 preview 和 Compose 测试依赖（审计指出的矛盾）。
- Phase 4 依赖 P0.2 schema JSON + D3 的 migration 策略。
- 任何 schema 变更都依赖 v1 -> v2 migration test；没有例外。

---

## 5. Phase 0 — 验证与快速收益（本轮执行）

**目标**：让项目可度量，防止回归，解锁后续阶段。Phase 0 只做便宜、可逆、不改变运行时行为的项。

### P0.1 测试/Instrumentation 盘点（测试部分已完成，2026-10-29）

- 新增 `app/src/test/kotlin/...`，先覆盖纯 JVM 的 `Format`、`MainUiState`（`canStart`/`canStartSession`）和 UI 无关 helper。
- 生成 module -> test count -> type 清单，防止计数漂移（审计时 64；Phase 0 后 92）；记录 3 个 Room instrumented 测试和 Pixel_8 结果。
- `ui-test-junit4` 等 Phase 2/3 再加；本轮不加空测试框架。
- 测试明细（审计时基线、Phase 0 后当前、Phase 1 计划）见 `07-test-inventory.md`。

**验收**：`:app:testDebugUnitTest` 已不再 no-op（28 个测试，XML failures=0/errors=0）；新增测试在 `verify` 中运行；计数清单与 `07` 一致。

### P0.2 Room v1 schema 导出

- `core/data/build.gradle.kts` 加 KSP `room.schemaLocation = $projectDir/schemas`。
- `SpoofingDatabase.kt` 改 `exportSchema = true`。
- 提交 `core/data/schemas/com.snake.mobilespoofing.core.data.SpoofingDatabase/1.json`（实际路径以生成结果为准）。
- 因为 D3 按"已分发/不确定"处理：**从下一次 schema 变更起**必须写 v1 -> v2 migration + `MigrationTestHelper` 测试。
- 本轮**不加**空的 room-testing 骨架，不做 destructive recreate。

**验收**：schema JSON 存在且与 v1 实体一致；`assembleDebug verify` 通过；没有运行时行为变化。

### P0.3 CI / release 冒烟

- CI 加 `:app:lintVitalRelease`。
- 上传 `**/build/reports/lint-results*`、`**/build/outputs/mapping/release/mapping.txt`、release APK。
- 明确 CI 产出未签名 release；运行时 release 验证需要 ARM 真机 + AMap key。
- instrumented job 用 GMD/ATD，或明确记录"仅本地"政策；不要假装 CI 覆盖了设备。
- 暂保留三次 Gradle 调用，等单次调用实验证明等价再合并。

**验收**：CI artifact 可下载；lint/mapping/APK 至少各一份；instrumentation 的状态被明确记录。

### P0.4 文档漂移

- 修正 `settings.gradle.kts:21` 的依赖关系注释（本轮不修改构建文件；在文档/后续 Phase 记录）。
- 对齐 README 测试列表与 `07-test-inventory.md`。
- `DESIGN.md:76` 的 320dp/1.3x 在验证前写"目标"。
- 删除或接入未使用的 `map_fullscreen`（`values/strings.xml:33`、`values-en/strings.xml:33`）。
- 记录诊断命令：`dumpsys location`/`appops`/`dumpsys activity services`/`dumpsys power`/`batterystats`（见 `01` 第 6 节）。

**验收**：README/DESIGN 不再声称未验证的 320dp 支持；文档索引 `docs/optimization/2026-10-29/00-index.md` 可访问。

### P0.5 小而确定的修复

- `MainScreenPreviews.kt:16` 的 `SystemClock.elapsedRealtime()` 换成稳定值，为截图测试做准备。
- lint 关闭 `NewerVersionAvailable`，使 CI hermetic；不要全局开 `warningsAsErrors`。
- `org.gradle.tooling.parallel` 删除或标注 "Gradle 9.4+"；当前 9.3.1 无效。
- keystore 改用与 AMAP_API_KEY 相同的 Provider API：**先做两次构建复现**，确认是真问题再改；不作为安全修复宣传。
- 暂不动 token/模块边界/依赖。

**验收**：preview 可重复；lint 不依赖网络；`tooling.parallel` 不再误导；keystore 结论有复现记录。

### Phase 0 退出标准

```text
./gradlew :app:assembleDebug verify lint :app:assembleRelease
```

必须通过；并且：

- `core/data` schema JSON 存在；
- CI 上传 lint/mapping/APK；
- release APK 在 ARM 真机能安装启动（记录，不要求自动化）；
- `git status` 无构建产物/`tmp/` 新增；
- `07-test-inventory.md` 的"现有/计划"标记与实际一致。

### Phase 0 关闭记录（2026-10-29）

审计结论（`05-audit.md` 保持审计时记录，不追写）：

| 项 | 结果 | 备注 |
|---|---|---|
| Schema 导出 | **CONFIRMED** | v1 JSON 已生成 |
| CI `lintVitalRelease` / artifacts | **CONFIRMED** | lint/mapping/APK 上传 |
| lint hermeticity | **CONFIRMED** | `NewerVersionAvailable` 已关闭 |
| 确定性 preview | **CONFIRMED** | 固定时间源 |
| 28 个 app 测试 | **CONFIRMED** | `FormatTest` 10 + `MainUiStateTest` 18；XML 通过 |
| `map_fullscreen` | **移除中** | 未使用；lint warning |
| keystore / 配置缓存 | **无 Phase 0 代码改动** | stale-input 在两次构建复现前按"很可能误报"；密码静态存储 plausible/low severity，后续 build-hygiene pass 再看 |
| `tooling.parallel=true` | **no-op on 9.3.1** | 用户本地未提交行；保留并记录，等 wrapper 9.4+ |
| Phase 0 delta / commit | **与既有 UI/session 改动混合** | 用户要求提交时拆分 baseline 与 Phase 0 |
| release build | **重跑中** | 结果报告后再记录，不预写结论 |

---

## 6. Phase 1 — complete；final gate passed (with device limits)

**目标**：让会话生命周期能扛进程死亡、provider 冲突、权限撤销和慢引擎。

**实现契约**：`08-phase1-design.md`（Phase 1 的五条不变量、`SessionState`、lease、engine contract、runner/service、坐标兜底、测试矩阵、文件级顺序）。本节是路线图摘要；与 `08` 冲突时以 `08` 为准。当前状态：1A/1B/1C 实现与审计完成；final gate passed with noted device limits（Pixel_8 x86_64 AVD only；ARM base map 与 two-OEM matrix 未跑；见 `12-final-verification.md`）。

### 6.1 Workstream A：teardown ownership（最高危）

#### A1 Provider lease + 冷启动清理

- 在 `addTestProvider` 前写持久租约（SharedPreferences/DataStore），记录 session id、开始时间、provider 名、package。
- 只有成功移除后才清除租约。
- `SpoofingApp.onCreate` 后台检查：租约存在且无活跃会话 -> `LocationManagerMockEngine.clearStaleProviders()`。
- 服务在 `startForeground` 前标记活跃，`finishSession` 在 teardown 后清除。
- **必须加进程级 gate**：清理与服务 `handleStart` 串行化，避免清理删掉新会话的 provider。
- provider 名是全局的；租约是启发式，不能区分其他 mock app；记录残余冲突风险。
- `stopMockMode` 改为返回 per-provider 结果/health，不能只清 `touched`。
- force-stop 不会调 `onTerminate`；只能下次启动清理，需产品接受度（D9）。

**验收**：force-stop/relaunch、kill -9、LMK 后 `dumpsys location` 无残留；新会话启动不被冷启动清理误删；lease write-before-add 测试。

#### A2 Timeout-safe 引擎启停

- **不要直接用 `withTimeout`**：`TimeoutCancellationException` 是 `CancellationException`，现有 rethrow 会跳过 teardown/`onFinished()`。
- 用 `withTimeoutOrNull` 或先 catch `TimeoutCancellationException` 再按失败处理；一个引擎超时不能中止其余引擎的 stop。
- LM 先启动，stop 反向（先 fused 后 LM，或按启动逆序）。
- 每个引擎独立超时（建议 start 5s、stop 2s；具体值实现时定）。
- 加 per-push 超时；fused `setMockLocation` 挂起不能阻塞 LM。
- GMS Task 超时后加 generation guard + stop 重试，防止晚到的 `setMockMode(true)`。
- `previous?.join()` 不能无界阻塞；超时后停在 `Stopping` 并拒绝新 start，而不是继续启动。

**验收**：虚拟时间测试覆盖 hanging start/stop/push、timeout 不被当作 cancellation、`previous.join()` 阻塞、Stopping 期间 supersede。

#### A3 终止失败 / Degraded / AppOp 复检 / provider 冲突

- 连续 push/start 失败达到阈值 -> 终止并清理。
- 每 N tick 复检 mock AppOp（把 `MockPermissionChecker` 抽成接口 + Android 实现）。
- 全 provider 失败时做一次有界 backoff 的 remove/add/enable 重注册 + 新鲜 AppOp 检查；限流，防止永久撤销导致注册风暴。
- 暴露 per-engine health（fused vs LM）。
- 修 `StopMockUseCase` 在 `startService` 失败时覆盖运行中状态的问题。
- 启动时把旧 Error 重置为中性状态，避免重发旧错误通知。

**验收**：撤销 AppOp 后会话终止并清理；provider 被第二个 mock app 占用时能恢复或明确失败；per-engine health 可见。

### 6.2 Workstream B：session state（与 A 并行设计，A 稳定后合并）

#### B1 原子 `SessionState`

- 把 phase（Idle/Starting/Running/Degraded/Stopping/Error）和 telemetry（point、sessionEpoch）放在同一个 `MockStatusStore`/sealed state，避免 UI 看到"Running phase + Idle telemetry"。
- 转移矩阵显式化：

```text
Idle -> Starting -> Running/Degraded -> Stopping -> Idle
                 \-> Error (terminal)
Degraded -> Running (recovered) / Error (terminal)
Stopping -> Idle (teardown complete)
```

- `canStartSession` 排除 Starting/Stopping/Running，允许 Idle/terminal Error。
- `canStop` 在 terminal Error 下禁用。
- tile/通知/`requestListeningState` 全部跟随 phase。
- 启动时把旧 Error 重置为中性状态。

**验收**：阶段转移虚拟时间测试；双击 Start 只启动一次；Stopping 期间 Start 被拒绝；terminal Error 下 Stop 不可点。

#### B2 Session epoch

- `MockStatus.Running(pointWgs84, sessionEpoch)`，epoch 整个会话恒定（不是 per-tick timestamp），供 `rememberElapsedLabel` 使用。
- Idle/terminal Error 清除 epoch，Degraded 保留。
- 这同时修复 R1（静态会话只发一次）和 R4（计时器错误）。

**验收**：静态会话最多一次状态发射；计时器仍每秒走；ViewModel 重建后计时器不归零；失败重启不继承旧起点。

#### B3 通知 / 磁贴 / 停止

- `ensureChannel()` 移到 `onCreate`。
- 停止时进入 `Stopping`，teardown 完成后回 Idle。
- `onDestroy` 做 best-effort 等待但不无限阻塞；进程被杀时 A1 是真正保护。
- 通知拒绝（`POST_NOTIFICATIONS`）在 checklist/服务里提示；QS 磁贴作为停止兜底。
- tile 观察 phase store，而不是立即读 `runner.isRunning`；start/stop 后请求刷新。

**验收**：Robolectric 覆盖 service start/stop、通知 channel、tile 状态；通知拒绝时有可见提示。

#### B4 进程/诊断

- `onTaskRemoved` 决策（记录行为，不静默）。
- debug 结构化日志/环形缓冲：start/stop 延迟、per-engine 失败、tick jitter、teardown 时长。
- `SavedStateHandle` 保存 mode/point/route/speed/end behavior（限制路线长度）。
- 修正 `observeFavorites` 的永久终止、`id` 二级排序、保存成功前不清空输入。

**验收**：进程重建后编辑状态恢复；收藏流异常后能重试；保存失败保留输入。

### 6.3 独立项 C（可与 A/B 并行）

#### C1 坐标兜底（中国优先）

- 新增 `CoordinateInput`，解析 `lat,lon`、`geo:lat,lon` 和粘贴坐标。
- 显式标注 datum：`geo:` 和粘贴 `lat,lon` 按 **WGS84**；`onMapPointSelected` 是 GCJ02，**不能复用**。
- 范围校验（纬度 -90..90、经度 -180..180）；显示 datum；增加"中心 pin"选择。
- x86/AMap 失败设备也能选点/收藏/开始。

**验收**：x86_64 AVD 上无地图也能选点并开始会话；`geo:`/粘贴不产生 GCJ02->WGS84 二次偏移；范围非法时明确报错。

#### C2 通知拒绝与文档

- checklist 增加通知行；服务在通知关闭时警告；QS 磁贴作为停止兜底。
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 的 Play 政策记录在 README（侧载可接受）。

**验收**：Android 13+ 拒绝通知时有可见提示；README 有政策说明。

#### C3 数据/备份

- 保持现有备份 + README 披露；本轮不做加密/开关。
- 路线收藏/历史仍留 Phase 4。

**验收**：备份规则不变；README 描述与 `backup_rules.xml`/`data_extraction_rules.xml` 一致。

### Phase 1 测试/验收

- **虚拟时间**：hanging start/stop/push；timeout 不能被当作 cancellation；`previous.join()` 阻塞；lease write-before-add/clear-only-after-confirmed-remove；Starting/Stopping 下 supersede；terminal vs Degraded；epoch reset；ViewModel 重建计时器。
- **Robolectric**：service start/stop、通知 channel/拒绝、tile 状态、AppOp 复检。
- **ARM 真机矩阵**：`am force-stop` 后立即/重启后 `dumpsys location`；`am kill`/LMK；撤销 AppOp；第二个 mock app 占用 gps/network；通知拒绝；tile start/stop；至少两个 OEM。
- **构建**：`assembleDebug verify lint assembleRelease`；Room instrumented 本地通过；release APK 在 ARM 真机安装启动。
- **报告归档**：更新 `docs/optimization/2026-10-29/`；`tmp/` 不入库。

### Phase 1 退出标准

- provider 清理：`dumpsys location` 在 force-stop/relaunch 后无残留（至少两台 OEM；至少一次 kill -9）。
- AppOp 撤销：会话终止并清理，不再永久 Error 循环。
- Starting/Stopping：双击/磁贴重入被拒绝；Stopping 期间不启动新会话。
- epoch：静态会话最多一次状态发射；计时器正确。
- 坐标兜底：x86 AVD 可完成"选点 -> 收藏 -> 开始 -> 停止"。
- 构建：`assembleDebug verify lint assembleRelease` 通过；instrumented 本地通过。
- 不得在没有 `dumpsys` + 双 OEM 证据时声称"已修复"。

> **Final gate actual (2026-10-30)**：Pixel_8 x86_64 AVD passed build/lint/JVM/Room/E2E; ARM base-map and two-OEM matrix were **not run** (`needs-device`). See `12-final-verification.md`; do not treat the ARM bullet as passed.

---

## 7. Phase 2 — 性能、耗电、Compose（本轮不执行）

### P2.1 Running equality / 拆分遥测

- 从 `Running` equality 去掉 `elapsedRealtimeMs`（或只在 point 变化时 emit `Running(point)`）。
- **审计修正**：用 `Running(pointWgs84, sessionEpoch)`（epoch 整个会话恒定），不要拆两个时钟。
- elapsed 走独立 clock/flow；静态会话只发一次，路线会话只在坐标变化时发。
- 同步更新 store/runner/ViewModel/通知/StatusHero。
- 补 emission-count 和 ViewModel 重建计时器测试。

**退出标准**：静态会话启动后最多一次状态发射；计时器仍每秒走；路线 tick 仍渲染。

### P2.2 稳定 state/actions/收藏

- `MainUiState` 标 `@Immutable` 或拆成 `EditorState`/`Capabilities`/`SessionState`/`FavoritesState`/`Message`，用不可变集合。
- `favoriteName` 下沉到 `FavoriteSection`/`rememberSaveable`。
- `MainActions` remember 或传稳定方法引用。
- 收藏改成独立 keyed LazyColumn items + `animateItem()`。
- **审计修正**：用 Compose 测试验证 100 收藏只组合可见行，不要只靠 prose；`formatPoint` 只对可见行跑。

### P2.3 地图单实例 + keyed diff

- 全屏 Dialog 打开时不再组合内联 `AMapPicker`（显示静态预览/占位），关闭后恢复。
- marker/polyline 改 keyed diff，宽度 `10.dp.toPx()`，保存 `CameraPosition`。
- 加实时模拟位置 marker（只在 fix 变化时更新）。
- 测量重建内联地图的闪烁/成本。

### P2.4 通知与磁贴

- `ensureChannel()` 移到 `onCreate`。
- start/stop 后调 `TileService.requestListeningState`。
- 磁贴观察 phase store，而不是立即读 `runner.isRunning`。

### P2.5 动效与计时器生命周期

- `rememberMotionEnabled` 监听 `ANIMATOR_DURATION_SCALE` 变化。
- 不在运行/不可见时停 `StatusDot` 无限动画。
- 计时器用 lifecycle-aware repeat，而不是 composition-scoped 1 Hz `LaunchedEffect`。
- 不要用"删掉 StatusDot 动画"来修它；它已经在 `graphicsLayer` 内读 alpha。

### P2.6 启动 / baseline / ABI

- 先测冷启动是否满足 3s。
- 为 app 类加 curated `baseline-prof.txt` 或 Macrobenchmark `BaselineProfileRule`；注意当前 profile 由库主导（`reported`）。
- 确认 AMap 两个 ABI 后再加 ABI split/App Bundle（主要改善分发，不一定减少 ARM 安装体积）。
- **审计修正**：不要引用未复现的 4,478 profile 条目；先测启动，再决定 baseline/macrobenchmark。

### P2.7 测量协议

- 耗电：息屏静态 1 小时，前后 `dumpsys batterystats`/Battery Historian 对比。
- Compose：compiler stability report + layout inspector。
- 启动：Macrobenchmark。
- 不要只凭算术声称省电。

### Phase 2 退出标准

- 静态会话启动后最多一次状态发射；计时器仍每秒走；路线 tick 仍渲染。
- Compose UI 测试证明 100 收藏不全组合；preview 稳定。
- Macrobenchmark 启动达标；耗电记录在案。

---

## 8. Phase 3 — 可访问性、适配、本地化（本轮不执行）

### P3.1 320dp/1.3x 布局

- `AppTopBar` 标题列加权，允许状态 pill 换行。
- 窄宽阈值下 `BottomActionBar` 按钮堆叠。
- 同意卡 intrinsic Row 改 FlowRow/Column。
- 全屏 Dialog header 可缩放；Scaffold/content 加 `imePadding()`。
- 加 320/360/1.3x/英文宽度矩阵测试。

### P3.2 地图语义与替代

- 预览加 role/label/`onClickLabel`。
- 全屏地图增加"输入坐标""当前位置"动作。
- 不可用/占位状态可操作；接入 `map_fullscreen`。

### P3.3 Heading / liveRegion / selectableGroup

- `SectionHeader` 加 `semantics { heading() }`。
- 错误/状态文本加 `liveRegion`；状态 pill 加 `stateDescription`。
- `RouteControls` 两个 FlowRow 加 `selectableGroup()`。
- setup chevron 的重复 `contentDescription` 改为 `onClickLabel`/state 语义。

### P3.4 对比度 / focus / 夜间地图

- 调整用于文字/选中边框的 accent token，满足 3:1 非文本、4.5:1 文本。
- 自定义 chip 加可见 focus。
- 深色主题切 `AMAP_MAP_TYPE_NIGHT`，验证同意/占位状态。

### P3.5 Plurals / RTL / IME

- waypoint/count 字符串改 `<plurals>`（values + values-en）。
- 坐标、"km/h"、" · " 用 `BidiFormatter` 隔离为 LTR run。
- 加 RTL/pseudo-locale 布局测试；验证底部收藏输入框键盘避让。

### P3.6 验证

- Compose UI 测试用 `LocalDensity` 设置 font scale。
- 320dp/1.3x/dark/RTL 截图测试；Espresso 可访问性检查；记录 TalkBack 手测。
- 稳定 preview 是前置；测试依赖必须在 Phase 2 开始前加（审计修正）。

### Phase 3 退出标准

- 320dp/1.3x/dark/RTL 截图和可访问性检查通过。
- 地图替代输入可用；TalkBack 能完成"选点 -> 开始 -> 停止"。

---

## 9. Phase 4 — 产品与功能（本轮不执行）

### P4.1 路线收藏

- 依赖 Phase 0 schema 导出 + D3 migration 策略。
- 路线收藏模型（独立 entity 或 JSON column）存 waypoints/speed/end behavior。
- DAO/repository/UI + migration test。
- 在 schema 决策前不要启用当前不可达的路线收藏路径。

### P4.2 保存当前运行位置

- 依赖 Phase 1 status 暴露当前 fix。
- 仅在运行中显示"保存当前"，明确 datum 标签。

### P4.3 GPX/KML 导入导出

- SAF；parser 放 `core:geo`（纯 JVM 可测）。
- 校验坐标范围、限制文件大小/waypoint 数、禁用外部实体。
- 只在显示边界做 WGS84 -> GCJ02；从路线收藏或当前路线导出。
- 审计：GPX 比地理编码价值更高，且不需要第二套地图栈。

### P4.4 搜索/地理编码

- 依赖中国/全球 + 坐标模式决策。
- AMap geocoder 是中国路径；全球需要另一 provider 和 WGS84 输出。
- 网络/隐私/限流是一等公民。

### P4.5 历史 / Profiles / 快捷方式 / Onboarding

- 历史需要 Room v3 + 保留/删除规则。
- profiles 存 mode/speed/end behavior。
- launcher shortcuts 深链 profiles/favorites。
- onboarding 覆盖权限 rationale、通知拒绝、电池"建议而非必需"。

### P4.6 功能开关

- 每个功能由决策 + migration test 把关。
- 路线收藏/历史不能在 v1 迁移策略确定前发布。

### Phase 4 退出标准

- 每个新 schema 有 migration test 和备份/回滚路径。
- 路线收藏/历史在 v1 -> v2 迁移策略确定后才发布。

---

## 10. 决策门禁（Decision Gates）

| 门禁 | 时机 | 必须记录 |
|---|---|---|
| G0 | Phase 0 前 | D1-D3 已确认（本轮已完成） |
| G1 | Phase 1 写码前 | A/B 状态模型设计；provider lease 语义；force-stop 接受度；Stopping 策略；datum 输入契约；通知拒绝兜底 |
| G2 | Phase 2 前 | 状态模型稳定；全球/中国（影响 Phase 4）；真实感 vs 确定性（影响 P2.6/P4）；备份策略（影响 P4） |
| G3 | Phase 3 前 | 320dp/1.3x 目标设备和语言；截图/axe 基建；测试依赖已加 |
| G4 | Phase 4 前 | Room v1 分发确认；migration 策略；路线收藏 schema；备份/回滚 |
| G5 | Play 发布前 | 16KB/AMap；`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 政策；fused 保留决策；force-stop 残留接受度 |

任何门禁未记录，不得进入下一阶段。决策记录写在本文件第 2 节或追加"变更控制"。

---

## 11. 先不要做 / 避免（Do Not Do）

### 11.1 架构与状态

- 不要在 Phase 0 测试盘点和 Phase 1 fakes 之前重写 runner/engine 架构。
- 不要在 app 测试和 DI seam 之前拆 `:core:location` 或改模块边界。
- 不要把 `MockStatus.Error` 当单一状态；terminal/degraded 分离是 UI retry/dismiss 的前置。
- 不要用 naive `withTimeout` 包引擎调用（见 `05` 陷阱 1）。
- 不要在 `Application.onCreate` 无 gate 清理 provider（陷阱 3）。
- 不要用 GCJ02 入口接 WGS84（陷阱 5）。
- 不要 bounded join 超时后继续启动（陷阱 4）。
- 不要把 lease、session epoch、last spec 混成一个 store（陷阱 6）。

### 11.2 构建与分发

- 不要在视觉验证 XML theme/edge-to-edge/status bar/splash 之前移除 Material Components。
- 不要在坐标模式决策前加 MapLibre 或第二套地图栈。
- 不要在 16KB emulator/真机验证前对 16KB 结论采取行动；ELF 对齐证据不等于运行时失败。
- 不要在测量分发前加 ABI split 作为体积优化；它不减少 ARM 安装体积。
- 不要在 v1 没有 schema 导出时加 Room KMP/2.8 特性或第二个数据库。
- 不要在 v2 前加 room-testing 骨架（P0.2 审计修订）。
- 不要在优化期间追 AGP 9.2/Gradle 9.4；Android Studio 2025.3 锁 AGP 9.1.0，当前组合已验证。
- 不要在 18 条 lint warning 分诊前全局开 `warningsAsErrors`。
- 不要把 `org.gradle.tooling.parallel` 当有效优化；9.3.1 上 no-op。

### 11.3 性能与测量

- 不要在 P2.7 电池协议前移除 WakeLock 或改超时。
- 不要用"删掉 StatusDot 动画"来修它；它已经在 `graphicsLayer` 内读 alpha，应该修可见性/动效偏好。
- 不要用时间相关 preview（`MainScreenPreviews.kt:16` 的 `SystemClock.elapsedRealtime()`）加截图测试；先稳定 preview。
- 不要只凭算术声称省电；要实测。
- 不要引用未复现的 4,478 profile 条目 / stdlib 2.3.21 作为路线图依据。

### 11.4 产品与数据

- 不要在备份策略决策前加云同步/备份控制。
- 不要在 schema 决策前用当前 point-only `FavoriteItem` 构建路线收藏。
- 不要在没有 v1 -> v2 migration test 时发布任何 schema 变更。
- 不要在没有 `dumpsys location` + 双 OEM 证据时声称 provider 清理"已修复"。

---

## 12. 报告阅读顺序（Reading Order）

```text
完整：
00 -> 01 -> 02 -> 03 -> 04 -> 05 -> 06 -> 08 -> 09/10/11 -> 12
                                  \
                                   +-> 07 (测试盘点)

Phase 0 最短路径：
00 -> 01 -> 05 -> 06 -> 07

Phase 1 最短路径：
00 -> 01 -> 05 -> 06 -> 08 -> 09/10/11 -> 12 -> 07（测试基线）
```

- 执行前必须读 `05-audit.md`，否则会重复草案的错误（timeout/坐标/lease）。
- 领域实现读对应报告：`02` 构建/测试，`03` 运行时，`04` UI/数据/产品，`08` Phase 1 实现契约，`09/10/11` 为 1A/1B/1C 审计，`12` 为最终 gate/限制。
- `06` 是决策记录；审计修订条目优先于草案；Phase 1 设计以 `08` 为准。
- **Phase 1 当前状态：1A/1B/1C 实现与审计完成；final gate passed with noted device limits（x86_64 AVD only；ARM/OEM 未跑）。**

---

## 13. 总体发布门禁（Release Gates）

| 阶段 | 门禁 |
|---|---|
| Phase 0 | 测试盘点完成；schema JSON；CI artifacts；release 冒烟记录 |
| Phase 1 | **PASS with noted device limits**: Pixel_8 x86_64 AVD build/lint/JVM/Room/E2E; ARM base-map and two-OEM matrix not run (`needs-device`); see `12-final-verification.md` |
| Phase 2 | 静态会话只发一次；启动/耗电测量记录；截图 baseline 稳定 |
| Phase 3 | 320dp/1.3x/dark/RTL 截图和可访问性检查通过 |
| Phase 4 | 每个新 schema 有 migration test 和备份/回滚路径 |

---

## 14. 验收追溯（Traceability）

| 发现 | 验收测试/证据 | 阶段 |
|---|---|---|
| R1 | emission-count 测试；Compose 重组测试；静态会话 1 次发射 | 2 |
| R2 | `dumpsys location` + force-stop/relaunch + 双 OEM | 1 |
| R3 | v1 JSON；v1 -> v2 migration test（下次变更） | 0/4 |
| R4 | 虚拟时间：Error/重启/新 ViewModel epoch | 1 |
| R5 | idle 编辑同步；stop 清理；磁贴当前 spec | 1 |
| R6 | hanging start/stop/push 虚拟时间；LM 先启动 | 1 |
| R7 | 连续失败终止；Degraded/terminal | 1 |
| R8 | 阶段转移；双击/磁贴拒绝 | 1 |
| R9 | Robolectric/真机 stop teardown；Stopping gate | 1 |
| R10 | 单实例；keyed diff；dp 宽度；CameraPosition | 2 |
| R11 | 击键不重组整屏；100 收藏只组合可见行 | 2 |
| R12 | TalkBack/axe/语义断言 | 3 |
| R13 | 320/360/1.3x/英文截图矩阵 | 3 |
| R14 | 通知拒绝提示；checklist 行；tile 兜底 | 1/2 |
| R15 | AppOp 复检；provider 重注册；限流 | 1 |
| R16 | app 测试；接口/测试 seam；Robolectric | 0/1/2 |
| R17 | CI artifacts；lintVital；instrumentation 状态 | 0 |
| R18 | x86 AVD 坐标输入/开始会话 | 1 |
| R19 | 路线收藏 schema + migration；收藏重试 | 4 |
| R20 | 16KB emulator/真机加载结果 | 0/4 |
| R21 | 两次构建复现；tooling.parallel 清理 | 0 |
| R22 | `SavedStateHandle` 进程重建 | 1 |
| R23 | datum-explicit 入口；范围校验；datum 标签 | 1 |

---

## 15. 风险与回滚（Risk & Rollback）

| 变更 | 风险 | 回滚 |
|---|---|---|
| Provider lease | 冷启动清理误删新 provider；全局 provider 名冲突 | 进程 gate + 只清租约匹配的 provider；保留 `dumpsys` 证据；功能开关可关 |
| 引擎超时 | 超时被当取消；晚到 setMockMode(true) | timeout-safe 包装 + generation guard；虚拟时间测试；保留旧路径开关 |
| Degraded/terminal | UI 状态变化导致误判 | 先在 store/runner 层加状态，UI 分步接入；保留 Error 兼容映射 |
| SessionState 原子化 | 迁移期双源不一致 | 一次只改一个 store；旧 API 适配层；测试覆盖转移矩阵 |
| Room schema 导出 | 生成路径/实体不一致 | 纯增量；删 JSON 即可回滚；不改变运行时 |
| CI lintVital/artifacts | 构建时间/成本 | job 可独立关闭；不阻塞现有 verify |

回滚原则：Phase 0 的改动必须可逆且不改变运行时；Phase 1 的 provider/超时改动必须有功能开关或"旧路径"回退，且在没有设备证据前不删除旧逻辑。

---

## 16. 变更控制（Change Control）

- D1–D3 已确认；改变必须在本文件追加记录（日期、原因、影响阶段）。
- 未决事项按第 2.1 节的保守默认执行；改变默认必须在对应决策门禁记录。
- 发现注册表 R1–R23 的 ID 固定，便于跨报告引用；新增发现用 R24+。
- 任何"审计修订"与草案冲突，以 `05-audit.md` 为准；本文件第 6 节的 A/B 设计已经包含修订。
- 报告文件不静默改结论；若代码变化导致证据失效，在文件顶部追加复核日期/差异。
