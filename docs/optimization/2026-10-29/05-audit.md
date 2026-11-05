# 05 - 独立审计（Independent Audit）

**日期**: 2026-10-29
**角色**: Audit Agent（只审计，不修复）
**范围**: 挑战 Explore A/B/C 的高危结论，找误报/夸大/漏报，评估风险与可行性
**方法**: 只读代码与报告交叉核对；没有运行构建、没有连接设备、没有修改任何文件
**证据图例**: 见 `00-index.md` 第 3 节

本文件只记录审计判断。它不提供修复方案、不写代码、不改路线图；路线图的最终形态见 `06-roadmap.md`。审计的优先级建议是"排序建议"，不是实现指令。

---

## 1. 审计立场与局限

审计的任务不是确认三份报告"看起来合理"，而是回答：

1. 这个结论在代码里真的成立吗？
2. 它现在是 bug、是未来风险，还是只是性能/卫生问题？
3. 如果按草案直接修，会不会引入新的竞态或数据错误？
4. 哪些结论证据不足，必须降级？
5. 三份报告共同漏了什么？

局限：

- 没有设备，因此 provider 残留、fused 挂起、OEM FGS、16KB 运行时失败都只能停在 `needs-device`。
- 没有运行 Gradle，因此构建体积、profile 条目、Kotlin stdlib 解析、配置缓存序列化都只能引用 Explore 报告（`reported`）或标为未复现。
- 审计不判断产品价值；产品优先级由用户决策和 `06-roadmap.md` 记录。

---

## 2. 逐条判定（Verdicts）

