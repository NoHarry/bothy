# Kotlin Harness Example

## 适用场景

Kotlin 技术栈适用于以下场景：

- Android 应用开发（官方推荐语言）
- Spring Boot 后端服务
- 跨平台 JVM 项目
- 需要空安全特性的项目
- 与 Java 混合开发的项目

Kotlin 新项目默认放在 `workspace/apps/server/` 下，并在平台目录内使用与 Java 类似的 Gradle 目录结构（`src/main/kotlin`），可与 Java 代码无缝互操作。Android 相关配置在场景 B 中定义，场景 A 样例不包含 Android 特有配置。

## 关键字段解释

| 字段 | 说明 |
|------|------|
| `project.language: kotlin` | 标识项目为 Kotlin 技术栈 |
| `platforms.server.path: "workspace/apps/server"` | 服务端代码所在的平台根目录 |
| `validation.build.enabled: true` | 启用构建验证，执行 gradle build |
| `validation.test.enabled: true` | 启用测试验证，执行 gradle test |
| `architecture.layers` | 使用包名路径：com/example/api/service/repository |
| `quality.forbidden_patterns: []` | Kotlin 无强制禁止模式，可按需添加 |

## 如何拷贝使用

1. 复制 `harness/docs/examples/kotlin/harness.yaml` 到项目根目录
2. 重命名为 `harness.yaml`
3. 根据项目实际情况修改以下字段：
   - `project.name`：项目名称
   - `architecture.layers.path`：按实际包名结构调整
   - `validation.build.command`：Maven 项目改为 `mvn compile`
4. 运行 `python scripts/validate.py` 验证配置

## 典型架构层级

```
workspace/apps/server/src/main/kotlin/com/example/
  api/              # 接口层：Controller、REST 端点
  service/          # 业务逻辑层：Service 类、业务规则
  repository/       # 数据访问层：Repository、DAO
workspace/apps/server/src/test/kotlin/com/example/  # 测试代码
```

质量规则：最大文件行数 300，最大函数行数 50。Kotlin 场景 A 样例无强制禁止模式。
