# Java Harness Example

## 适用场景

Java 技术栈适用于以下场景：

- 企业级后端服务（Spring Boot、Spring MVC）
- 大型分布式系统
- Android 应用后端
- 微服务架构
- 金融、电信等高可靠性系统

Java 新项目默认放在 `workspace/apps/server/` 下，并在平台目录内遵循 Maven/Gradle 约定的标准目录结构，主代码在 `src/main/java`，测试代码在 `src/test/java`，使用包名组织代码层次。

## 关键字段解释

| 字段 | 说明 |
|------|------|
| `project.language: java` | 标识项目为 Java 技术栈 |
| `platforms.server.path: "workspace/apps/server"` | 服务端代码所在的平台根目录 |
| `validation.build.enabled: true` | 启用构建验证，执行 mvn compile |
| `validation.test.enabled: true` | 启用测试验证，执行 mvn test |
| `architecture.layers` | 使用包名路径：com/example/api/service/repository |
| `quality.forbidden_patterns` | 禁止使用 System.out.print，须使用 Logger |

## 如何拷贝使用

1. 复制 `harness/docs/examples/java/harness.yaml` 到项目根目录
2. 重命名为 `harness.yaml`
3. 根据项目实际情况修改以下字段：
   - `project.name`：项目名称
   - `architecture.layers.path`：按实际包名结构调整（如 `com/mycompany/api/`）
   - `validation.build.command`：Gradle 项目改为 `gradle build`
4. 运行 `python scripts/validate.py` 验证配置

## 典型架构层级

```
workspace/apps/server/src/main/java/com/example/
  api/              # 接口层：Controller、REST 端点
  service/          # 业务逻辑层：Service 类、业务规则
  repository/       # 数据访问层：Repository、DAO
workspace/apps/server/src/test/java/com/example/  # 测试代码
```

质量规则：最大文件行数 300，最大函数行数 50，禁止使用 System.out.print。
