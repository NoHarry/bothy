#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/config.sh"
source "$SCRIPT_DIR/utils.sh"

# 获取git状态，返回改动文件列表
# 输出: 文件1 文件2 ...
get_status() {
    git status --porcelain | awk '{print $2}'
}

# 获取diff内容
# 输出: diff字符串
get_diff() {
    local cached=$(git diff --cached)
    if [ -n "$cached" ]; then
        echo "$cached"
    else
        git diff
    fi
}

# 获取改动统计
# 输出: "文件数 新增行数 删除行数"
get_stats() {
    local stats=$(git diff --cached --stat | tail -1)
    if [ -z "$stats" ]; then
        stats=$(git diff --stat | tail -1)
    fi
    echo "$stats"
}

# 暂存并提交
# 参数: 提交信息
stage_and_commit() {
    local message="$1"
    git add -A
    git commit -m "$message"
    return $?
}

# 推送到远程
# 参数: 分支名
push_branch() {
    local branch="$1"
    if git ls-remote --heads origin "$branch" | grep -q "$branch"; then
        git push origin "$branch"
    else
        git push --set-upstream origin "$branch"
    fi
    return $?
}

# 获取当前提交hash
get_commit_hash() {
    git rev-parse HEAD
}

# 获取子模块列表
# 输出: 子模块路径列表
get_submodules() {
    git submodule --quiet foreach 'echo "$name:$path"'
}

# 检查是否有未提交更改
has_changes() {
    if [ -n "$(git status --porcelain)" ]; then
        return 0  # 有更改
    else
        return 1  # 无更改
    fi
}

# 暂存更改，返回stash名称
stash_changes() {
    local stash_name="commit-review-$(date +%s)"
    if git stash push -m "$stash_name"; then
        echo "$stash_name"
        return 0
    else
        return 1
    fi
}

# 恢复暂存的更改
pop_stash() {
    git stash pop
}

# 拉取最新代码
pull_latest() {
    local branch=$(git rev-parse --abbrev-ref HEAD)
    git pull origin "$branch"
}

# 主函数 - 被Agent调用
main() {
    local action="$1"
    shift
    
    case "$action" in
        status)
            get_status
            ;;
        diff)
            get_diff
            ;;
        stats)
            get_stats
            ;;
        commit)
            stage_and_commit "$@"
            ;;
        push)
            push_branch "$@"
            ;;
        hash)
            get_commit_hash
            ;;
        submodules)
            get_submodules
            ;;
        has-changes)
            has_changes
            ;;
        stash)
            stash_changes
            ;;
        pull)
            pull_latest
            ;;
        pop)
            pop_stash
            ;;
        *)
            log_error "未知操作: $action"
            exit 1
            ;;
    esac
}

# 如果是直接执行（非source），调用主函数
if [ "${BASH_SOURCE[0]}" == "${0}" ]; then
    main "$@"
fi