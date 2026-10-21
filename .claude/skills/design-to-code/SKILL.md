---
name: design-to-code
description: 基于设计文档实现代码，支持可选的设计工具集成（Figma、MasterGo 或静态设计文件）生成 UI 组件。在 ref-to-design 之后或有设计文档时使用。当用户提到"实现设计"、"根据设计写代码"、"design to code"、"UI 实现"时激活此Skill。
license: MIT
metadata:
  author: noharry
  team: infra
  version: "1.0.0"
---

# Skill: Design to Code

> 从设计文档实现代码并完成完整验证。支持可选的设计工具集成（Figma、MasterGo 或静态设计文件）生成 UI 组件。在 ref-to-design 之后或存在设计文档时使用。

## 何时使用
- 设计文档存在且已审批
- 实现新功能或模块
- 将规格说明转化为可工作代码
- 有设计资源可用的 UI 实现（任意来源）

## 前置条件
- 设计文档（摘要 + 详情）存在于 `docs/design/{design-area}/`
- doc/features/{feature}/outputs/ 中的 functional-spec.md（由 prd-review 产出）
- CONSTRAINTS.md 为最新
- harness.yaml 配置了 build/test 命令
- 架构层级已定义
- （可选）来自任意来源的设计资源：Figma、MasterGo、静态文件（PNG/SVG/PDF）或带标注的截图

## 工作流程

### 步骤 1: 加载设计文档
- 解析 feature 文档路径：从 harness.yaml 读取 `doc.mode`
  - mode=local → 从 `{doc.path}/features/{feature}/outputs/` 读取 functional-spec.md
  - mode=submodule → 从 `{doc.path}/features/{feature}/outputs/` 读取 functional-spec.md（读取路径相同，submodule 已挂载）
- 确定 `design-area`：优先使用用户指定值、handoff、WORKFLOW/CONSTRAINTS 或项目 overlay 中的 domain/module；否则使用 `{feature}`
- 从 `docs/design/{design-area}/summary-design.md` 和 `docs/design/{design-area}/detail-design.md` 读取摘要和详情设计文档
- 兼容旧项目：如果 `docs/design/{design-area}/` 不存在但 `doc/features/{feature}/outputs/summary-design.md` 和 `detail-design.md` 存在，可以只读消费一次，并在版本报告中标记迁移建议；新写入或更新必须落到 `docs/design/{design-area}/`
- 从 feature outputs 路径读取 functional-spec.md
- **平台适用性检查**：从 harness.yaml 读取 `project.platform`，然后对每个 SPEC 条目：
  - `Type: native-ui` → 使用当前平台的 UI 框架实现
  - `Type: CRUD | workflow | integration | query | event` → 实现（业务逻辑，适用于所有平台）
  - 如果某个 SPEC 明确描述了当前平台不可用的平台特定原生 API → 跳过并在版本报告中注明
- 提取：待构建的组件、接口、依赖、数据流
- 从适用的规格说明创建实现检查清单

### 步骤 2: 检测设计来源
扫描设计文档、functional-spec.md 和 `doc/features/{feature}/src/` 中的设计资源引用。确定**设计来源类型**：

| 来源类型 | 检测方式 | 集成方法 |
|-------------|-----------|-------------------|
| **Figma** | 文档中匹配 `figma.com/design/` 或 `figma.com/file/` 的 URL | Figma MCP 工具（在 OpenCode 环境中加载） |
| **MasterGo** | 文档中匹配 `mastergo.com/` 的 URL | MasterGo MCP 工具（在 OpenCode 环境中加载） |
| **静态文件** | `doc/features/{feature}/src/designs/` 中的图片/PDF/SVG 文件或在文档中引用 | 视觉分析 + 标注解析 |
| **标记规格** | 包含尺寸、颜色、布局描述的 Markdown/HTML 设计规格 | 从文档解析 |
| **无** | 未找到设计资源 | 跳过设计提取，仅从逻辑规格实现 |

> **注意**：基于 MCP 的设计工具（Figma、MasterGo 等）在 OpenCode/IDE 环境中加载和配置，而非在 harness.yaml 中。代理通过扫描文档链接和文件目录来发现设计来源。

- **如果未找到设计来源** → 跳过步骤 3-5，进入步骤 6
- **如果找到设计来源** → 使用适当方法进入步骤 3

### 步骤 3: 提取设计数据
使用与检测到的来源类型匹配的方法：

#### 3a: 基于 MCP 的工具（Figma / MasterGo）
使用设计工具的 MCP 读取设计文件：
1. 检索完整文件结构（页面、画板、图层）
2. 提取样式定义（颜色、文本样式、效果、网格）
3. 列出可复用组件及其变体
4. 读取设计文档中引用的特定画板/组件

#### 3b: 静态设计文件（PNG/SVG/PDF/Sketch 导出）
1. 从 `doc/features/{feature}/src/designs/` 或引用的路径加载设计文件
2. 如果有标注（带有尺寸、颜色代码、间距）：
   - 解析标注获取精确值
   - 提取组件边界和层级
