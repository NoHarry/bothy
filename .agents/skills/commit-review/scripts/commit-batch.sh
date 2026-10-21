#!/bin/bash

# 批量提交脚本 - 正确的提交流程：
# 1. 提交前确保子模块基于目标分支最新代码（rebase）
# 2. 提交有本地变更的子模块到开发分支
# 3. 处理子模块临时分支（如果提交信息包含 &branch&）
# 4. 更新主模块中的子模块引用
# 5. 提交主模块
# 6. 处理主模块临时分支（如果提交信息包含 &branch&）

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/utils.sh"
source "$SCRIPT_DIR/config.sh"

# 全局变量：记录分支推送信息
declare -A MODULE_BRANCHES

# Stage code changes but never include legacy shared commit-review log files.
stage_all_except_commit_logs() {
    git add -A -- . || return 1
    git reset -q -- \
        commit_log.csv \
        commit_log.xlsx \
        harness/.local/commit_log.csv \
        harness/.local/commit_log.xlsx \
        2>/dev/null || true
}

# 检测当前项目是否有子模块
_has_submodules() {
    [ -f ".gitmodules" ] && grep -q "\[submodule" .gitmodules 2>/dev/null
}

# 检查是否有冲突
has_conflicts() {
    git diff --name-only --diff-filter=U | grep -q .
}

# 交互式处理冲突
handle_conflicts() {
    local operation="$1"

    if ! has_conflicts; then
        return 0
    fi

    log_error "检测到 $operation 冲突！"
    echo ""
    echo "冲突文件:"
    git diff --name-only --diff-filter=U | sed 's/^/  - /'
    echo ""

    # 非交互模式检测（BATCH_NON_INTERACTIVE=1 或 stdin 不是终端）
    # 保留冲突状态退出，由AI报告冲突并等待用户手动解决
    if [ "${BATCH_NON_INTERACTIVE:-0}" = "1" ] || [ ! -t 0 ]; then
        log_error "非交互模式，保留冲突状态退出。请手动解决冲突后继续。"
        return 1
    fi
    
    while true; do
        echo "请选择处理方式:"
        echo "  [s] 解决冲突 - 编辑冲突文件后，SKILL自动完成后续操作"
        echo "  [a] 中止操作 - 放弃本次 $operation"
        read -p "输入选项(s/a): " choice
        
        case "$choice" in
            s)
                echo ""
                echo "请编辑上面的冲突文件解决冲突。"
                echo "解决完成后保存文件，然后按 Enter 继续..."
                read -p ""
                
                if has_conflicts; then
                    echo ""
                    log_warn "仍有冲突未解决"
                else
                    echo ""
                    log_info "冲突文件已解决，正在自动完成后续操作..."
                    
                    if ! git add .; then
                        log_error "git add 失败"
                        return 1
                    fi
                    log_success "已自动执行: git add ."
                    
                    case "$operation" in
                        pull|rebase)
                            if git rebase --continue; then
                                log_success "已自动执行: git rebase --continue"
                                log_success "冲突处理完成"
                                return 0
                            else
                                log_error "git rebase --continue 失败"
                                return 1
                            fi
                            ;;
                        *)
                            log_success "冲突处理完成"
                            return 0
                            ;;
                    esac
                fi
                ;;
            a)
                echo ""
                log_info "中止 $operation"
                case "$operation" in
                    pull|rebase)
                        git rebase --abort 2>/dev/null || git merge --abort 2>/dev/null || true
                        ;;
                    stash)
                        git reset --hard HEAD
                        ;;
                esac
                return 1
                ;;
            *)
                echo "无效选项，请输入 s 或 a"
                ;;
        esac
    done
}