| ID | 发现 | 判定 | 审计要点 |
|---|---|---|---|
| R1 | `Running` 带 `elapsedRealtimeMs`，每秒不等 -> 全链路唤醒 | **CONFIRMED** | 是性能问题，不是正确性 bug；"3,600 次重组/小时"只在有帧时成立 |
| R2 | 系统 test provider 跨进程死亡存活；无持久租约/冷启动清理 | **CONFIRMED** | 代码层成立；运行时需 `dumpsys location` 验证 |
| R3 | Room v1 + `exportSchema=false`，无 schema/迁移测试 | **CONFIRMED but deflated** | 不是当前崩溃；v1 schema 自 `pre-refactor` 未变（identity hash `1277d4d...`），是未来改表的前置门槛 |
| R4 | 计时器在 ViewModel 推断；Error 保留旧起点 | **CONFIRMED** | 新 ViewModel 以最新 tick 为起点；失败后重启继承旧起点 |
| R5 | `SessionConfigStore` 只在 start 写入，`clear()` 无调用者 | **CONFIRMED** | 磁贴可重启旧 spec；进程重启后 store 为空是刻意的防呆 |
| R6 | 引擎 start/stop 无超时；fused 先启动 | **PLAUSIBLE / overstated** | 只有 fused Task 真的挂起才会阻塞 LM；但阻塞机制真实，且 per-push 无超时是漏报 |
| R7 | 瞬时失败与终止失败共用 `Error`；AppOp 撤销后永久错误循环 | **CONFIRMED** | 需要连续失败计数 + AppOp 复检 + 终止；但不要用裸 `withTimeout` |
| R8 | 无显式 Starting/Stopping；启动窗口双击/磁贴可再次启动 | **CONFIRMED** | Runner 能安全 supersede，但会重启路线/重建 provider/重发通知 |
| R9 | `handleStop` 立即 `finishSession`，onDestroy 不 join teardown | **CONFIRMED** | 窗口小；正常路径安全，风险在未来调用者/慢 OEM |
| R10 | 内联+全屏双 MapView；marker O(N²) 重建；polyline 10px；相机丢失 | **CONFIRMED** | "O(N²)" 夸大；实际是每次编辑、每个地图实例 O(N) |
| R11 | `favoriteName` 提升到 MainScreen；收藏 forEach 不虚拟化 | **PLAUSIBLE / overstated** | 击键重组的是收藏 item 所在页面，不是"所有可见 item"；1 Hz 状态本来就会全屏重组 |
| R12 | 可访问性：地图预览无语义、无 heading/liveRegion、RouteControls 缺 selectableGroup、对比度/focus | **CONFIRMED** | 结构性缺口；具体对比度/布局需要截图/axe 确认 |
| R13 | 320dp+1.3x 英文可能溢出；DESIGN.md 声称支持但无验证 | **CONFIRMED** | 结构性；需要截图矩阵确认具体溢出点 |
| R14 | 通知拒绝后失去应用内停止入口；`ensureChannel` 每次 rebuild | **CONFIRMED** | 通知拒绝确认；channel 缓存被夸大（只在 buildNotification 时调用） |
| R15 | provider 被抢/AppOp 撤销后不重注册、不复检 | **CONFIRMED** | 运行中无重注册；下一次 start 会先 remove 再 add，能恢复 |
| R16 | 审计时 `:app` 无测试；ViewModel Context-bound + final 依赖；无 Hilt/Robolectric 基建 | **CONFIRMED** | 是 Phase 2 的前置，不是"先多写测试"；Phase 0 后已补 28 个纯 JVM 测试，但仍需先解耦 ViewModel |
| R17 | CI 三次独立 Gradle 调用；不跑 instrumentation/lintVital；不上传 lint/mapping/APK | **CONFIRMED** | 三次调用是优化不是缺陷；不上传 artifacts 是实际缺口 |
| R18 | 无地图设备无法选点/收藏/开始会话 | **CONFIRMED** | 最强理由是 x86 模拟器/AMap native 失败；坐标兜底是唯一可用路径 |
| R19 | 收藏仅单点；路线收藏不可用；无唯一索引；Room 错误会永久终止收藏流 | **CONFIRMED** | 路线收藏是 Phase 4，取决于 schema 决策 |
| R20 | AMap 10.0.600 ELF 4KB 对齐，16KB 设备风险未验证 | **NEEDS-DEVICE** | ELF 对齐确认；运行时是否失败取决于 Android 16 compat mode 与 AMap 加载路径，不是 app 代码能修的 |
| R21 | 构建声明：AGP↔Gradle 耦合、keystore 配置缓存、Material profile 影响、tooling.parallel 有效性 | **MIXED** | AGP↔Gradle 耦合确认；keystore stale-input 很可能是误报；`tooling.parallel` 在 9.3.1 无效；Material profile / stdlib 2.3.21 未复现 |
| R22 | 进程死亡丢失 mode/点/路线/速度/到达行为 | **CONFIRMED** | 只有 favoriteName/Dialog 可见性 rememberSaveable |
| R23 | `GeoPoint` 默认 GCJ02，注入要求 WGS84，`toLocation` 不校验；UI 无 datum 标签 | **CONFIRMED** | 正常路径安全（Route/Static 强制 WGS84）；风险在未来调用者/新入口 |

统计：18 CONFIRMED，1 CONFIRMED but deflated，2 PLAUSIBLE/overstated，1 MIXED，1 NEEDS-DEVICE。

---

## 3. 确认但降级的结论（Confirmed but Deflated）

### R3：Room v1 不是当前崩溃

- v1 schema 自 `pre-refactor` 未变（identity hash `1277d4d...`），当前没有迁移失败。
- `exportSchema=false` 的真实影响是**未来**：一旦改表，没有 schema 基线、没有 migration 测试、没有回滚路径。
- 审计结论：这是 Phase 0 的"便宜前置"（导出 schema），不是 Phase 1 的正确性修复；不要在 v2 migration 之前加空的 `room-testing` 骨架。
- 置信度：`verified`（代码 + Explore 的 hash 比对）。

### R1：性能不是正确性

- `Running` 的 timestamp 让 StateFlow 每秒发射，导致重组/唤醒/耗电；但会话仍然注入正确位置，计时器也仍然走。
- "静态会话 3,600 次重组/小时"只在有帧/可见时成立；后台/息屏不一定有重组。
- 审计结论：最高杠杆的性能修复，但放在 Phase 2；不要把它说成正确性 bug。

