#!/bin/bash

# Local checkpoint commit for long-running Harness work.
# This script intentionally does not sync, push, or create target branches.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/utils.sh"

stage_checkpoint_changes() {
    git add -A -- . || return 1
    git reset -q -- \
        commit_log.csv \
        commit_log.xlsx \
        harness/.local \
        harness/.local/commit_log.csv \
        harness/.local/commit_log.xlsx \
        2>/dev/null || true
}

usage() {
    echo "Usage: $0 \"chore(checkpoint): short description\""
}

main() {
    local commit_msg="${1:-}"
    if [ -z "$commit_msg" ]; then
        usage
        return 2
    fi

    if ! check_git_repo; then
        return 1
    fi

    if echo "$commit_msg" | grep -qE '&[^&]+&'; then
        log_error "checkpoint commits must not include target branch markers"
        log_info "Use publish mode / commit-batch.sh for branch fan-out and push."
        return 1
    fi

    if ! echo "$commit_msg" | grep -qE '^chore\(checkpoint\): .+'; then
        log_error "checkpoint commit message must use: chore(checkpoint): <description>"
        return 1
    fi

    if ! has_uncommitted_changes; then
        log_info "No changes to checkpoint."
        return 0
    fi

    if ! stage_checkpoint_changes; then
        log_error "failed to stage checkpoint changes"
        return 1
    fi

    if git diff --cached --check; then
        log_success "checkpoint diff whitespace check passed"
    else
        log_error "checkpoint diff whitespace check failed"
        return 1
    fi

    if git diff --cached --quiet; then
        log_info "No staged changes after checkpoint exclusions."
        return 0
    fi

    log_info "Creating local checkpoint commit; normal git hooks still apply."
    git commit -m "$commit_msg"
    log_success "Local checkpoint commit created."
    log_info "Run publish mode before push/MR so the accumulated diff receives full commit-review."
}

main "$@"
