#!/bin/bash

# Compatibility shim for older commit-review instructions.
# Intentionally does not create commit_log.csv or commit_log.xlsx.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/utils.sh"

show_disabled_message() {
    log_warn "commit-review shared CSV logging is disabled."
    log_info "No commit_log.csv or commit_log.xlsx file was created."
    log_info "Keep review evidence in the commit-review output, agent runtime result, trace, or review notes."
}

main() {
    local action="${1:-}"
    case "$action" in
        init|log|export|show)
            show_disabled_message
            ;;
        *)
            show_disabled_message
            echo ""
            echo "Usage: $0 <init|log|export|show> [legacy args...]"
            ;;
    esac
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    main "$@"
fi
