# Harness 组件 Agent 地图

本目录承载框架组件，不承载目标业务实现。修改本目录时先确认变更是模板能力、运行记录治理、memory/trace/eval 治理，还是文档说明。

## 组件边界
| 路径 | 职责 |
|------|------|
| `VERSION` | 模板语义化版本唯一来源 |
| `docs/` | harness 框架文档和示例配置 |
| `dream/` | 只读 dream report 入口和 reports 运行路径说明 |
| `doc-local/` | `doc.mode=submodule` 时的本地草稿区 |
| `.local/` | ignored 的模板本地运行记录，不分发 |
| `memory/` | durable memory 和 index |
| `evals/` | 经 review 的仓库级 eval 元数据 |
| `trace/` | 目标项目标准执行追踪；模板自维护写入 `.local/trace/` |

## 规则
- 修改框架能力时同步 `harness/VERSION`、`harness.example.yaml`、示例配置、README Version History 和升级文档。
- 模板仓库自维护记录必须进入 `harness/.local/`；不要把本机运行记录写入标准 `memory/`、`trace/`、`dream/reports/`。
- `harness-upgrade` 可自动导入本目录下框架拥有的局部 `AGENTS.md`，但根项目文件仍然 MANUAL 合并。
- `harness/dream` 只读；不得直接修改 memory、eval、prompt、policy 或业务代码。
- runtime candidate 和 eval candidate 只能作为候选证据，review 后才允许提升为 durable memory 或 repo eval。

## 子目录规则
- `docs/AGENTS.md`
- `dream/AGENTS.md`
- `doc-local/AGENTS.md`
- `memory/AGENTS.md`
- `evals/AGENTS.md`
- `trace/AGENTS.md`
