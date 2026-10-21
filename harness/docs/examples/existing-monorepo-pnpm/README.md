# existing-monorepo-pnpm 预设

## 适用场景
适合 pnpm workspace 多包管理的前端/全栈 monorepo，包含 web、api、shared 等多个子包。
痛点：跨包循环依赖难以检测（packages/web → packages/shared → packages/api → packages/web）、
node_modules/build 干扰单包扫描、turborepo/.turbo 缓存目录误入分析。
preset 通过 platforms 多平台配置 + drift_gate 模式支持跨包架构检测和渐进改进。

## 关键配置说明
- `baseline_mode: "drift_gate"`：monorepo 跨包依赖复杂，drift_gate 允许持平或改善时放行，
  只有新引入的跨包循环依赖或架构违规才阻断，适合多团队协作的渐进式改造。
- `platforms.*`：为每个 workspace 包单独配置 path/build/test；
  platforms.web.path: packages/web、platforms.api.path: packages/api。
  build/test 命令使用 pnpm --workspace 限定作用域。
- `layers`：monorepo 中 web 包遵循 pages → components → hooks → services 层级；
  api 包单独一层（level: 4），与 web 的 services 层通过 HTTP/RPC 通信。
- `exclude_paths`：**/node_modules/**、**/.turbo/** 必须排除；
  pnpm 的 node_modules 采用软链接，.turbo 是 turborepo 构建缓存，都不参与架构扫描。
- `generated_code_paths`：各包的 .next/dist/node_modules/.cache/ 应列入豁免。

## 接入步骤
1. 复制 harness.yaml 到项目根目录
2. 对照 pnpm-workspace.yaml 确认实际包名，更新 platforms.* 和 --workspace= 包名
3. 检查 packages/web/src/ 和 packages/api/src/ 实际结构，调整 layers.path
4. 运行 `python scripts/lint_quality.py --baseline-init` 建立质量基线
5. 运行 `python scripts/validate.py` 确认 exit 0
6. 运行 migration-roadmap skill 制定跨包依赖规范和循环依赖治理路线图

## 常见坑
- pnpm workspace 中软链接导致的路径解析问题：
  lint_deps.py 若无法正确解析跨包依赖，需检查 packages/*/node_modules 是否存在，
  或在 gradle_modules 类字段中手动列出所有包的 path（harness.yaml 中 platforms 已配置）。
- monorepo 首次建立 baseline 时，各包的 node_modules 尚未完全安装，
  可能导致 generated_code_paths 解析不完整，建议先 pnpm install 再跑基线。
- 跨包循环依赖（如 @my/web 引用 @my/api，@my/api 引用 @my/web）在 drift_gate 下会报错，
  但若 A → B → C → A 的长链循环，drift_gate 可能检测不到，需要在 architecture.layers 中
  明确各包层级关系来辅助检测。