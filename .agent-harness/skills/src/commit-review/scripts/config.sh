#!/bin/bash

# 代码提交评审工具配置

# 评分阈值
PASS_THRESHOLD=80

# 支持的提交类型
VALID_TYPES=(feat fix docs style refactor perf test chore)

# 项目根目录（用于读取项目级 .review/config.sh）
_CONFIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_PROJECT_ROOT="$(cd "$_CONFIG_DIR/../../../.." && pwd)"

# 提交信息格式正则（分支格式: &branch1&branch2&branch3&，&为分隔符）
COMMIT_MSG_REGEX='^(feat|fix|docs|style|refactor|perf|test|chore)(\([a-zA-Z0-9_-]+\))?: .+(#TB-[0-9]+#)?((&[a-zA-Z0-9_/-]+)+&)?$'

# 评审模型名称（仅用于终端摘要；commit-review 不再写入共享 CSV 日志）
REVIEW_MODEL="unknown"

# 同步代码时拉取的分支（主模块和子模块都使用此分支）
# 自动探测远程主干分支（develop > main > master），可在项目 .review/config.sh 中覆盖
_detect_sync_branch() {
    for _b in develop main master; do
        if git ls-remote --heads origin "$_b" 2>/dev/null | grep -q "refs/heads/$_b"; then
            echo "$_b"; return
        fi
    done
    # fallback: upstream 分支
    git rev-parse --abbrev-ref --symbolic-full-name "@{u}" 2>/dev/null | sed 's|origin/||' || echo "main"
}
MAIN_SYNC_BRANCH="$(_detect_sync_branch)"
SUBMODULE_SYNC_BRANCH="$MAIN_SYNC_BRANCH"

# 允许项目通过 .review/config.sh 覆盖分支配置（放在此处 source，使其优先级最高）
_REVIEW_CONFIG="$_PROJECT_ROOT/.review/config.sh"
[ -f "$_REVIEW_CONFIG" ] && source "$_REVIEW_CONFIG"
unset _REVIEW_CONFIG _b

# 评审复杂度路由阈值
LIGHT_REVIEW_THRESHOLD=10      # ≤10行改动 → 轻量评审
DEEP_REVIEW_THRESHOLD=200      # >200行改动 → 深度评审
DEEP_REVIEW_FILE_THRESHOLD=5   # >5个文件改动 → 深度评审
