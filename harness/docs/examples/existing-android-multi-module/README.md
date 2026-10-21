# existing-android-multi-module 预设

## 适用场景
适合已有多个 Gradle 子模块（:app、:lib_core、:lib_router、:lib_resource 等）的 Android 项目。
痛点：模块间循环依赖难以检测、KAPT/APT 生成的代码（R.java、BuildConfig.java）被误判为架构违规、
build/ 产物干扰质量扫描。preset 通过 gradle_modules 列表 + generated_code_paths + exclude_paths 解决这些问题。

## 关键配置说明
- `gradle_modules`：列出项目所有 Gradle 子模块，供 lint_deps.py 跨模块依赖分析兜底；
  必须与 settings.gradle.kts 中的模块名完全一致（带冒号前缀）。
- `baseline_mode: "baseline"`：Android 项目历史债务多（Kotlin/Java 混编、大型 Activity），
  用基线模式允许存量违规、新增违规才报错，逐步改善。
- `generated_code_paths`：KAPT 生成的 R.java、BuildConfig.java、Room Database 等路径，
  循环依赖检测时会自动豁免这些文件，避免误报。
- `exclude_paths`：**/build/**、**/.gradle/**、**/generated/** 必须排除；
  build 目录中包含大量非业务代码，会严重干扰架构分析和质量扫描。

## 接入步骤
1. 复制 harness.yaml 到项目根目录
2. 确认 settings.gradle.kts 中的模块名，据实更新 gradle_modules 列表
3. 检查 app/src、lib_core/src、lib_router/src 等实际路径，更新 layers.path（如有差异）
4. 运行 `python scripts/lint_quality.py --baseline-init` 建立质量基线
5. 运行 `python scripts/validate.py` 确认 exit 0
6. 运行 migration-roadmap skill 制定分模块改造路线图

## 常见坑
- KAPT 生成代码路径（如 build/generated/data/***/Hilt_*.java）若不在 generated_code_paths 中，
  会产生大量误报循环依赖；务必对照实际 build 目录结构补充。
- 多模块项目首次运行 lint_deps.py 时若模块路径解析失败，
  需手动在 gradle_modules 中补全所有子模块的冒号前缀路径。
- Android 项目中部分模块仅有资源文件（lib_resource），不产生 Java/Kotlin 源文件，
  架构层级的 path 记得用 **/ui/** 而非精确路径，防止漏扫。