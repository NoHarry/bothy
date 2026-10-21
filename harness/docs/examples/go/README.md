# Go Harness Example

## 适用场景

Go 技术栈适用于以下场景：

- 云原生微服务（Kubernetes、Docker）
- 命令行工具与 DevOps 工具
- 网络编程与 API 网关
- 高性能服务器组件
- 分布式系统与中间件

Go 新项目默认放在 `workspace/apps/server/` 下，并在平台目录内遵循标准 Go 布局：`cmd/` 存放可执行文件入口，`internal/` 存放内部包，`pkg/` 存放可共享的公共包。Go 编译器直接构建为单可执行文件，无须 JVM 或其他运行时依赖。

## 关键字段解释

| 字段 | 说明 |
|------|------|
| `project.language: go` | 标识项目为 Go 技术栈 |
| `platforms.server.path: "workspace/apps/server"` | 服务端代码所在的平台根目录 |
| `validation.build.enabled: true` | 启用构建验证，执行 go build ./... |
| `validation.test.enabled: true` | 启用测试验证，执行 go test ./... |
| `architecture.layers` | cmd/api 为入口，internal/ 分层服务与仓库 |
| `quality.forbidden_patterns` | 禁止使用 fmt.Print，须使用 log 或自定义 logger |

## 如何拷贝使用

1. 复制 `harness/docs/examples/go/harness.yaml` 到项目根目录
2. 重命名为 `harness.yaml`
3. 根据项目实际情况修改以下字段：
   - `project.name`：项目名称
   - `architecture.layers.path`：按实际目录结构调整（如 `cmd/server/`、`internal/handler/`）
   - `validation.build.command`：按需添加构建产物路径
4. 运行 `python scripts/validate.py` 验证配置

## 典型架构层级

```
workspace/apps/server/
  cmd/
  api/              # 可执行文件入口：main.go
  internal/
  service/          # 业务逻辑层：核心业务规则
  repository/       # 数据访问层：数据库操作、缓存
  pkg/              # 可选：共享公共包，可被外部引用
```

质量规则：最大文件行数 300，最大函数行数 50，禁止使用 fmt.Print。