### R9：窗口小

- `handleStop` 立即 `finishSession`，teardown 异步；正常路径下 `NonCancellable` 会跑完。
- 风险在"快速进程终止/OEM 慢销毁"和未来调用者；当前没有证据表明常规路径会留下 provider。
- 审计结论：Phase 1 处理，但不要用"已证明的崩溃"来描述。

### R23：正常路径安全

- `Route`/`StaticMovementSource` 强制 WGS84；`MainViewModel` 在地图/收藏入口转换。
- `LocationExt.toLocation` 不校验，但当前所有生产路径都先经过 movement source。
- 审计结论：风险在未来新入口/新 source；P1.8 的坐标输入必须显式 datum，不能复用 GCJ02 入口。

---

## 4. 部分成立 / 夸大（Plausible / Overstated）

### R6：只有 fused 挂起才会阻塞 LM

- `CompositeMockLocationEngine.startMockMode()` 顺序启动；fused 在前（`LocationModule.kt:45`）。
- 如果 fused 的 `setMockMode(true).await()` 永不完成，LM 不会启动；teardown 在同一个 Task 上 `await()` 会阻塞 LM 清理。
- 但"无超时"本身不等于"会挂起"；Play services 正常时，fused 在几百毫秒内返回。
- 审计结论：阻塞机制真实，严重度取决于 fused 挂起概率；这是 `needs-device` 的频率问题，不是必然故障。
- 漏报：per-push 也没有超时；fused `setMockLocation` 挂起会让整个 loop 静默停摆。

### R11：不是"所有可见 item"

- `favoriteName` 提升到 `MainScreen`，击键触发 `MainScreen` 重组；但 Compose 可能跳过未变化的 item，尤其是 keyed LazyColumn item。
- 收藏列表在单个 item 内 `forEachIndexed`，所以该 item 内的所有行会一起重组；不是"所有可见 item"。
- 1 Hz 状态本来就会导致全屏重组；`favoriteName` 是额外的击键成本。
- 审计结论：真实问题，但表述要降级；修复顺序在 Phase 2，与稳定性/虚拟化一起。

---

## 5. 混杂结论（Mixed）

### R21：构建声明

| 子结论 | 判定 | 理由 |
|---|---|---|
| AGP 9.1.0 与 Gradle 9.3.1 耦合 | 成立 | AGP 的 minimum Gradle 是 9.3.1；AGP/wrapper 必须同步升级 |
| Dependabot 分组漏了 wrapper | 成立 | `.github/dependabot.yml` 只分组 Compose/Kotlin；未分组 AGP PR 可能失败 |
| keystore 配置缓存导致 stale password/序列化 | **很可能是误报** | Gradle 配置缓存通常会跟踪 `File.exists()`/`FileInputStream`；需要两次构建复现 |
| `org.gradle.tooling.parallel` 在 9.3.1 有效 | **不成立** | 该属性是 Gradle 9.4+；当前 no-op |
| Material Components 主导 baseline profile | **未复现** | profile 条目数来自 Explore，审计未重跑；不能作为路线图依据 |
| resolved kotlin-stdlib 2.3.21 是编译器不一致 | **未复现** | 可能是 AGP 自带 KGP 的传递解析；需要 `buildEnvironment` 确认 |

审计结论：构建类结论只能作为"待验证线索"，不能直接进入 Phase 0 的修复清单；P0.5 只做便宜、可逆的项。

---

## 6. 需要设备（Needs-Device）

### R20：16KB page

- ELF 证据：`libAMapSDK_MAP_v10_0_600.so` 的 `PT_LOAD p_align=0x1000`，而 16KB 设备要求 `0x4000`。
- 但"4KB 对齐的库在 16KB 设备上一定失败"不是必然：Android 16 的 compat mode、AMap 的加载路径、是否有 4KB 兼容页都可能影响。
- 这不是 app 代码能修的问题；如果上架 Play 且 target Android 15+，AMap 升级/替换是分发决策。
- 审计结论：`needs-device`；在 16KB emulator/真机验证前，不要对 16KB 采取行动，也不要把 ELF 对齐写成"当前崩溃"。

