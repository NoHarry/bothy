---
name: sync-latest
description: 拉取主模块和所有子模块的最新代码。自动处理未提交更改（stash → pull → pop），自动解决冲突。当用户提到拉取代码、同步代码、更新代码时激活此Skill。
license: MIT
metadata:
  author: noharry
  team: infra
  version: "1.0.0"
---

# Sync Latest Skill

自动拉取主模块和所有子模块的最新代码，处理未提交更改和冲突。

## 何时使用

当用户有以下意图时激活此Skill：
- 提到 "拉取代码", "同步代码", "更新代码", "pull"
- 输入 `/sync-latest`
- 需要在提交前同步最新代码

## 工作流程

### 1. 同步主模块

```
如果有未提交更改:
  ├── 自动 stash 更改
  ├── 拉取配置分支最新代码 (git pull --rebase)
  └── 自动 pop stash
      └── 如果有冲突:
          ├── 提示用户解决冲突
          └── 用户解决后自动完成 git add / rebase --continue

如果没有未提交更改:
  └── 直接拉取配置分支最新代码
```

### 2. 同步所有子模块

> **若项目无子模块，此步骤自动跳过。**

对每个子模块执行相同流程：

```
切换到配置分支 (SUBMODULE_SYNC_BRANCH)
├── 拉取最新代码
├── 切换回原分支
└── rebase 到最新代码
    └── 如果有冲突:
        ├── 提示用户解决冲突
        └── 自动完成后续操作
```

## 使用方式

### 命令行

```bash
# 同步所有模块
bash .agents/skills/sync-latest/scripts/sync-latest.sh

# 或
/sync-latest
```

### 配置

`config.sh` 会**自动探测**远程主分支，优先级：`develop` > `main` > `master`。大多数情况下无需手动配置。

**手动覆盖（可选）**：在项目根目录创建 `.review/config.sh` 文件：

```bash
# .review/config.sh（项目根目录，按需创建）
MAIN_SYNC_BRANCH="your-branch"       # 覆盖主模块同步分支
SUBMODULE_SYNC_BRANCH="your-branch"  # 覆盖子模块同步分支
```

**无子模块项目**：`sync-latest.sh` 会自动检测 `.gitmodules`，若无子模块则跳过步骤2，仅同步主模块。

## 冲突处理

当拉取或 pop stash 时产生冲突：

```
═══════════════════════════════════════════════════════
           检测到冲突
═══════════════════════════════════════════════════════

冲突文件:
  - src/main/java/com/example/File.java
  - src/main/resources/config.properties

请选择处理方式:
  [s] 解决冲突 - 编辑冲突文件后，SKILL自动完成后续操作
  [a] 中止操作 - 放弃本次同步

输入选项(s/a):
```

用户选择 `s` 后：
1. 用户编辑冲突文件
2. 保存后按 Enter
3. SKILL 自动执行：`git add .` 和 `git rebase --continue`

## 脚本说明

| 脚本 | 功能 |
|------|------|
| `scripts/sync-latest.sh` | 主脚本，同步所有模块 |
| `scripts/config.sh` | 配置同步分支 |
| `scripts/utils.sh` | 工具函数（log, stash等） |

## 依赖

- 需要安装 git
- 需要在主模块根目录执行
- 子模块需要已初始化 (`git submodule update --init`)
