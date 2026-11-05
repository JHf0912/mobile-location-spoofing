# 00 - 优化报告索引（Optimization Report Index）

**日期**: 2026-10-29
**状态**: Phase 0 + Phase 1 complete; final gate passed (with noted device limits), 2026-10-30
**整理**: 文档归档 agent（只读；汇总 Explore A/B/C、Audit Agent 与 Plan Agent 的产出）
**工作树**: `main` @ `0e37763`，包含未提交改动；本目录只读归档，不改变工作树代码
**目录**: `docs/optimization/2026-10-29/`

本目录是这一轮"调研 -> 审计 -> 路线图"的稳定存档。主体描述的是**执行前的代码与判断**，不是执行结果；`07-test-inventory.md` 在此之上补记了 Phase 0 测试完成后的当前状态。后续修改代码后，`file:line` 可能漂移，引用前请重新核对。

---

## 1. 范围（Scope）

本轮优化只执行 **Phase 0 + Phase 1**：

- Phase 0：验证、测试盘点、Room v1 schema 导出、CI/release 冒烟、文档漂移修正、小而确定的修复。
- Phase 1：会话生命周期正确性与健壮性，拆成两个工作流：
  - **Workstream A**：teardown ownership（provider 租约、冷启动清理、timeout-safe 引擎启停、终止失败）。
  - **Workstream B**：session state（原子 `SessionState`、Starting/Stopping、session epoch、通知/磁贴/停止）。
  - **独立项 C**：坐标兜底、通知拒绝、数据/备份现状。
  - **实现契约**见 `08-phase1-design.md`；最终验证见 `12-final-verification.md`。当前状态：Phase 0 + Phase 1 complete；final gate passed（with noted device limits；x86_64 AVD only，ARM/OEM matrix 未跑）。
- Phase 2–4 **只保留在路线图**，本轮不执行：Compose 性能、地图单实例、可访问性/320dp、路线收藏、GPX、搜索、历史等。

范围边界：

- 不引入第二套地图栈，不做全球地理编码。
- 不改变"中国优先 + AMap/GCJ02"的产品方向；坐标兜底只解决"无地图设备不可用"。
- Room v1 按**已分发/不确定**处理：立即导出 v1 schema；下一次 schema 变更必须写 v1 -> v2 migration + `MigrationTestHelper` 测试；禁止 destructive recreate。
- 本轮文档不修改源码、构建文件、CI 或计划文件。

---

## 2. 方法（Method）

1. **工作树为事实来源**：所有 `file:line` 均指本归档时的 `D:\github\mobile-location-spoofing` 工作树。
2. **三路只读探索**：
   - Explore A：架构、模块边界、Gradle/AGP/依赖、CI、测试体系。
   - Explore B：运行时正确性、会话生命周期、FGS/Doze/OEM、性能与耗电。
   - Explore C：Compose UI、可访问性/本地化、Room 数据层、产品缺口。
3. **独立审计**：Audit Agent 只审计、不修复；挑战高危结论，标记 confirmed / false-positive / unverifiable，并补充漏报。
4. **主 AI 收敛**：去重、排优先级、识别依赖、记录用户决策，形成审计修正后的路线图。
5. **本归档**：重新核对了关键 `file:line`、测试计数、字符串使用、调用者（grep）等静态证据；**没有重新运行 Gradle、没有连接设备、没有复现构建体积/Pixel_8 结果**。凡属 Explore 报告值而未重跑的，标注为 `reported`。Phase 0 测试完成后，`07-test-inventory.md` 补记了 app 28 个测试；其通过证据是 `app/build/test-results/testDebugUnitTest/*.xml`，不是本轮重跑。

归档过程本身不执行任何修改类命令；`git status` 只用于核对新增文档。

