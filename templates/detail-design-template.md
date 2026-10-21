# 详细设计：{Feature/Module Name}

## 元数据
- **日期**：{YYYY-MM-DD}
- **作者**：{name or agent}
- **概要设计**：{link to summary design}
- **状态**：{draft | review | approved}

## 组件：{Component Name}

### 接口 / API
{Public methods, endpoints, or exports with signatures}

### 内部结构
{Key internal classes, functions, data structures}
{How they relate to each other}

### 算法/逻辑
{Step-by-step description of complex logic}
{Pseudocode if needed}

### 错误处理
| 错误条件 | 响应 | 恢复 |
|----------------|----------|----------|
| {condition} | {what happens} | {how to recover} |

### 配置
| 参数 | 类型 | 默认值 | 描述 |
|-----------|------|---------|-------------|
| {name} | {type} | {value} | {what it controls} |

### 依赖
- {Dependency}：{why needed, version constraints}

---

{Repeat "## Component:" section for each component}

## 集成说明
{How components connect. Sequence of initialization. Shutdown order.}

## 迁移/回滚计划
{If replacing existing functionality: how to migrate, how to rollback if issues}

## 测试策略
{What types of tests are needed. Key scenarios to cover. Performance benchmarks.}