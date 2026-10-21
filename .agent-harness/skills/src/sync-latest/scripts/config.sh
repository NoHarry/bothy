#!/bin/bash

# 代码提交评审工具配置

# 评分阈值
PASS_THRESHOLD=80

# 支持的提交类型
VALID_TYPES=(feat fix docs style refactor perf test chore)

# 项目根目录（用于读取项目级 .review/config.sh）
_SL_CONFIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_SL_PROJECT_ROOT="$(cd "$_SL_CONFIG_DIR/../../../../" && pwd)"

# 提交信息格式正则
COMMIT_MSG_REGEX='^(feat|fix|docs|style|refactor|perf|test|chore)(\([a-zA-Z0-9_-]+\))?: .+(#TB-[0-9]+#)?(&[a-zA-Z0-9_/-]+&)*$'

# 评审模型名称（仅保留兼容变量；sync-latest 不写提交日志）
REVIEW_MODEL="unknown"

# 同步代码时拉取的分支（主模块和子模块都使用此分支）
# 自动探测远程主干分支（develop > main > master），可在项目 .review/config.sh 中覆盖
_detect_sync_branch() {
    for _b in develop main master; do
        if git ls-remote --heads origin "$_b" 2>/dev/null | grep -q "refs/heads/$_b"; then
            echo "$_b"; return
        fi
    done
    git rev-parse --abbrev-ref --symbolic-full-name "@{u}" 2>/dev/null | sed 's|origin/||' || echo "main"
}
MAIN_SYNC_BRANCH="$(_detect_sync_branch)"
SUBMODULE_SYNC_BRANCH="$MAIN_SYNC_BRANCH"

# 允许项目通过 .review/config.sh 覆盖分支配置（放在此处 source，使其优先级最高）
_REVIEW_CONFIG="$_SL_PROJECT_ROOT/.review/config.sh"
[ -f "$_REVIEW_CONFIG" ] && source "$_REVIEW_CONFIG"
unset _SL_CONFIG_DIR _SL_PROJECT_ROOT _REVIEW_CONFIG _b
