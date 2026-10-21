#!/bin/bash

# 日志输出函数
log_info() {
    echo "[INFO] $1"
}

log_error() {
    echo "[ERROR] $1" >&2
}

log_success() {
    echo "[✓] $1"
}

# 警告日志
log_warn() {
    echo "[WARN] $1"
}

# 检查是否在git仓库
check_git_repo() {
    if ! git rev-parse --git-dir > /dev/null 2>&1; then
        log_error "当前目录不是git仓库"
        return 1
    fi
    return 0
}

# 获取当前分支名
get_current_branch() {
    git rev-parse --abbrev-ref HEAD
}

# 检查是否有未提交更改
has_uncommitted_changes() {
    if [ -n "$(git status --porcelain)" ]; then
        return 0  # true - 有改动
    else
        return 1  # false - 无改动
    fi
}

# 获取git用户名
get_git_user() {
    git config user.name
}

# 获取git邮箱
get_git_email() {
    git config user.email
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
    local stash_name="sync-latest-$(date +%s)"
    if git stash push -m "$stash_name" --include-untracked; then
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