# 同步子模块到目标分支最新代码（rebase方式，保留merge commit）
sync_submodule_to_target() {
    local module_path="$1"
    local module_name="$2"
    local target_branch="$3"

    log_info "同步子模块 $module_name 到 $target_branch 最新代码..."

    cd "$module_path" || {
        log_error "无法进入目录: $module_path"
        return 1
    }

    # 1. 获取目标分支最新代码
    if ! git fetch origin "$target_branch"; then
        log_error "获取 $target_branch 失败"
        cd - > /dev/null
        return 1
    fi

    # 2. rebase到目标分支（有未提交更改时先stash）
    local did_stash=false
    if has_changes; then
        log_info "  检测到未提交更改，自动暂存..."
        if stash_changes > /dev/null; then
            did_stash=true
        else
            log_error "  暂存失败"
            cd - > /dev/null
            return 1
        fi
    fi

    log_info "  rebase 到 origin/$target_branch..."
    if ! git rebase --rebase-merges "origin/$target_branch"; then
        if has_conflicts; then
            log_error "rebase 产生冲突"
            if ! handle_conflicts "rebase"; then
                # 非交互模式：保留冲突状态，不abort
                # 交互模式：用户选择了中止，abort
                if [ "${BATCH_NON_INTERACTIVE:-0}" != "1" ] && [ -t 0 ]; then
                    git rebase --abort 2>/dev/null || true
                fi
                if [ "$did_stash" = true ]; then pop_stash 2>/dev/null || true; fi
                cd - > /dev/null
                return 1
            fi
            log_success "冲突已解决"
        else
            log_error "rebase 失败"
            git rebase --abort 2>/dev/null || true
            if [ "$did_stash" = true ]; then pop_stash 2>/dev/null || true; fi
            cd - > /dev/null
            return 1
        fi
    fi

    # 恢复暂存
    if [ "$did_stash" = true ]; then
        if ! pop_stash; then
            log_error "  恢复暂存失败，可能有冲突"
            if has_conflicts; then
                if ! handle_conflicts "stash"; then
                    cd - > /dev/null
                    return 1
                fi
            fi
        fi
    fi

    log_success "  已同步到 $target_branch 最新代码"
    cd - > /dev/null
    return 0
}

# 提交子模块
commit_submodule() {
    local module_path="$1"
    local module_name="$2"
    local commit_msg="$3"
    local target_branch="$4"

    log_info "提交子模块: $module_name"

    # 【关键】先同步到目标分支最新代码（在进入目录前调用）
    log_info "  步骤1: 同步到 $target_branch 最新代码..."
    if ! sync_submodule_to_target "$module_path" "$module_name" "$target_branch"; then
        log_error "  同步失败，跳过提交"
        return 1
    fi

    cd "$module_path" || {
        log_error "无法进入目录: $module_path"
        return 1
    }

    # 获取当前分支（应该是开发分支如 develop_lhy）
    local current_branch=$(git rev-parse --abbrev-ref HEAD)
    log_info "  当前分支: $current_branch"
    log_info "  目标分支: $target_branch (用于同步)"

    # 安全检查：当前分支不能是目标分支（目标分支通常是受保护的，不能直接push）
    if [ "$current_branch" = "$target_branch" ]; then
        log_error "  子模块 $module_name 当前在目标分支 $target_branch 上，不能直接提交到目标分支！"
        log_error "  请先切换到开发分支（如 develop_lhy）再提交。"
        cd - > /dev/null
        return 1
    fi
    
    # 步骤2: 提交到当前开发分支
    log_info "  步骤2: 提交到 $current_branch..."
    if ! stage_all_except_commit_logs; then
        log_error "  git add 失败"
        cd - > /dev/null
        return 1
    fi
    
    if ! git commit -m "$commit_msg"; then
        log_error "  git commit 失败"
        cd - > /dev/null
        return 1
    fi
    
    # 步骤3: 提交前再次同步到最新（避免MR冲突）
    log_info "  步骤3: 同步到 $target_branch 最新代码..."
    if ! git fetch origin "$target_branch"; then
        log_error "  获取 $target_branch 失败"
        cd - > /dev/null
        return 1
    fi
    
    log_info "  rebase 到 origin/$target_branch..."
    if ! git rebase --rebase-merges "origin/$target_branch"; then
        log_error "  rebase 失败，请手动解决冲突"
        git rebase --abort 2>/dev/null || true
        cd - > /dev/null
        return 1
    fi
    log_success "  已同步到最新代码"
    
    # 步骤4: 强制推送到远程（先刷新ref，处理远程分支不存在的情况）
    log_info "  强制推送到远程 $current_branch ..."
    git fetch --prune origin 2>/dev/null || true
    if ! git push --force-with-lease origin "$current_branch" 2>/dev/null; then
        log_info "  force-with-lease 失败，尝试普通推送（远程分支可能不存在）..."
        if ! git push -u origin "$current_branch"; then
            log_error "  推送失败"
            cd - > /dev/null
            return 1
        fi
    fi
    local new_hash=$(git rev-parse HEAD)
    log_success "  已提交到 $current_branch: $new_hash"

    # 记录分支推送信息
    MODULE_BRANCHES["$module_name"]="$current_branch"

    cd - > /dev/null
}