---

## 7. 关键实现陷阱（Critical Implementation Traps）

以下陷阱是审计推翻草案的地方。它们不是修复方案，而是"如果按草案直接写，会坏在哪里"。

### 陷阱 1：`withTimeout` 不能直接包引擎调用

- `kotlinx.coroutines.TimeoutCancellationException` 继承 `CancellationException`。
- `MockSessionRunner` 和 `CompositeMockLocationEngine` 现有 catch 明确 rethrow `CancellationException`（`MockSessionRunner.kt:101-102,115-116,126-129,146-147,160-161`；`CompositeMockLocationEngine.kt:33-34,44-49,64-65,87-88,102-103`）。
- 如果直接 `withTimeout { engine.startMockMode() }`，超时会以 `CancellationException` 形式抛出，被现有 rethrow 分支当成"真正的取消"：
  - 跳过后续引擎的启动；
  - 跳过 `finally` 里的 `onFinished()`；
  - 服务可能永久运行。
- 这是草案 timeout 补丁的最大缺陷。审计只指出机制，不在此处给实现。

### 陷阱 2：GMS Task 取消不会取消底层 Task

- `FusedLocationProviderClient.setMockMode(true)` 返回 GMS `Task`；协程 `await()` 被取消，不代表底层 `Task` 被取消。
- 超时后，`setMockMode(true)` 可能晚到，在 teardown 的 `setMockMode(false)` 之后完成，留下 fused mock mode on。
- `NonCancellable` 只能防止协程被取消，不能抢占阻塞的 binder/Play services 调用。
- 需要一个"代际/epoch"守卫和超时后的 stop 重试；否则"超时"只是把挂起变成晚到。

### 陷阱 3：Provider lease 设计不完整

- `Application.onCreate` 清理会与服务 `handleStart` 竞态：清理可能在新会话 `addTestProvider` 之后运行，删掉刚注册的 provider。
- provider 名 `gps`/`network` 是全局的；无法知道当前 provider 是否属于本 app，也无法知道是否被另一个 mock app 占用。
- `stopMockMode(): Unit` 吞掉 `SecurityException` 并清空 `touched`，调用方无法确认"成功移除"。
- force-stop 不会调 `onTerminate`；只能下次启动清理。
- 因此租约只能作为**启发式**，不能作为所有权证明；必须明确 force-stop 残留的产品接受度。
- 审计要求：进程级 gate、写入先于 `addTestProvider`、per-provider stop result/health、lease 与 session epoch/last spec 分开。

### 陷阱 4：bounded join 会重新引入旧 bug

- `stopAndJoin(timeout)` 超时后继续启动新会话，会重演"旧 teardown 注销新 provider"的 bug。
- 更安全的是停在 `Stopping` 并拒绝新 start，直到 teardown 完成；或者给 teardown 加 ownership tag + 幂等。
- 这与陷阱 1 相连：bounded join 不能只加超时，还要定义超时后的状态。

### 陷阱 5：坐标输入不能复用 `onMapPointSelected`

- `MainViewModel.onMapPointSelected` 假定输入是 GCJ02 并立即转 WGS84（`MainViewModel.kt:53-68`）。
- `geo:` 链接和粘贴的 `lat,lon` 通常是 WGS84；复用会造成几百米偏移。
- 需要显式的 datum 参数/入口和范围校验；不能把 WGS84 输入喂给 GCJ02 入口。
- 这是 D2 "坐标兜底"的核心实现约束。

### 陷阱 6：session epoch、provider lease、last spec 是三件事

- 计时器起点（epoch）、provider 残留标记（lease）、下一次会话 spec（last spec）生命周期不同，不能混成一个 store。
- 混在一起会导致：停止后 timer 不重置、lease 被 spec 编辑覆盖、磁贴读到错误的"活跃会话"。
- 审计要求在设计阶段就把这三个概念分开。