3. 如果无标注：
   - 使用视觉分析识别组件、布局、间距
   - 标记不确定的值以便人工确认
4. 如果图片旁边存在 CSS/样式导出（例如来自 Zeplin、MasterGo 导出）：
   - 解析样式导出获取精确的 token 值

#### 3c: 标记规格
1. 从 markdown/HTML 文档解析设计规格
2. 提取明确的尺寸、颜色、字体、间距
3. 从描述的 UI 部分构建组件列表

对于所有来源类型，提取并编目：
- **组件树**：名称、层级、嵌套、变体属性
- **设计 Token**：颜色、间距值、字体（族、大小、粗细、行高）、圆角、阴影、透明度
- **布局数据**：flex 方向、间距、padding、对齐方式
- **资源引用**：图标、图片、插图（注明需要的导出格式）
- **响应式行为**：断点、适应性规则（如果有定义）

### 步骤 4: 生成设计 Token
先从 `harness.yaml` 读取目标平台的 `platforms.*.path`，所有业务代码和资源都写入该平台根目录内。新项目默认平台根为 `workspace/apps/{platform}`。

在项目的样式格式中创建设计 Token 文件：

| 项目类型 | Token 格式 | 位置 |
|-------------|-------------|----------|
| CSS / HTML | CSS 自定义属性在 `:root` 中 | `{platform.path}/src/styles/tokens.css` |
| Tailwind CSS | `theme.extend` 在 tailwind.config | `{platform.path}/tailwind.config.{js,ts}` |
| React Native | StyleSheet 常量 | `{platform.path}/src/theme/tokens.{ts,js}` |
| Flutter | ThemeData / 常量 | `{platform.path}/lib/theme/tokens.dart` |
| Android (XML) | `colors.xml` + `dimens.xml` + `styles.xml` | `{platform.path}/app/src/main/res/values/` |
| iOS (Swift) | Asset catalog + 常量 | `{platform.path}/Sources/Theme/` |
| HarmonyOS (ArkTS) | Resource 文件 + 常量 | `{platform.path}/resources/` + `{platform.path}/common/` |
| Generic | JSON tokens 文件 | `{platform.path}/src/tokens.json` |

Token 包含：
- 颜色调色板（primary、secondary、neutral、semantic）
- 间距比例
- 字体比例（字体族、大小、粗细、行高）
- 圆角值
- 阴影定义
- 断点（如果有定义）

### 步骤 5: 将设计组件映射到代码组件
对于每个设计组件，确定：
- **目标代码组件名称**（来自设计文档映射或从设计层名称派生）
- **Props/参数**（来自变体和组件属性）
- **Children/slots**（来自层级结构）
- **响应式行为**（来自自动布局或标注的断点）
- **状态变体**（default、hover、active、disabled 等）

为版本报告创建组件映射表：
| 设计组件 | 来源 | 代码组件 | Props | 参考 ID |
|-----------------|--------|---------------|-------|-------------|
| {design name} | {Figma/MasterGo/file} | {code name} | {prop list} | {node ID or file ref} |

### 步骤 6: 规划实现顺序
- 按依赖关系排序组件（先构建依赖项）
- 如果有设计数据：先构建设计 token 和共享 UI 组件
- 识别可以并行 vs 顺序构建的内容
- 估算每个组件的文件数和范围
- 设置检查点间隔（每 3-5 个文件）

### 步骤 6.5: 实现 Plan Preview 前第一性原理反思

在向用户 preview 实现计划或开始创建文件前，先复核：
- 本轮实现要让哪个用户/系统结果变为可用。
- 哪些约束不可违背：设计文档、functional-spec、CONSTRAINTS、平台源码根、设计资源授权和验证命令。
- 最小可交付代码是什么，哪些 UI polish、优化或扩展测试应推迟到后续任务。
- 当前计划基于哪些设计资源和代码证据，哪些只是推断。
- 如果按当前计划实现，最可能出现的失败模式是什么；据此调整文件顺序、验证点或范围。

如存在 runtime，记录到 workpad：

```bash
python scripts/agent_runtime.py workpad --reflection "Implementation preview reflection: user/system outcome={...}; constraints={...}; smallest code deliverable={...}; evidence={...}; risks={...}; plan adjustment={...}"
```

### 步骤 7: 预验证每个文件创建
对于每个要创建的文件：
- 运行 `python scripts/verify_action.py --action "create file {path}"`
- 验证路径符合命名规范
- 验证层级放置正确
- 如果被阻塞 → 继续之前调整

### 步骤 8: 按组件实现
按顺序对每个组件：
1. 使用正确的头部/文档创建文件
2. 按设计规格实现接口/API
3. 实现内部逻辑
4. **如果组件有设计映射**：
   - 从提取的布局数据生成 UI 结构
   - 应用设计 token 代替硬编码值（引用 token 文件）
   - 从设计数据实现响应式/自适应布局
   - 添加追溯注释：`// Design: {source}:{reference_id} — {component_name}`
5. 按规格添加错误处理
6. 在文件上运行 lsp_diagnostics
7. 立即修复任何问题

