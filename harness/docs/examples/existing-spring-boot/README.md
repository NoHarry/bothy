# existing-spring-boot 预设

## 适用场景
适合 Java/Maven 或 Gradle 的单模块 Spring Boot 后端服务，三层架构（controller/service/repository）。
痛点：controller 直接依赖 repository（跳过 service 层）、System.out 散落各处难以追溯、
target/build 产物干扰架构扫描。preset 通过 drift_gate 模式鼓励改善但不阻断开发。

## 关键配置说明
- `baseline_mode: "drift_gate"`：Spring Boot 项目通常有一定历史债务，
  drift_gate 允许代码质量持平或改善时放行，只有新引入的恶化才阻断，
  适合渐进式改进，既不躺平也不过于严苛。
- `layers`：严格三层（controller → service → repository），防止跨层直接调用。
  路径需按实际包结构调整（如 controller 包不在 com/example/controller/ 时要修改 path）。
- `exclude_paths`：**/target/**、**/build/**、**/.mvn/** 必须排除；
  Maven 编译产物（target/classes、target/test-classes）不参与架构扫描。
- `generated_code_paths`：Maven 的 target/generated-sources/（如 lombok 生成的 getter/setter、
  MyBatis Mapper 接口等）应列入豁免，防止循环依赖误报。

## 接入步骤
1. 复制 harness.yaml 到项目根目录
2. 确认实际包结构，修改 layers.path（如 controller 包可能叫 web/ 而非 controller/）
3. 运行 `python scripts/lint_quality.py --baseline-init` 建立质量基线
4. 运行 `python scripts/validate.py` 确认 exit 0
5. 运行 migration-roadmap skill 制定重构路线图（如有必要拆分 service 层）
6. 逐步清理 System.out 散落点，迁移到统一 logger

## 常见坑
- Spring Boot 项目若有 @MapperScan 动态生成的 MyBatis Mapper 接口，
  路径一般在 target/generated-sources/，漏列在 generated_code_paths 会导致大量架构误报。
- 遗留项目中部分老旧 service 可能直接拼 SQL（越过 repository），
  这些文件在未改造前会产生架构违规，用 baseline 模式暂时忽略是合理的。
- Maven 多模块项目（multi-module）不适用此预设，应参考 Android 多模块预设或单独配置 platforms.*。