---

## 8. 误报 / 夸大 / 漏报

### 8.1 夸大或误报

| 条目 | 审计判断 |
|---|---|
| R1 "3,600 次重组/小时" | 只在有帧时成立；性能不是正确性 |
| R3 "当前崩溃" | 不是当前崩溃；v1 schema 自 pre-refactor 未变 |
| R11 "每次击键重组所有可见 item" | 不是"所有可见 item"；是收藏 item/页面重组，1 Hz 本来全屏 |
| R14 "channel 缓存" | 次要问题；只在 buildNotification 时调用，不是每秒 |
| R17 "三次 Gradle 调用是缺陷" | 是优化，不是缺陷 |
| R21 "keystore 安全性" | 框架不成立；需先复现 |
| R10 "O(N²)" | 实际是每次编辑、每个地图实例 O(N) |
| R20 "16KB 崩溃" | ELF 对齐证据成立；运行时失败未验证 |
| "4,478 profile 条目" / "stdlib 2.3.21" | 未复现；不能作为路线图依据 |

### 8.2 漏报（审计补充）

1. **没有 per-push 超时**：fused `setMockLocation` 挂起时 LM 永远收不到 push，整个 loop 静默停摆。
2. **`previous?.join()` 无界**：旧 teardown 挂起会永久阻塞后续 start，而 `isRunning` 仍可能为 true。
3. **`StopMockUseCase` 在 `startService` 失败时把运行中状态覆盖成 Error**：会话还在跑，UI 却显示错误。
4. **terminal Error 后计时器继续走、Stop 仍可点**：`MainViewModel.kt:254`、`MainScreen.kt:76`。
5. **启动时 collector 可能短暂重发旧 Error 通知**：`lastNotificationText = running` 后 collector 立即读到 store 里的旧 Error。
6. **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 有 Play 政策风险**：侧载可接受，上架需评估。
7. **备份包含坐标且 UI 无开关**：README 披露但应用内没有控制。
8. **没有诊断日志**：现场失败不可观察；grep 无 `android.util.Log`。
9. **没有 `onTaskRemoved` 决策**：从最近任务划掉后的行为未定义。
10. **`startForegroundService` 后 `stopSelf` 在 OEM 上未验证**：缺 spec/权限/AppOp 时直接 `stopSelf`，未先 `startForeground`。
11. **Phase 2 的 Compose 测试验收与 Phase 3 才加测试依赖互相矛盾**。
12. **baseline profile 的 app 类覆盖未验证**：不能假设当前 profile 对启动有效。

---

## 9. 优先级调整（Priority Adjustment）

审计不改变"先正确性、后性能、再产品"的大方向，但调整了顺序和粒度。

### 9.1 先决策，再写码

以下事项必须先记录，否则写码会返工：

- Room v1 是否分发（D3 已决定：按已分发处理）。
- Play/16KB 分发。
- 中国优先 vs 全球/坐标模式（D2 已决定：中国优先）。
- 备份策略。
- 运行中编辑语义。
- 320dp/1.3x 的目标设备和语言。
- fused 是否保留。
- force-stop 残留是否可接受。
- 通知拒绝时磁贴作为停止兜底是否可接受。
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 的 Play 政策。

### 9.2 Phase 0 只做便宜项

- schema 导出；
- CI artifacts/lint gate；
- 测试盘点；
- preview 固定时钟；
- 文档漂移；
- keystore 两次构建复现（不是安全修复）。

**不要**在 Phase 0 先加 Room 测试骨架、移除 Material、拆模块、重写 runner。

### 9.3 Phase 1 拆两个工作流，不要五个

- **A. teardown ownership**：stop-result API + lease + timeout-safe 启停 + LM-first/反向 stop + per-push timeout + 处理 `previous.join()`。
- **B. session state**：Starting/Stopping + Degraded/terminal + epoch，放在同一个原子状态流里。
- 坐标输入可以早做（独立、解锁无地图设备），但先做 datum-explicit 入口。
- per-engine health 和 provider 重注册推迟到 A/B 稳定后。

