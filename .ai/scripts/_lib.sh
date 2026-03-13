#!/bin/bash
# _lib.sh — Shared shell utilities for AI template scripts and adapters
#
# Usage: source this file from any adapter install.sh or template script
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../.ai/scripts/_lib.sh"
#
# Provides:
#   - Color constants (GREEN, BLUE, YELLOW, RED, NC)
#   - resolve_project_root() — find the project root
#   - resolve_ai_dir() — find .ai/ directory
#   - merge_context_files() — merge context + system prompt into a single output file
#   - update_manifest_adapters() — write installed adapters to AI_MANIFEST.yaml

# ============================================================
# Color Constants
# ============================================================
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# ============================================================
# Path Resolution
# ============================================================

# Resolve PROJECT_ROOT from a known script location.
# Usage: resolve_project_root "$SCRIPT_DIR" <levels_up>
#   levels_up: number of parent directories from SCRIPT_DIR to PROJECT_ROOT
#              e.g., for .ai/scripts/ → 2, for adapters/claude/ → 2
resolve_project_root() {
    local dir="$1"
    local levels="${2:-2}"
    local result="$dir"
    for ((i = 0; i < levels; i++)); do
        result="$(cd "$result/.." && pwd)"
    done
    echo "$result"
}

# ============================================================
# Context Merge
# ============================================================

# Merge context files (PROJECT.md, CONVENTIONS.md, BOUNDARIES.md) and system
# prompt into a single output file. This is the shared logic previously
# duplicated across all 4 adapter install.sh scripts.
#
# Usage: merge_context_files <ai_dir> <output_file> [header_line]
#
# Arguments:
#   ai_dir       — path to .ai/ directory
#   output_file  — target file to write
#   header_line  — optional first line (e.g., "# CLAUDE.md")
merge_context_files() {
    local ai_dir="$1"
    local output_file="$2"
    local header="${3:-}"
    local adapter_name="${4:-adapter}"

    {
        if [ -n "$header" ]; then
            echo "$header"
            echo ""
        fi

        echo "<!-- Auto-generated from .ai/context/ by $adapter_name adapter -->"
        echo "<!-- Do not edit directly. Edit .ai/context/ files and re-run adapter. -->"
        echo ""

        if [ -f "$ai_dir/context/PROJECT.md" ]; then
            cat "$ai_dir/context/PROJECT.md"
            echo ""
            echo "---"
            echo ""
        fi

        if [ -f "$ai_dir/context/CONVENTIONS.md" ]; then
            cat "$ai_dir/context/CONVENTIONS.md"
            echo ""
            echo "---"
            echo ""
        fi

        if [ -f "$ai_dir/context/BOUNDARIES.md" ]; then
            cat "$ai_dir/context/BOUNDARIES.md"
            echo ""
        fi

        if [ -f "$ai_dir/prompts/system.md" ]; then
            echo "---"
            echo ""
            cat "$ai_dir/prompts/system.md"
        fi
    } > "$output_file"
}

# ============================================================
# Manifest Update
# ============================================================

# Update AI_MANIFEST.yaml with installed adapter list.
#
# Usage: update_manifest_adapters <manifest_file> <adapter1> [adapter2] ...
update_manifest_adapters() {
    local manifest="$1"
    shift
    local adapters=("$@")

    if [ ! -f "$manifest" ]; then
        echo -e "${YELLOW}WARNING: AI_MANIFEST.yaml not found at $manifest${NC}"
        return 1
    fi

    # Build YAML array
    local yaml_list=""
    for adapter in "${adapters[@]}"; do
        yaml_list="${yaml_list}\n  - ${adapter}"
    done

    # Replace the adapters line using sed
    # Match "adapters: []" or "adapters:" followed by list items
    if grep -q '^adapters:' "$manifest"; then
        # Remove existing adapter list items (lines starting with "  - " after adapters:)
        local tmp_file
        tmp_file=$(mktemp)
        awk '
            /^adapters:/ { print "adapters:"; in_adapters=1; next }
            in_adapters && /^  - / { next }
            in_adapters && /^[^ ]/ { in_adapters=0 }
            in_adapters && /^$/ { in_adapters=0 }
            { print }
        ' "$manifest" > "$tmp_file"

        # Insert new adapter list
        sed -i "s|^adapters:$|adapters:${yaml_list}|" "$tmp_file"

        # Use printf to interpret \n properly
        printf '%s\n' "$(awk '
            /^adapters:/ {
                print "adapters:"
                split("'"$(printf '%s' "${adapters[*]}")"'", arr, " ")
                for (i in arr) print "  - " arr[i]
                next
            }
            { print }
        ' "$manifest")" > "$tmp_file"

        mv "$tmp_file" "$manifest"
    fi
}

# ============================================================
# Copy Helpers
# ============================================================

# Copy files from source to target with logging.
# Usage: copy_files_with_log <source_glob> <target_dir> [transform]
copy_files_with_log() {
    local source_pattern="$1"
    local target_dir="$2"

    for f in $source_pattern; do
        [ -f "$f" ] || continue
        cp "$f" "$target_dir/$(basename "$f")"
    done
}

# Copy skill directories preserving structure.
# Usage: copy_skills <ai_dir>/skills <target_skills_dir>
copy_skills() {
    local source_dir="$1"
    local target_dir="$2"

    for d in "$source_dir"/*/; do
        [ -d "$d" ] || continue
        local skill_name
        skill_name=$(basename "$d")
        mkdir -p "$target_dir/$skill_name"

        if [ -f "$d/SKILL.md" ]; then
            cp "$d/SKILL.md" "$target_dir/$skill_name/SKILL.md"
        fi

        # Copy subdirectories (references, scripts, assets)
        for sub in references scripts assets; do
            if [ -d "$d/$sub" ]; then
                cp -r "$d/$sub" "$target_dir/$skill_name/"
            fi
        done
    done

    # Copy skills README if present
    if [ -f "$source_dir/README.md" ]; then
        cp "$source_dir/README.md" "$target_dir/README.md"
    fi
}

# ============================================================
# Dry Run Support
# ============================================================
DRY_RUN=false

# Parse common flags. Call this at the top of your script.
# Usage: parse_flags "$@"
parse_flags() {
    for arg in "$@"; do
        case "$arg" in
            --dry-run) DRY_RUN=true ;;
        esac
    done
}

# Wrap mkdir for dry-run support
safe_mkdir() {
    if [ "$DRY_RUN" = true ]; then
        echo -e "${YELLOW}[DRY RUN]${NC} Would create: $1"
    else
        mkdir -p "$1"
    fi
}
