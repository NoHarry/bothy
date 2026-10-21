# doc-local

当 `doc/` 作为 git submodule 共用时（同一 doc 仓库在多个平台仓库间共享），供项目使用的本地写入目录。

## 为何存在

当 `doc/` 是一个共享的 git submodule（同一 doc 仓库在多个平台仓库间共享），直接将中间文件和生成文件写入 `doc/` 会：
- 用仓库特定的内容弄脏 submodule
- 在各平台仓库间造成合并冲突
- 将平台特定的笔记与共享需求混在一起

## 工作原理

```
harness.yaml:
  doc:
    mode: submodule    ← 触发此行为

读取路径：  doc/features/{feature}/outputs/       ← 共享 submodule（只读）
写入路径：  harness/doc-local/{feature}/           ← 此目录（仓库本地）
```

## 结构

```
harness/doc-local/
└── {feature-name}/
    ├── intermediates/      # 此仓库的评审讨论记录
    │   ├── progress.md
    │   ├── decisions.md
    │   ├── issues.md
    │   ├── problems.md
    │   └── learnings.md
    └── generated/          # 此仓库的草稿输出
```

## 跨仓库场景

```
repo-android/           repo-ios/              repo-harmony/
├── doc/ (submodule) ←→ doc/ (submodule) ←→    doc/ (submodule)     ← 同一 git 仓库
├── harness/             ├── harness/           ├── harness/
│   └── doc-local/       │   └── doc-local/     │   └── doc-local/   ← 每个仓库各自独立
│       └── my-app/      │       └── my-app/    │       └── my-app/
└── src/ (Android)       └── src/ (iOS)         └── src/ (HarmonyOS)
```

每个平台仓库从 doc submodule 读取共享的 functional-spec.md，但将各自的中间文件和生成文件保存在本地。

（文件结束 — 共 48 行）