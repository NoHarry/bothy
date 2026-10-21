#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_PATH="$SCRIPT_DIR/sync-latest.sh"

source "$SCRIPT_DIR/utils.sh"
source "$SCRIPT_DIR/config.sh"

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
                    log_warn "仍有冲突未解决，请继续编辑冲突文件"
                else
                    echo ""
                    log_info "冲突文件已解决，正在自动完成后续操作..."
                    
                    # 自动执行 git add
                    if ! git add .; then
                        log_error "git add 失败"
                        return 1
                    fi
                    log_success "已自动执行: git add ."
                    
                    # 根据操作类型自动执行后续命令
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

# 同步主模块
sync_main_module() {
    log_info "处理: 主项目"
    log_info "同步分支: $MAIN_SYNC_BRANCH"
    
    # 获取当前分支
    local current_branch=$(git symbolic-ref --short HEAD 2>/dev/null || echo "HEAD")
    log_info "当前分支: $current_branch"

    if has_changes; then
        log_warn "检测到未提交更改，自动暂存..."

        if stash_changes; then
            log_info "已暂存更改"

            # 先fetch最新代码
            if ! git fetch origin "$MAIN_SYNC_BRANCH"; then
                log_error "获取远程分支失败"
                pop_stash
                return 1
            fi

            # 将当前分支rebase到origin/develop（--rebase-merges保留merge commit）
            log_info "将 $current_branch rebase 到 origin/$MAIN_SYNC_BRANCH ..."
            if ! git rebase --rebase-merges "origin/$MAIN_SYNC_BRANCH"; then
                handle_conflicts "rebase" || {
                    if [ "${BATCH_NON_INTERACTIVE:-0}" != "1" ] && [ -t 0 ]; then
                        git rebase --abort 2>/dev/null || true
                    fi
                    pop_stash
                    return 1
                }
            fi
            log_success "$current_branch 已更新到最新代码"

            # 恢复暂存
            if ! pop_stash; then
                handle_conflicts "stash" || return 1
                log_success "冲突解决"
            fi
        else
            log_error "暂存失败"
            return 1
        fi
    else
        # 没有未提交更改，直接rebase
        if ! git fetch origin "$MAIN_SYNC_BRANCH"; then
            log_error "获取远程分支失败"
            return 1
        fi

        log_info "将 $current_branch rebase 到 origin/$MAIN_SYNC_BRANCH ..."
        if ! git rebase --rebase-merges "origin/$MAIN_SYNC_BRANCH"; then
            handle_conflicts "rebase" || {
                if [ "${BATCH_NON_INTERACTIVE:-0}" != "1" ] && [ -t 0 ]; then
                    git rebase --abort 2>/dev/null || true
                fi
                return 1
            }
            log_success "冲突已解决"
        else
            log_success "$current_branch 已更新到最新代码"
        fi
    fi
}

# 同步子模块
sync_submodule() {
    local module_path="$1"
    local module_name="${2:-$(basename "$module_path")}"
    
    log_info "处理子模块: $module_name"
    log_info "同步分支: $SUBMODULE_SYNC_BRANCH"
    
    cd "$module_path" || {
        log_error "无法进入目录"
        return 1
    }
    
    # 获取当前分支
    local current_branch=$(git symbolic-ref --short HEAD 2>/dev/null || echo "HEAD")
    log_info "  当前分支: $current_branch"

    local has_local_changes=false
    
    if has_changes; then
        has_local_changes=true
        log_warn "  检测到未提交更改，自动暂存..."
        
        if ! stash_changes; then
            log_error "  暂存失败"
            cd - > /dev/null
            return 1
        fi
        log_info "  已暂存"
    fi
    
    # fetch最新代码
    if ! git fetch origin "$SUBMODULE_SYNC_BRANCH"; then
        log_error "  获取远程分支失败"
        if [ "$has_local_changes" == "true" ]; then
            pop_stash
        fi
        cd - > /dev/null
        return 1
    fi
    
    # 将当前分支rebase到origin/SUBMODULE_SYNC_BRANCH（--rebase-merges保留merge commit）
    log_info "  将 $current_branch rebase 到 origin/$SUBMODULE_SYNC_BRANCH ..."
    if ! git rebase --rebase-merges "origin/$SUBMODULE_SYNC_BRANCH"; then
        if ! handle_conflicts "rebase"; then
            if [ "${BATCH_NON_INTERACTIVE:-0}" != "1" ] && [ -t 0 ]; then
                git rebase --abort 2>/dev/null || true
            fi
            if [ "$has_local_changes" == "true" ]; then
                pop_stash
            fi
            cd - > /dev/null
            return 1
        fi
        log_success "  冲突已解决"
    else
        log_success "  $current_branch 已更新到最新代码"
    fi

    # 恢复暂存
    if [ "$has_local_changes" == "true" ]; then
        if ! pop_stash; then
            handle_conflicts "stash" || {
                cd - > /dev/null
                return 1
            }
        fi
    fi

    cd - > /dev/null
}

# 同步所有模块
sync_all() {
    log_info "═══════════════════════════════════════════════════════"
    log_info "           开始同步所有模块"
    log_info "═══════════════════════════════════════════════════════"
    log_info "主模块分支: $MAIN_SYNC_BRANCH"
    log_info "子模块分支: $SUBMODULE_SYNC_BRANCH"
    log_info ""
    
    # 同步主模块
    sync_main_module
    if [ $? -ne 0 ]; then
        log_error "主模块同步失败"
        return 1
    fi
    
    log_info ""
    if ! _has_submodules; then
        log_info "当前项目无子模块，跳过子模块同步"
    else
        log_info "开始同步子模块..."

        # 同步所有子模块
        git submodule foreach --recursive "
            export SCRIPT_PATH=\"$SCRIPT_PATH\"
            export MAIN_SYNC_BRANCH=\"$MAIN_SYNC_BRANCH\"
            export SUBMODULE_SYNC_BRANCH=\"$SUBMODULE_SYNC_BRANCH\"
            bash \"\$SCRIPT_PATH\" sync_submodule \"\$PWD\" \"\$name\"
        "

        if [ $? -ne 0 ]; then
            log_error "子模块同步失败"
            return 1
        fi
    fi
    
    log_info ""
    log_success "═══════════════════════════════════════════════════════"
    log_success "           所有模块同步完成"
    log_success "═══════════════════════════════════════════════════════"
}

# 主函数
main() {
    case "$1" in
        sync_submodule)
            shift
            sync_submodule "$@"
            ;;
        *)
            sync_all
            ;;
    esac
}

if [ "${BASH_SOURCE[0]}" == "${0}" ]; then
    main "$@"
fi