> **快照与后续变化**：本目录开始写入后，同一工作树的 Phase 0 测试工作已完成（`app/src/test/` 的 `FormatTest`/`MainUiStateTest`，共 28 个测试；`core/data/schemas/.../1.json`；`lint.xml`；`settings.gradle.kts` 注释；`SpoofingDatabase.exportSchema=true`；preview 固定时钟）。这些改动不是本归档产生的，本归档只读取了测试源码和结果 XML。`07` 同时记录"审计时基线（64 / 0）"与"Phase 0 后当前（92 / 28）"；`02`/`06` 保留审计时口径，并在相关段落标注后续变化。引用 live worktree 时请重新核对 `file:line`、测试计数和 schema 状态。

---

## 3. 证据与置信度图例（Confidence Legend）

每条关键结论尽量带一个标签：

| 标签 | 含义 | 使用方式 |
|---|---|---|
| `verified` | 本轮归档直接复核了源码、配置或 grep 结果 | 附 `file:line` 或命令 |
| `reported` | Explore 报告声称已验证，但本轮未重跑（构建缓存、APK 体积、Pixel_8 运行结果等） | 附报告来源，不当作本轮实测 |
| `inferred` | 通过阅读代码/配置推导，未执行 | 明确写"推导" |
| `needs-device` | 必须由真机/模拟器/OEM/16KB 设备确认 | 不得写成"已修复" |
| `hypothesis` | 尚未证实，仅作线索 | 不得进入验收结论 |

审计结论另有固定判定：

| 判定 | 含义 |
|---|---|
| `CONFIRMED` | 审计接受该结论 |
| `CONFIRMED but deflated` | 结论成立，但严重度/影响被夸大 |
| `PLAUSIBLE / overstated` | 可能成立，但证据不足或表述夸大 |
| `MIXED` | 部分成立、部分不成立 |
| `NEEDS-DEVICE` | 静态证据成立，运行时行为未验证 |
| `FALSE POSITIVE` | 审计认为不成立 |

**两条硬规则**：

- 没有 `dumpsys location` + 至少两台 OEM 的证据，不得声称 provider 清理"已修复"。
- 没有 16KB 设备/模拟器的运行时证据，不得把 AMap ELF 对齐结论写成"当前崩溃"。

---

## 4. 报告职责与关系（Report Roles）

| 文件 | 职责 | 权威范围 | 状态 | 关系 |
|---|---|---|---|---|
| `00-index.md` | 入口：范围、方法、图例、阅读顺序、决策日志、术语表 | 全目录 | 已归档 | 指向所有报告 |
| `01-project-map.md` | 跨领域项目地图：模块图、会话生命周期、数据流、不变量、危险点、诊断命令 | 共享脊柱 | 已归档 | 02–04 都链接到这里 |
| `02-architecture-build.md` | Explore A 细节：架构、模块边界、Gradle/AGP/CI、release、测试策略 | 构建/测试权威 | 已归档（Explore A） | 引用 01 的模块图，向 06 提供构建类机会 |
| `03-runtime-correctness.md` | Explore B 细节：运行时地图、已验证修复、剩余缺口、性能/耗电 | 运行时权威 | 已归档（Explore B） | 引用 01 的生命周期，向 06 提供 P0/P1 项 |
| `04-ui-data-product.md` | Explore C 细节：UI 地图、Compose 性能、a11y/适配、数据层、产品矩阵 | UI/数据/产品权威 | 已归档（Explore C） | 引用 01 的数据流，向 06 提供 Phase 2–4 项 |
| `05-audit.md` | 独立审计：逐条判定、实现陷阱、误报/夸大、漏报、优先级调整、残余风险 | 对抗性 gate | 已归档（Audit Agent） | **必须先于 06 阅读** |
| `06-roadmap.md` | 审计修正后的路线图：阶段、依赖、Phase 0+1 执行计划、决策、禁做清单、发布门禁 | 决策记录 | 已归档（Plan + 主 AI + 审计修订） | 最后读；覆盖草案 |
| `07-test-inventory.md` | 测试清单：Phase 0 基线（64 / 0）与 Phase 0 后当前（92 / 28）；最终 227 见 `12` | 测试基线附录 | 已归档（Phase 0 后更新） | 06 Phase 0 的输入；最终计数以 12 为准 |
| `08-phase1-design.md` | Phase 1 实现契约：五条不变量、`SessionState`、provider lease、engine contract、runner/service、坐标兜底、测试矩阵、文件级顺序 | Phase 1 实现权威 | 实现与审计完成；final gate passed（device limits） | 06 Phase 1 的执行契约；引用 05 的审计陷阱 |
| `09-phase1a-audit.md` | Phase 1A + re-audits：teardown ownership、lease、engine/timeout、runner failure/AppOp、service/use-case | 1A 审计记录 | 已归档 | 与 10/11 组成 Phase 1 审计链 |
| `10-phase1b-audit.md` | Phase 1B-1/B-2 + re-audits：`SessionState` cutover、phase/epoch、UI/service/tile、tests | 1B 审计记录 | 已归档 | 与 09/11 组成 Phase 1 审计链 |
| `11-phase1c-audit.md` | Phase 1C + C1/C2 re-audit：coordinate fallback、LOW fixes、final gate readiness | 1C 审计记录 | 已归档 | 与 09/10 组成 Phase 1 审计链 |
| `12-final-verification.md` | 最终验证：build/lint/schema、227 JVM、4/4 Room、x86_64 E2E、device limits、audit trail/LOW residuals | 最终 gate 记录 | **PASSED with device limits** | Phase 0+1 关闭记录；最终计数以本文件为准 |

