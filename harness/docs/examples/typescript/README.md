# TypeScript Harness Example

## 适用场景

TypeScript 技术栈适用于以下场景：

- Web 前端应用（React、Vue、Angular）
- Node.js 后端服务
- 跨平台桌面应用（Electron）
- Monorepo 多包项目（pnpm workspaces）
- 需要严格类型安全的项目

TypeScript 新项目默认放在 `workspace/apps/web/` 下，平台目录内以 `src/` 作为源码根，通过 npm/pnpm/yarn 管理依赖，支持完整的类型检查和编译验证。

## 关键字段解释

| 字段 | 说明 |
|------|------|
| `project.language: typescript` | 标识项目为 TypeScript 技术栈 |
| `platforms.web.path: "workspace/apps/web"` | Web 平台代码所在的平台根目录 |
| `validation.build.enabled: true` | 启用构建验证，执行 npm run build |
| `validation.test.enabled: true` | 启用测试验证，执行 npm test |
| `architecture.layers` | 定义 api / service / repository 三层结构 |
| `quality.forbidden_patterns` | 禁止使用 console.log，须使用 proper logging |

## 如何拷贝使用

1. 复制 `harness/docs/examples/typescript/harness.yaml` 到项目根目录
2. 重命名为 `harness.yaml`
3. 根据项目实际情况修改以下字段：
   - `project.name`：项目名称
   - `architecture.layers.path`：按实际目录结构调整
   - `validation.build.command`：按实际构建命令调整（如 `vite build`）
4. 运行 `python scripts/validate.py` 验证配置

## 典型架构层级

```
workspace/apps/web/
  src/
  api/              # 接口层：路由、控制器、API 定义
  services/         # 业务逻辑层：核心业务规则
  repositories/     # 数据访问层：数据获取、状态管理
  tests/            # 测试目录：单元测试、组件测试
```

质量规则：最大文件行数 300，最大函数行数 50，禁止使用 console.log。