# 更新主模块中的子模块引用
update_submodule_references() {
    log_info "更新主模块中的子模块引用..."
    
    git submodule foreach --quiet '
        cd "$toplevel"
        git add "$path"
        echo "  已更新: $path"
    '
    
    log_success "子模块引用已更新"
}

# 提交主模块
commit_main_module() {
    local commit_msg="$1"
    local target_branch="$2"
    
    log_info "提交主模块..."
    
    # 获取当前分支
    local current_branch=$(git rev-parse --abbrev-ref HEAD)
    log_info "  当前分支: $current_branch"
    log_info "  目标分支: $target_branch (用于同步)"

    # 安全检查：当前分支不能是目标分支（目标分支通常是受保护的，不能直接push）
    if [ "$current_branch" = "$target_branch" ]; then
        log_error "  主模块当前在目标分支 $target_branch 上，不能直接提交到目标分支！"
        log_error "  请先切换到开发分支（如 develop_lhy）再提交。"
        return 1
    fi
    
    # 【关键】先同步到目标分支最新代码（有未提交更改时先stash）
    local did_stash=false
    if has_changes; then
        log_info "  检测到未提交更改，自动暂存..."
        if stash_changes > /dev/null; then
            did_stash=true
        else
            log_error "  暂存失败"
            return 1
        fi
    fi

    log_info "  步骤1: 同步到 $target_branch 最新代码..."
    if ! git fetch origin "$target_branch"; then
        log_error "获取 $target_branch 失败"
        if [ "$did_stash" = true ]; then pop_stash 2>/dev/null || true; fi
        return 1
    fi

    if ! git rebase --rebase-merges "origin/$target_branch"; then
        if has_conflicts; then
            if ! handle_conflicts "rebase"; then
                if [ "${BATCH_NON_INTERACTIVE:-0}" != "1" ] && [ -t 0 ]; then
                    git rebase --abort 2>/dev/null || true
                fi
                if [ "$did_stash" = true ]; then pop_stash 2>/dev/null || true; fi
                return 1
            fi
        else
            log_error "rebase 失败"
            git rebase --abort 2>/dev/null || true
            if [ "$did_stash" = true ]; then pop_stash 2>/dev/null || true; fi
            return 1
        fi
    fi
    log_success "  已同步到 $target_branch 最新代码"

    # 恢复暂存
    if [ "$did_stash" = true ]; then
        if ! pop_stash; then
            if has_conflicts; then
                if ! handle_conflicts "stash"; then
                    return 1
                fi
            fi
        fi
    fi

    # 步骤2: 提交到当前开发分支
    log_info "  步骤2: 提交到 $current_branch..."
    if ! stage_all_except_commit_logs; then
        log_error "  git add 失败"
        return 1
    fi
    
    if ! git commit -m "$commit_msg"; then
        log_error "  git commit 失败"
        return 1
    fi
    
    # 步骤3: 提交前再次同步到最新（避免MR冲突）
    log_info "  步骤3: 同步到 $target_branch 最新代码..."
    if ! git fetch origin "$target_branch"; then
        log_error "  获取 $target_branch 失败"
        return 1
    fi
    
    log_info "  rebase 到 origin/$target_branch..."
    if ! git rebase --rebase-merges "origin/$target_branch"; then
        log_error "  rebase 失败，请手动解决冲突"
        git rebase --abort 2>/dev/null || true
        return 1
    fi
    log_success "  已同步到最新代码"
    
    # 步骤4: 强制推送到远程（先刷新ref，处理远程分支不存在的情况）
    log_info "  强制推送到远程 $current_branch ..."
    git fetch --prune origin 2>/dev/null || true
    if ! git push --force-with-lease origin "$current_branch" 2>/dev/null; then
        log_info "  force-with-lease 失败，尝试普通推送（远程分支可能不存在）..."
        if ! git push -u origin "$current_branch"; then
            log_error "  推送失败"
            return 1
        fi
    fi
    local new_hash=$(git rev-parse HEAD)
    log_success "  已提交到 $current_branch: $new_hash"

    # 记录主模块分支推送信息
    MODULE_BRANCHES["主模块"]="$current_branch"

    # 输出hash到fd3，避免被log污染
    echo "$new_hash" >&3
}

