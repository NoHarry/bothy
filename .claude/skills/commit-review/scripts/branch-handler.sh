#!/bin/bash

# 导入依赖
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/config.sh"
source "$SCRIPT_DIR/utils.sh"

# 检查远程分支是否存在
# 参数: 分支名
remote_branch_exists() {
    local branch="$1"
    git ls-remote --heads origin "$branch" | grep -q "refs/heads/$branch"
}

# 交互式处理 cherry-pick 冲突
# 参数: 临时分支名 提交hash
handle_cherry_pick_conflict() {
    local temp_branch="$1"
    local commit_hash="$2"

    log_error "在临时分支 $temp_branch 上 cherry-pick $commit_hash 出现冲突。"

    # 非交互模式检测（BATCH_NON_INTERACTIVE=1 或 stdin 不是终端）
    # 保留冲突状态退出，由AI报告冲突并等待用户手动解决
    if [ "${BATCH_NON_INTERACTIVE:-0}" = "1" ] || [ ! -t 0 ]; then
        log_error "非交互模式，保留冲突状态退出。请手动解决冲突后执行 git add 和 git cherry-pick --continue。"
        return 1
    fi

    while true; do
        echo "请选择冲突处理方式:"
        echo "  [c] 解决冲突后继续 cherry-pick"
        echo "  [s] 跳过当前 cherry-pick"
        echo "  [a] 中止 cherry-pick"
        read -r -p "输入选项(c/s/a): " choice

        case "$choice" in
            c|C|"")
                read -r -p "请先手动解决冲突并完成 git add，按 Enter 继续..."
                if git cherry-pick --continue; then
                    log_success "冲突已解决，cherry-pick 已继续完成。"
                    return 0
                fi
                log_error "cherry-pick --continue 失败，请继续处理冲突。"
                ;;
            s|S)
                if git cherry-pick --skip; then
                    log_info "已跳过当前 cherry-pick。"
                    return 0
                fi
                log_error "cherry-pick --skip 失败。"
                ;;
            a|A)
                git cherry-pick --abort
                log_error "已中止 cherry-pick。"
                return 1
                ;;
            *)
                log_error "无效选项，请输入 c/s/a。"
                ;;
        esac
    done
}

# 从提交信息中解析目标分支列表
# 格式: &branch1&branch2&branch3&（&为分隔符）
# 输出: 每行一个分支名
parse_target_branches() {
    local commit_msg="$1"
    local temp="$commit_msg"
    while [[ "$temp" =~ \&([a-zA-Z0-9_/-]+) ]]; do
        echo "${BASH_REMATCH[1]}"
        temp="${temp#*&${BASH_REMATCH[1]}}"
    done
}

# 处理目标分支：创建临时分支、cherry-pick、推送、清理
# 参数: 提交信息 提交hash [原始分支]
handle_target_branches() {
    local commit_msg="$1"
    local commit_hash="$2"
    local original_branch="${3:-$(get_current_branch)}"

    if [ -z "$commit_msg" ] || [ -z "$commit_hash" ]; then
        log_error "handle_target_branches 参数不足，需要: 提交信息 提交hash [原始分支]"
        return 1
    fi

    # 解析目标分支
    local branches=()
    while IFS= read -r branch; do
        [ -n "$branch" ] && branches+=("$branch")
    done < <(parse_target_branches "$commit_msg")

    if [ ${#branches[@]} -eq 0 ]; then
        log_info "提交信息未包含目标分支标记，跳过临时分支处理。"
        return 0
    fi

    log_info "解析到目标分支: ${branches[*]}"

    for target_branch in "${branches[@]}"; do
        local temp_branch="temp_${target_branch}=${commit_hash}"
        local pushed=false

        if remote_branch_exists "$target_branch"; then
            log_info "远程分支 $target_branch 存在，基于 origin/$target_branch 创建临时分支。"
            if ! git fetch origin "$target_branch"; then
                log_error "获取远程分支 $target_branch 失败。"
                return 1
            fi

            if ! git checkout -B "$temp_branch" "origin/$target_branch"; then
                log_error "创建临时分支 $temp_branch 失败。"
                return 1
            fi
        else
            log_warn "远程分支 $target_branch 不存在，跳过此临时分支处理。"
            continue
        fi

        log_info "已切换到临时分支 $temp_branch，开始 cherry-pick 提交 $commit_hash。"
        if ! git cherry-pick "$commit_hash"; then
            if ! handle_cherry_pick_conflict "$temp_branch" "$commit_hash"; then
                if [ "$(get_current_branch)" != "$original_branch" ]; then
                    git checkout "$original_branch" > /dev/null 2>&1
                fi
                git branch -D "$temp_branch" > /dev/null 2>&1
                return 1
            fi
        fi

        if git push --set-upstream origin "$temp_branch"; then
            pushed=true
            log_success "临时分支 $temp_branch 已推送到远程。"
        else
            log_error "推送临时分支 $temp_branch 失败。"
        fi

        if [ "$(get_current_branch)" != "$original_branch" ]; then
            if ! git checkout "$original_branch"; then
                log_error "切回原始分支 $original_branch 失败。"
                return 1
            fi
        fi

        if git show-ref --verify --quiet "refs/heads/$temp_branch"; then
            if git branch -D "$temp_branch"; then
                log_info "已删除本地临时分支 $temp_branch。"
            else
                log_error "删除本地临时分支 $temp_branch 失败。"
                return 1
            fi
        fi

        if [ "$pushed" != true ]; then
            return 1
        fi
    done

    return 0
}

# 主函数
main() {
    local action="$1"
    shift

    case "$action" in
        handle_target_branches)
            handle_target_branches "$@"
            ;;
        *)
            log_error "未知操作: $action"
            echo "用法: $0 handle_target_branches <提交信息> <提交hash> [原始分支]"
            exit 1
            ;;
    esac
}

if [ "${BASH_SOURCE[0]}" == "${0}" ]; then
    main "$@"
fi