### 步骤 9: 检查点验证
每 3-5 个文件后：
1. 运行 `python scripts/validate.py`
2. 如果构建失败 → 继续之前修复
3. 如果 lint-arch 失败 → 修复依赖违规
4. 用进度更新追溯条目

### 步骤 10: 集成检查
所有组件构建完成后：
- 验证组件按设计连接
- 检查导入与设计中的依赖图匹配
- 验证数据流与设计文档一致
- 如果来自设计源：验证设计 token 使用一致（无硬编码值）
- 运行完整的 `python scripts/validate.py`

### 步骤 11: 代码质量门禁
对照 CONSTRAINTS.md L3 检查：
- 文件长度在限制内
- 函数长度在限制内
- 无禁止的模式
- 公共 API 有文档注释
- 运行 `python scripts/lint_quality.py`

### 步骤 12: 编写单元测试存根
对于每个组件：
- 在适当的测试目录中创建测试文件
- 为每个公共方法/函数编写测试存根
- 对于 UI 组件：如有适用添加视觉/快照测试存根
- 用 TODO 注释标记以便 code-to-test skill 实现
- 这不是完整的测试实现（那是 code-to-test 的工作）

### 步骤 13: 最终验证
1. 运行完整的 `python scripts/validate.py`
2. 所有步骤必须通过
3. 如果失败：修复并重试（最多 3 次）
4. 如果 3 次后仍失败 → 停止、记录、请求帮助

### 步骤 14: 生成版本报告
编写简短的实现报告：
- 已实现的组件（列出文件路径）
- 每个组件的代码行数
- 约束合规状态
- 测试存根覆盖率
- 使用的设计来源（Figma / MasterGo / 静态文件 / 标记 / 无）
- 生成的设计 token（如果有设计来源）
- 组件到设计的追溯表（如果有适用）
- 任何偏离设计的地方（附理由）

## 完成标准
- [ ] 所有设计文档中的组件已实现
- [ ] 完整验证管道通过
- [ ] 代码质量门禁通过
- [ ] 所有组件存在测试存根
- [ ] 设计 token 已生成（如果有设计来源）
- [ ] 版本报告中包含组件到设计的追溯（如果有适用）
- [ ] 已生成版本报告
- [ ] 已写入追溯条目
- [ ] 没有文件超过行数限制

## 输出产物
- 实现文件
- 设计 token 文件（如果使用了设计来源）
- 测试存根文件
- 版本报告（Markdown）
- 追溯条目

## 错误处理
- **MCP 工具不可用** → 降级到静态文件解析或标记规格解析，在版本报告中注明设计数据不完整
- **设计源无法解析** → 跳过设计提取步骤，从 functional-spec.md 的逻辑规格实现，注明"无设计来源"
- **functional-spec.md 缺失** → 终止流程，提示用户在 doc/features/{feature}/outputs/ 中提供规格文档
- **设计文档缺失** → 终止流程，提示用户先运行 prd-to-design/ref-to-design 生成 `docs/design/{design-area}/summary-design.md` 和 `detail-design.md`
- **验证失败超过 3 次** → 停止流程，在版本报告中记录所有失败项和错误信息，请求人工介入
- **设计 token 与代码实现不匹配** → 重新提取设计 token 并同步到代码，必要时调整设计 token 文件
- **组件依赖循环检测** → 暂停实现，报告依赖图问题，等待用户确认解决顺序
- **文件路径冲突（已存在同名文件）** → 预验证阶段检测到后立即停止，询问用户是覆盖还是重命名

## 设计工具 MCP 参考

### Figma MCP

| Tool | Arguments | Returns |
|------|-----------|---------|
| `get_file` | fileKey | Full file structure (pages, frames, layers) |
| `get_file_styles` | fileKey | Style definitions (colors, text, effects) |
| `get_file_components` | fileKey | Component list with variants and properties |
| `get_node` | fileKey, nodeId | Specific node data (layout, style, children) |
| `get_image` | fileKey, nodeId, format | Exported image (PNG, SVG, JPG, PDF) |

### MasterGo MCP

| Tool | Arguments | Returns |
|------|-----------|---------|
| `get_file` | fileKey | Full file structure |
| `get_styles` | fileKey | Style definitions |
| `get_components` | fileKey | Component list |
| `get_node` | fileKey, nodeId | Specific node data |
| `export_image` | fileKey, nodeId, format | Exported image |

> MCP tool names may vary by provider. Consult the installed MCP server documentation for exact tool signatures.

### 静态文件格式

| Format | How to Use |
|--------|-----------|
| Annotated PNG/JPG | Visual analysis + annotation parsing for measurements |
| SVG | Parse XML for exact dimensions, colors, paths |
| PDF (design export) | Extract pages, measurements from structured PDF |
| Sketch export (JSON) | Parse layer tree, styles, components |
| CSS/style export | Direct token extraction from Zeplin/MasterGo/Figma export |

**Note**: MCP-based design tools (Figma, MasterGo, etc.) are loaded and configured in the OpenCode/IDE environment. The agent calls MCP tools directly — no harness.yaml configuration needed for design tool access.