# 处理子模块临时分支（检测 &branch& 语法，仅处理本次有变更并已提交的子模块）
handle_submodule_temp_branches() {
    local commit_msg="$1"

    # 检测是否包含 &branch& 语法
    if ! echo "$commit_msg" | grep -qE '&[^&]+&'; then
        return 0
    fi

    log_info ""
    log_info "【第3步】处理子模块临时分支..."
    log_info "检测到提交信息包含临时分支标记"

    # 只处理本次有变更并已提交的子模块（记录在 MODULE_BRANCHES 中）
    local has_submodule=false
    for module in "${!MODULE_BRANCHES[@]}"; do
        [ "$module" = "主模块" ] && continue
        has_submodule=true

        # 获取子模块路径
        local submodule_path=$(git submodule | grep "$module" | awk '{print $2}')
        if [ -z "$submodule_path" ]; then
            log_warn "  找不到子模块 $module 的路径，跳过"
            continue
        fi

        log_info ""
        log_info "子模块: $module"

        cd "$submodule_path" || {
            log_error "  无法进入目录: $submodule_path"
            continue
        }

        # 获取子模块的当前commit hash
        local submodule_hash=$(git rev-parse HEAD)

        # 调用 branch-handler.sh 一次性处理所有目标分支
        if bash "$SCRIPT_DIR/branch-handler.sh" handle_target_branches "$commit_msg" "$submodule_hash"; then
            log_success "  子模块 $module 临时分支处理完成"
            MODULE_BRANCHES[$module]="${MODULE_BRANCHES[$module]},temp_branches"
        else
            log_error "  子模块 $module 临时分支处理失败"
            cd - > /dev/null
            return 1
        fi

        cd - > /dev/null
    done

    if [ "$has_submodule" = false ]; then
        log_info "  没有需要处理临时分支的子模块"
    fi

    log_success "子模块临时分支处理完成"
    return 0
}

# 处理主模块临时分支（检测 &branch& 语法，可选执行）
handle_main_temp_branches() {
    local commit_msg="$1"
    local main_commit_hash="$2"

    # 检测是否包含 &branch& 语法
    if ! echo "$commit_msg" | grep -qE '&[^&]+&'; then
        return 0
    fi

    log_info ""
    log_info "【第6步】处理主模块临时分支..."

    # 调用 branch-handler.sh 一次性处理所有目标分支
    if bash "$SCRIPT_DIR/branch-handler.sh" handle_target_branches "$commit_msg" "$main_commit_hash"; then
        log_success "主模块临时分支处理完成"
        MODULE_BRANCHES["主模块"]="${MODULE_BRANCHES["主模块"]},temp_branches"
    else
        log_error "主模块临时分支处理失败"
        return 1
    fi

    return 0
}

# 生成分支推送信息字符串（用于终端摘要）
generate_branch_info() {
    local branch_info=""
    for module in "${!MODULE_BRANCHES[@]}"; do
        if [ -n "$branch_info" ]; then
            branch_info="$branch_info;"
        fi
        branch_info="${branch_info}${module}:${MODULE_BRANCHES[$module]}"
    done
    echo "$branch_info"
}