关系图：

```text
00-index
  |
  +-- 01-project-map (shared spine)
  |     |
  |     +-- 02-architecture-build
  |     +-- 03-runtime-correctness
  |     +-- 04-ui-data-product
  |
  +-- 05-audit (adversarial gate; read before 06)
  |
  +-- 06-roadmap (audit-corrected decision record)
  |
  +-- 07-test-inventory (audit-time baseline + Phase 0 current)
  |
  +-- 08-phase1-design (Phase 1 implementation contract)
  |
  +-- 09/10/11 audits (Phase 1A/1B/1C)
  |
  +-- 12-final-verification (final gate record; device limits)
```

**优先级规则**：`05-audit.md` 的审计修订优先于 `06-roadmap.md` 中的草案条目；`06-roadmap.md` 的"审计修订"章节又优先于其后的阶段描述。阅读时不要只看阶段标题。

---

## 5. 阅读顺序（Reading Order）

完整阅读：

```text
00 -> 01 -> 02 -> 03 -> 04 -> 05 -> 06 -> 08 -> 09/10/11 -> 12
                                  \
                                   +-> 07 (按需，测试盘点)
```

执行时最短路径：

```text
Phase 0: 00 -> 01 -> 05 -> 06 -> 07
Phase 1: 00 -> 01 -> 05 -> 06 -> 08 -> 09/10/11 -> 12 -> 07（测试基线）
```

然后按任务读领域报告：

- 改 Provider 租约、引擎超时、服务/磁贴/通知：`08-phase1-design.md` + `03-runtime-correctness.md` + `01-project-map.md`。
- 改 Room、CI、Gradle、测试基建：`02-architecture-build.md` + `07-test-inventory.md`。
- 改 Compose、a11y、数据层、产品：`04-ui-data-product.md`。
- 任何"要不要现在做"的判断：先看 `05-audit.md` 的"先不要做"和"优先级调整"。

---

## 6. 决策日志（Decision Log）

### 6.1 用户已确认的决策

| ID | 决策 | 选择 | 对计划的影响 |
|---|---|---|---|
| D1 | 本轮范围 | **Phase 0 + Phase 1** | Phase 2–4 只保留在路线图；本轮不做大规模 Compose/地图/产品改动 |
| D2 | 地图/坐标 | **中国优先 + 坐标兜底** | 保留 AMap/GCJ02；只做独立的 WGS84 坐标输入/粘贴与 `geo:` 解析；不引入第二套地图、不做全球地理编码 |
| D3 | Room v1 | **已分发/不确定，按已分发处理** | 立即导出 v1 schema；任何 schema 变更必须写 v1 -> v2 migration + `MigrationTestHelper`；禁止 destructive recreate；路线收藏/历史留在 Phase 4 |

### 6.2 未决事项的保守默认（本轮不阻塞）

