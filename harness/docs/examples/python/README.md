# Python Harness Example

## 适用场景

Python 技术栈适用于以下场景：

- 数据处理与分析脚本
- 后端 REST API 服务（FastAPI、Flask、Django）
- 自动化工具与命令行工具
- 机器学习与深度学习模型服务
- 胶水代码与系统集成

Python 新项目默认放在 `workspace/apps/server/` 下，平台目录内的 `src/` 按功能分层，`tests/` 存放测试代码。

## 关键字段解释

| 字段 | 说明 |
|------|------|
| `project.language: python` | 标识项目为 Python 技术栈 |
| `platforms.server.path: "workspace/apps/server"` | 服务端代码所在的平台根目录 |
| `validation.build.enabled: false` | Python 通常无编译步骤，跳过构建验证 |
| `validation.test.enabled: false` | 测试默认关闭，按需启用 pytest |
| `architecture.layers` | 定义 api / service / repository 三层结构 |
| `quality.forbidden_patterns` | 禁止使用 print()，须使用 logging 模块 |

## 如何拷贝使用

1. 复制 `harness/docs/examples/python/harness.yaml` 到项目根目录
2. 重命名为 `harness.yaml`
3. 根据项目实际情况修改以下字段：
   - `project.name`：项目名称
   - `architecture.layers.path`：按实际目录结构调整
   - `validation.test.command`：启用测试并指定命令（如 `pytest`）
4. 运行 `python scripts/validate.py` 验证配置

## 典型架构层级

```
workspace/apps/server/
  src/
  api/              # 接口层：路由、视图、请求处理
  services/         # 业务逻辑层：核心业务规则
  repositories/     # 数据访问层：数据库操作、外部 API 调用
  tests/            # 测试目录：单元测试、集成测试
```

质量规则：最大文件行数 300，最大函数行数 50，禁止使用 print()。
