# 代码评审规则

本目录存放项目级的 commit-review 评审规则。

## 目录结构

```
.review/
├── rules/
│   ├── README.md              ← 本文件
│   ├── example-template.md    ← 格式模板（参考用，不参与实际评审）
│   ├── harness-sync.md        ← harness 同步与提交入口规则
│   └── <your-rules>.md        ← 项目实际规则文件
└── config.sh                  ← 可选：分支配置
```

## 使用方式

1. 参考 `example-template.md` 的格式创建项目规则文件
2. 按技术栈命名，如 `android-kotlin.md`、`python-fastapi.md`、`react-typescript.md`
3. commit-review skill 执行时会自动加载本目录下所有 `.md` 规则文件（排除 README 和 example-template）

注意：本目录规则只有通过 `commit-review` skill 才会被加载。项目必须配合本地 hooks 和提交流程要求，避免开发者绕过评审入口。

## 规则优先级

| 优先级 | 来源 | 说明 |
|--------|------|------|
| 高 | 项目规则 (`.review/rules/*.md`) | 匹配时使用规则自身的扣分值 |
| 低 | 通用评审矩阵 | 未匹配项目规则时使用通用评分 |

## 规则数量建议

- 小型项目：10-15 条
- 中型项目：15-20 条
- 大型项目：20 条以内

聚焦高频、高危问题，避免规则过于冗长。