# 生成GitLab Merge Request链接
# 参数: 源分支 目标分支
generate_mr_link() {
    local source_branch="$1"
    local target_branch="$2"
    
    # 获取GitLab项目URL
    local remote_url=$(git remote get-url origin 2>/dev/null || echo "")
    
    if [ -z "$remote_url" ]; then
        echo ""
        return
    fi
    
    # 转换SSH URL为HTTPS URL
    # git@gitlab.com:group/project.git -> https://gitlab.com/group/project
    # https://gitlab.com/group/project.git -> https://gitlab.com/group/project
    local project_url=""
    if [[ "$remote_url" == git@* ]]; then
        # SSH格式
        project_url=$(echo "$remote_url" | sed 's/^git@//' | sed 's/:/\//' | sed 's/\.git$//')
        project_url="https://${project_url}"
    elif [[ "$remote_url" == http* ]]; then
        # HTTPS格式
        project_url=$(echo "$remote_url" | sed 's/\.git$//')
    else
        echo ""
        return
    fi
    
    # 生成MR链接
    # GitLab格式: /-/merge_requests/new?merge_request[source_branch]=xxx&merge_request[target_branch]=yyy
    local mr_link="${project_url}/-/merge_requests/new?merge_request[source_branch]=${source_branch}&merge_request[target_branch]=${target_branch}"
    
    echo "$mr_link"
}

# 显示MR链接信息
show_mr_links() {
    local target_branch="$1"
    
    log_info ""
    log_info "═══════════════════════════════════════════════════════"
    log_info "           Merge Request 链接"
    log_info "═══════════════════════════════════════════════════════"
    
    # 主模块MR链接
    local main_branch="${MODULE_BRANCHES["主模块"]}"
    if [ -n "$main_branch" ]; then
        local main_mr=$(generate_mr_link "$main_branch" "$target_branch")
        if [ -n "$main_mr" ]; then
            log_info ""
            log_info "【主模块】"
            log_info "  分支: $main_branch → $target_branch"
            log_info "  链接: $main_mr"
        fi
    fi
    
    # 子模块MR链接
    for module in "${!MODULE_BRANCHES[@]}"; do
        if [ "$module" != "主模块" ]; then
            local module_branch="${MODULE_BRANCHES[$module]}"
            if [ -n "$module_branch" ]; then
                # 对于子模块，需要进入子模块目录获取URL
                local module_path=$(git submodule | grep "$module" | awk '{print $2}')
                if [ -n "$module_path" ]; then
                    local module_mr=$(cd "$module_path" && generate_mr_link "$module_branch" "$target_branch")
                    if [ -n "$module_mr" ]; then
                        log_info ""
                        log_info "【$module】"
                        log_info "  分支: $module_branch → $target_branch"
                        log_info "  链接: $module_mr"
                    fi
                fi
            fi
        fi
    done
    
    log_info ""
    log_info "═══════════════════════════════════════════════════════"
}

# 获取有本地变更的子模块列表
get_changed_submodules() {
    git submodule foreach --quiet '
        if [ -n "$(git status --porcelain)" ]; then
            echo "$name:$path"
        fi
    '
}