| ID | 事项 | 本轮默认 | 重新决策时机 |
|---|---|---|---|
| D4 | 云备份策略 | 保持 `allowBackup=true` + README 披露；不加密、不加 UI 开关 | Phase 4 前 |
| D5 | 运行中编辑语义 | 仍只影响下一次会话；P1.7 只同步 idle 编辑到 `SessionConfigStore` | Phase 1 设备验证后记录 |
| D6 | 320dp/1.3x | 本轮不做布局修复；Phase 1 设备矩阵记录实际表现；Phase 3 再修 | Phase 3 前明确目标设备/语言 |
| D7 | Play/16KB 分发 | 本轮不做 AMap 替换或 ABI 决策；坐标兜底保证无地图设备可用 | 决定上架 Play 前 |
| D8 | fused 引擎 | 保留双引擎；不因"可能冗余"删除；先用 per-engine health 观察 | Phase 1 稳定后 |
| D9 | force-stop / 通知拒绝 | 实现租约清理与通知拒绝提示；产品接受度在设备验证后记录 | Phase 1 设备验证后 |

### 6.3 仍需产品/分发输入的事项

- 全球/坐标模式 vs 中国优先（D2 只锁定本轮；全球会影响 Phase 4 搜索与 16KB/ABI 战略）。
- 真实感（道路贴合、jitter、accuracy/altitude）vs 确定性测试。
- 备份加密/开关；运行中编辑是否实时生效；320dp/1.3x 的验收设备和语言。
- Play 分发、16KB、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 的 Play 政策风险。
- fused 是否值得保留；force-stop 残留是否可接受；通知拒绝时磁贴作为停止兜底是否可接受。

### 6.4 Phase 0 关闭记录（2026-10-29）

Phase 0 审计结果（只记录结论；`05-audit.md` 保持审计时原样，不追写 Phase 0 结果）：

| 项 | 结果 | 备注 |
|---|---|---|
| Schema 导出 | **CONFIRMED** | v1 schema JSON 已生成；后续变更仍需 migration test |
| CI `lintVitalRelease` / artifacts | **CONFIRMED** | lint/mapping/APK 上传路径已确认 |
| lint hermeticity | **CONFIRMED** | `NewerVersionAvailable` 已关闭，lint 不再依赖网络 |
| 确定性 preview | **CONFIRMED** | preview 时间源改为固定值 |
| 28 个 app 测试 | **CONFIRMED** | `FormatTest` 10 + `MainUiStateTest` 18；XML failures=0/errors=0 |
| `map_fullscreen` | **移除中** | 未被使用；lint warning |
| keystore / 配置缓存 | **Phase 0 无代码改动** | 当前证据表明 Gradle 会跟踪 `File.exists()`/`FileInputStream`，stale-input 在两次构建复现前按"很可能误报"处理；项目本地配置缓存中的密码静态存储是 plausible/low severity，留待后续 build-hygiene pass |
| `org.gradle.tooling.parallel=true` | **惰性/no-op** | Gradle 9.3.1 上无效；这是用户本地未提交行，保留并记录，等 wrapper 升到 9.4+ 再评估 |
| Phase 0 delta 与提交 | **与既有 UI/session 改动混合** | 用户要求提交时，commit 应拆分"baseline"与"Phase 0"两组 |
| release build | **PASS** | APK 2026-10-30 04:41:59，mapping 04:41:55；unsigned；详见 `12-final-verification.md` |

**Phase 1 closure**: Phase 1A/1B/1C implementation and audits are complete; the final gate passed with documented device limits (x86_64 AVD only; ARM base-map and two-OEM matrix remain `needs-device`). See `12-final-verification.md`.

> 决策记录以本节为准。任何后续阶段若改变 D1–D3，必须在 `06-roadmap.md` 的"变更控制"中追加记录，而不是静默修改报告。

---

## 7. 术语表（Glossary）