### 9.4 Phase 2 先做最简单的 equality 修复

- `Running(point, sessionEpoch)` 常量 epoch，而不是拆两个时钟。
- 然后做 stable actions/state、收藏、地图单实例/keyed diff、通知/磁贴。
- baseline/macrobenchmark/ABI split 等测量后再说。

### 9.5 Phase 3 / 4

- Phase 3 按计划，但测试依赖提前到 Phase 2 开始前（否则 Phase 2 的 Compose 验收无法执行）。
- Phase 4 等 schema/分发决策；路线收藏/历史风险最高；GPX 导入比地理编码价值更高，且不需要第二套地图栈。

### 9.6 降级/放弃

- `tooling.parallel`：在 9.3.1 无效，删除或标注未来启用。
- keystore"安全修复"框架：先复现，不作为已证明漏洞。
- Material 移除：降级为视觉/启动优化，不是 Phase 0/1 优先项。
- `O(N²)`/全 item 的绝对化表述：改为"每次编辑每个地图实例 O(N)"。
- 4,478 profile 条目 / stdlib 2.3.21：未复现，不进入路线图依据。
- ABI split 作为体积优化：主要改善分发，不一定减少 ARM 安装体积。
- MapLibre：在 16KB/全球决策前不评估。
- 模块拆分和完整 ViewModel 去 Android 化：在接口/测试之前不做。

---

## 10. 各阶段残余风险（Residual Risk）

| 阶段 | 残余风险 | 置信度 |
|---|---|---|
| 0 | 低；schema 导出可能暴露不一致；CI artifact 成本 | 高 |
| 1 | 高；provider 所有权、force-stop、GMS 晚到 Task、OEM FGS、清理竞态都依赖设备；没有 app 测试 seam | 低-中；没有 `dumpsys` + 两设备证据不得声称"修复" |
| 2 | 中；equality 改动可能影响 timer/通知；Compose 测试还没有；耗电需实测 | 中 |
| 3 | 中；视觉/a11y 无 baseline；对比度和 1.3x 是真实问题；截图基建是新的 | 中 |
| 4 | 高；schema 迁移、备份、数据丢失是用户数据风险；分发答案未知 | 低，直到 Room/Play 决策记录 |

---

## 11. 审计总评（Overall Verdict）

- **方向正确，但不能按草案直接执行**。
- R2/R8/R15 是真实问题；R6 的阻塞机制真实，但严重度取决于 fused 是否真的挂起；R3 和若干构建结论被夸大；草案的 timeout 补丁会破坏 teardown；坐标输入方案会破坏 WGS84。
- **先修状态/teardown 设计并记录用户决策，再写代码。**
- 没有 `dumpsys location` + 两台 OEM 证据，不得声称 provider 清理"已修复"。
- 没有 16KB 设备证据，不得把 AMap ELF 对齐写成"当前崩溃"。
- Phase 0 是便宜的验证/快赢；Phase 1 必须按 A/B 两个工作流设计，不能把五个修复捆成一个 phase gate。

### 11.1 什么会改变审计判定

- 在 16KB 设备/模拟器上复现 AMap 加载失败 -> R20 从 `needs-device` 升为 `CONFIRMED`（运行时）。
- 两次构建证明 keystore 修改不使配置缓存失效 -> R21 的 keystore 子项从"很可能误报"升为 `CONFIRMED`。
- `dumpsys location` 在 force-stop 后无残留 -> R2 的运行时部分降级（但代码层仍缺冷启动清理）。
- fused listener 测试证明 fused 消费者不接收 LM test-provider fix -> R6/fused 保留决策改变。
- Compose 测试证明 100 收藏只组合可见行 -> R11 的视觉化部分降级。

### 11.2 审计未做的事

- 没有运行任何测试、构建、设备命令。
- 没有修改计划、源码、构建文件、CI。
- 没有评估产品市场价值；只评估技术风险与顺序。