# 批量提交主流程
batch_commit() {
    local commit_msg="$1"
    local target_branch="${2:-$MAIN_SYNC_BRANCH}"
    
    # 清空分支记录
    MODULE_BRANCHES=()
    
    log_info "═══════════════════════════════════════════════════════"
    log_info "           开始批量提交"
    log_info "═══════════════════════════════════════════════════════"
    log_info "提交信息: $commit_msg"
    log_info "同步目标分支: $target_branch"
    log_info ""
    log_info "说明: 流程: 子模块提交 → 子模块临时分支 → 更新引用 → 主模块提交 → 主模块临时分支"
    log_info "      每个模块提交时会自动rebase到 $target_branch 最新代码避免MR冲突"
    # 检测是否需要处理临时分支
    if echo "$commit_msg" | grep -qE '&[^&]+&'; then
        log_info ""
        log_info "检测到临时分支标记，将在主提交完成后处理临时分支"
    fi
    
    # 【第1步】收集有本地变更的子模块
    log_info ""
    log_info "【第1步】收集有本地变更的模块..."

    local changed_submodules=()
    local changed_count=0
    if _has_submodules; then
        while IFS=: read -r name path; do
            changed_submodules+=("$name:$path")
            log_info "  发现变更: $name"
        done < <( get_changed_submodules )
        changed_count=${#changed_submodules[@]}
        log_info "  共 $changed_count 个子模块有本地变更"
    else
        log_info "  当前项目无子模块，跳过子模块收集"
    fi
    
    # 【第2步】先提交有变更的子模块
    if ! _has_submodules; then
        log_info ""
        log_info "【第2步】当前项目无子模块，跳过"
    elif [ $changed_count -gt 0 ]; then
        log_info ""
        log_info "【第2步】提交有本地变更的子模块..."

        local i=1
        for submodule_info in "${changed_submodules[@]}"; do
            IFS=: read -r name path <<< "$submodule_info"
            log_info ""
            log_info "[$i/$changed_count] 处理子模块: $name"
            if ! commit_submodule "$path" "$name" "$commit_msg" "$target_branch"; then
                log_error "子模块 $name 提交失败，中止批量提交"
                return 1
            fi
            ((i++))
        done
    else
        log_info ""
        log_info "【第2步】没有需要提交的子模块变更"
    fi
    
    # 【第3步】处理子模块临时分支（如果提交信息包含 &branch&）
    if _has_submodules && echo "$commit_msg" | grep -qE '&[^&]+&'; then
        if ! handle_submodule_temp_branches "$commit_msg"; then
            log_error "子模块临时分支处理失败"
            return 1
        fi
    fi
    
    # 【第4步】更新主模块中的子模块引用
    log_info ""
    log_info "【第4步】更新主模块中的子模块引用..."
    if _has_submodules; then
        update_submodule_references
    else
        log_info "  当前项目无子模块，跳过引用更新"
    fi
    
    # 【第5步】提交主模块
    log_info ""
    log_info "【第5步】提交主模块..."
    local main_hash
    # 通过fd3捕获纯净的hash值，避免log输出污染
    main_hash=$(commit_main_module "$commit_msg" "$target_branch" 3>&1 1>&2)
    if [ $? -ne 0 ]; then
        log_error "主模块提交失败"
        return 1
    fi
    
    # 【第6步】处理主模块临时分支（如果提交信息包含 &branch&）
    if echo "$commit_msg" | grep -qE '&[^&]+&'; then
        if ! handle_main_temp_branches "$commit_msg" "$main_hash"; then
            log_error "主模块临时分支处理失败"
            return 1
        fi
    fi
    # 生成分支推送信息
    local branch_info=$(generate_branch_info)
    log_info ""
    log_info "分支推送记录: $branch_info"
    
    log_info ""
    log_success "═══════════════════════════════════════════════════════"
    log_success "           批量提交完成"
    log_success "═══════════════════════════════════════════════════════"
    log_info ""
    log_info "分支推送详情:"
    for module in "${!MODULE_BRANCHES[@]}"; do
        log_info "  $module → ${MODULE_BRANCHES[$module]}"
    done
    log_info ""
    # 显示MR链接
    show_mr_links "$target_branch"
    log_info "提示: 请检查GitLab上的Merge Request，确保可以正常合入 $target_branch"
    
    return 0
}

# 主函数
main() {
    if [ $# -lt 1 ]; then
        echo "用法: $0 <提交信息> [目标分支]"
        echo "示例: $0 'feat(auth): 添加登录验证' develop"
        echo ""
        echo "说明:"
        echo "  - 所有模块先rebase到目标分支最新代码"
        echo "  - 然后提交到各自的开发分支"
        echo "  - 支持 &branch& 语法创建临时分支（可选）"
        echo "  - 最后通过MR合入目标分支"
        exit 1
    fi
    
    local commit_msg="$1"
    local target_branch="${2:-$MAIN_SYNC_BRANCH}"
    
    batch_commit "$commit_msg" "$target_branch"
}

if [ "${BASH_SOURCE[0]}" == "${0}" ]; then
    main "$@"
fi
