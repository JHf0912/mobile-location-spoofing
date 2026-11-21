# 文档索引

本目录只放**稳定、可引用**的文档。临时截图、日志和一次性输出放在仓库根目录的 `tmp/`（已 gitignore），不进版本库。

## 顶层文档

| 文档 | 内容 |
| --- | --- |
| [../README.md](../README.md) | 项目简介、能力边界、快速开始、测试与构建 |
| [../DESIGN.md](../DESIGN.md) | 模块架构、会话状态机、注入管线、UI 设计系统、验收标准 |
| [capability-boundary.md](capability-boundary.md) | 能力边界与用途约束：mock 标记、过滤类应用、非目标与证据级别 |

## 优化归档

每一轮优化在 `optimization/<日期>/` 下独立归档，归档后内容不再修改；后续变化通过新目录或新的基线文档记录。

### `optimization/2026-10-29/`

Phase 0 + Phase 1 的完整调研、审计与实现记录。**状态：Phase 0 / Phase 1 完成，final gate 通过（带设备限制）。**

| 文件 | 职责 |
| --- | --- |
| [00-index.md](optimization/2026-10-29/00-index.md) | 入口：范围、方法、证据图例、阅读顺序、决策日志 |
| [01-project-map.md](optimization/2026-10-29/01-project-map.md) | 跨领域地图：模块、会话生命周期、数据流、不变量、诊断命令 |
| [02-architecture-build.md](optimization/2026-10-29/02-architecture-build.md) | 架构、构建、CI、release、测试体系 |
| [03-runtime-correctness.md](optimization/2026-10-29/03-runtime-correctness.md) | 运行时正确性、FGS、Doze、性能与耗电 |
| [04-ui-data-product.md](optimization/2026-10-29/04-ui-data-product.md) | Compose、可访问性、本地化、Room、产品缺口 |
| [05-audit.md](optimization/2026-10-29/05-audit.md) | 独立审计：逐条判定、实现陷阱、误报与漏报 |
| [06-roadmap.md](optimization/2026-10-29/06-roadmap.md) | 审计修正后的分阶段路线图与禁做清单 |
| [07-test-inventory.md](optimization/2026-10-29/07-test-inventory.md) | 测试盘点与基线 |
| [08-phase1-design.md](optimization/2026-10-29/08-phase1-design.md) | Phase 1 实现契约 |
| [09-phase1a-audit.md](optimization/2026-10-29/09-phase1a-audit.md) | teardown ownership 审计 |
| [10-phase1b-audit.md](optimization/2026-10-29/10-phase1b-audit.md) | SessionState 切换审计 |
| [11-phase1c-audit.md](optimization/2026-10-29/11-phase1c-audit.md) | 坐标兜底与通知审计 |
| [12-final-verification.md](optimization/2026-10-29/12-final-verification.md) | 最终验证：构建、227 个 JVM 测试、4/4 Room、x86_64 E2E、设备限制 |

阅读顺序：

```text
00 → 01 → 02/03/04（按需要）
        → 05（对抗性审计，先于 06）
        → 06（路线图）
        → 08 → 09/10/11 → 12
```

## 写作约定

- 每条高风险结论带 `file:line` 或命令证据，并标注 `verified` / `inferred` / `needs-device`。
- 没有 `dumpsys location` + 至少两台 OEM 的证据，不得声称 provider 清理"已修复"。
- 没有设备运行时证据，不得把 AMap 的 16 KB 对齐问题写成"当前崩溃"。
- 归档目录描述**当时的判断**；引用前请重新核对 `file:line` 与测试计数。
