# existing-react-spa 预设

## 适用场景
适合 TypeScript/React 单页应用，前端分层（pages/components/hooks/services）。
痛点：pages 直接 import 组件内部实现（绕过 hooks/services 层）、node_modules 干扰扫描、
build 产物误入架构分析。preset 通过 baseline 模式容纳存量债务，逐步改善前端架构。

## 关键配置说明
- `baseline_mode: "baseline"`：React 项目历史包袱多（class component 混用、hooks 滥用），
  用基线模式先记录现状，阻止新的架构违规引入，不要求清空历史债务。
- `layers`：四层结构（pages → components → hooks → services），禁止跨层直接调用。
  pages 是最上层只能被 router 调用，不能直接依赖 data 层。
  hooks 是 React 特有层，用于共享状态逻辑，不属于 components 也不是 services。
  services 是数据请求层（axios/fetch 封装），禁止被 pages 直接调用。
- `exclude_paths`：**/node_modules/**、**/build/**、**/.next/**、**/dist/** 必须排除；
  这些目录包含大量第三方代码和编译产物，不参与架构检查。
- `generated_code_paths`：Next.js 的 .next/、Vite 的 dist/ 缓存，
  React 的 node_modules/.cache/ 不参与架构扫描。

## 接入步骤
1. 复制 harness.yaml 到项目根目录
2. 检查实际 src/ 结构：确认 pages/components/hooks/services 路径（如是否在 src/ 下）
3. 运行 `python scripts/lint_quality.py --baseline-init` 建立质量基线
4. 运行 `python scripts/validate.py` 确认 exit 0
5. 运行 migration-roadmap skill 制定组件拆分和 hooks 规范化路线图
6. 逐步清理 console.log 散落点，统一使用 @ logger 封装

## 常见坑
- React 项目中 useEffect/useCallback 滥用可能导致巨型 hooks 文件，
  max_function_lines: 50 对复杂 hooks 可能过严，按需调整为 80-100。
- node_modules/.cache/ 在不同构建工具下路径略有差异，
  若 lint_deps.py 报 generated_code_paths 解析错误，需对照实际缓存目录调整。
- 遗留 React 项目可能有 pages 直接 import services 而非通过 hooks 的情况，
  这种情况在 baseline 模式下会被记录但暂不阻断，可在后续重构中逐步调整。