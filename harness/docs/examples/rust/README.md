# Rust Harness Example

## 适用场景

Rust 技术栈适用于以下场景：

- 系统级编程（操作系统、驱动程序）
- 高性能网络服务
- WebAssembly 应用
- 命令行工具
- 嵌入式开发
- 安全关键的组件

Rust 新项目默认放在 `workspace/apps/server/` 下，并在平台目录内遵循标准 crate 布局。`src/` 目录存放库代码或二进制目标，`tests/` 目录存放集成测试。Rust 以其内存安全性和零成本抽象著称。

## 关键字段解释

| 字段 | 说明 |
|------|------|
| `project.language: rust` | 标识项目为 Rust 技术栈 |
| `platforms.server.path: "workspace/apps/server"` | 服务端代码所在的平台根目录 |
| `validation.build.enabled: true` | 启用构建验证，执行 cargo build |
| `validation.test.enabled: true` | 启用测试验证，执行 cargo test |
| `architecture.layers` | `workspace/apps/server/src/` 下分 api / service / repository 子模块 |
| `quality.forbidden_patterns` | 禁止使用 println!，须使用 log 或自定义 logger |

## 如何拷贝使用

1. 复制 `harness/docs/examples/rust/harness.yaml` 到项目根目录
2. 重命名为 `harness.yaml`
3. 根据项目实际情况修改以下字段：
   - `project.name`：项目名称
   - `architecture.layers.path`：按实际模块结构调整（如 `workspace/apps/server/src/handlers/`、`workspace/apps/server/src/models/`）
   - `validation.build.command`：按需添加发布构建（如 `cargo build --release`）
4. 运行 `python scripts/validate.py` 验证配置

## 典型架构层级

```
workspace/apps/server/
  src/
  api/              # 接口层：路由处理、请求响应
  service/          # 业务逻辑层：核心业务规则
  repository/       # 数据访问层：数据库操作、持久化
  tests/            # 集成测试目录
```

质量规则：最大文件行数 300，最大函数行数 50，禁止使用 println!。