| 术语 | 含义 |
|---|---|
| WGS84 | GPS/国际通用大地坐标基准；注入器要求此基准 |
| GCJ02 | 中国国测局加密坐标；AMap 渲染使用 |
| BD09 | 百度坐标；项目只做转换支持，不用于 UI |
| datum | 坐标所基于的基准；`GeoPoint.coordType` 表达 |
| test provider | `LocationManager.addTestProvider` 注册在 `system_server` 的模拟 provider，可跨进程存活 |
| AppOp | Android 应用操作权限；`android:mock_location` 决定本应用是否为系统选中的模拟位置应用 |
| fused | Google Play services 的 `FusedLocationProviderClient` 注入路径 |
| FGS | Foreground Service；本项目用 `location` 类型前台服务维持注入 |
| Doze | Android 低电耗模式；会限制后台 CPU/网络 |
| OEM | 设备厂商定制系统（MIUI/HyperOS、EMUI、ColorOS、OriginOS 等） |
| provider lease | 计划中的持久化"provider 可能仍注册"标记；是启发式，不是所有权证明 |
| session epoch | 会话起始的 `elapsedRealtime` 基准；计划中放入 runner/session 状态，供计时器使用 |
| teardown | 会话结束时的清理：`stopMockMode()`、注销 provider、释放 WakeLock、停止服务 |
| `NonCancellable` | Kotlin 协程上下文，保证清理代码在取消后仍执行；不防止阻塞调用永久挂起 |
| `Starting` / `Stopping` | 计划中的显式会话阶段；当前只有 Idle/Running/Error |
| `Degraded` | 计划中的"仍在运行但部分失败"状态，与终止性 `Error` 区分 |
| AMap | 高德地图 SDK；10.0.600，只提供 arm64-v8a/armeabi-v7a native 库 |
| ABI | 应用二进制接口；AMap 缺 x86/x86_64 native 库，模拟器走占位图 |
| 16KB page | Android 15+ 设备可能使用 16KB 内存页；native 库需 `p_align=0x4000` |
| GMD / ATD | Gradle Managed Devices / Automated Test Device；CI 跑 instrumented 测试的候选方案 |
| `MigrationTestHelper` | Room 的 schema 迁移测试工具；需要导出的 schema JSON |
| baseline profile | ART 启动/热点 profile；当前 pipeline 由库驱动，app 类覆盖为 0（`reported`） |
| Tile | Quick Settings 磁贴；本项目的备用启停入口 |
| supersede | 新会话替换旧会话；runner 用 `sessionId`/`accepting`/`previous.join()` 保证旧 teardown 不拆新会话 |

---

## 8. 使用约定与维护（Conventions）

- 所有路径默认相对仓库根目录；`file:line` 指本归档时的工作树。
- 命令行示例是**诊断剧本**，本轮没有执行；在 Windows 上 `grep` 可换成 `findstr`，或直接在 Git Bash 中运行。
- `reported` 的数字（APK 体积、Pixel_8 耗时、缓存内 AGP 元数据、profile 条目数）不能直接当作本轮验收证据；需要时重新测量。
- 本目录只追加/修订报告，不放入截图、日志、构建产物；临时材料继续放已 gitignore 的 `tmp/`。
- 若后续代码修改导致 `file:line` 漂移，以代码为准，并在报告顶部追加"复核日期/差异"而不是静默改结论。

---

## 9. 快速导航（Quick Navigation）

- 想知道"系统现在怎么跑"：`01-project-map.md` 第 2–4 节。
- 想知道"哪些修复是真的"：`03-runtime-correctness.md` 第 2 节。
- 想知道"哪些结论被审计推翻"：`05-audit.md` 第 2–7 节。
- 想知道"本轮到底做什么"：`06-roadmap.md` 第 5–6 节。
- 想知道"现在有多少测试"：`07-test-inventory.md` 第 0 节（时点对照）与第 3 节（Phase 0 后当前）。
- 想知道"Phase 1 具体怎么实现"：`08-phase1-design.md`（实现/审计完成）。
- 想知道"最终 gate 结果/限制"：`12-final-verification.md`（PASSED with device limits）。
- 想知道"为什么不能直接加 `withTimeout`"：`05-audit.md` 第 7 节第 1 条。
