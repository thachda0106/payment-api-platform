#!/bin/bash
# install-ai-template.sh
# Auto-detects installed AI tools and runs the appropriate adapter(s)
#
# Usage: bash .ai/scripts/install-ai-template.sh [--dry-run] [tool1] [tool2] ...
#
# Flags:
#   --dry-run    Show what would be installed without making changes

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ADAPTERS_DIR="$PROJECT_ROOT/adapters"

# Load shared library
source "$SCRIPT_DIR/_lib.sh"
parse_flags "$@"

# Filter out flags from arguments
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --dry-run) ;; # already parsed
        *) ARGS+=("$arg") ;;
    esac
done

echo -e "${BLUE}=== AI Template Installer ===${NC}"
echo ""

if [ "$DRY_RUN" = true ]; then
    echo -e "${YELLOW}[DRY RUN MODE] No files will be modified.${NC}"
    echo ""
fi

# Detect available AI tools
DETECTED=()

# Check for Claude Code
if [ -d "$PROJECT_ROOT/.claude" ] || command -v claude &>/dev/null 2>&1; then
    DETECTED+=(claude)
fi

# Check for Antigravity
if command -v antigravity &>/dev/null 2>&1 || [ -d "$PROJECT_ROOT/.gemini" ] || [ -d "$PROJECT_ROOT/.agent" ]; then
    DETECTED+=(antigravity)
fi

# Check for Cursor
if [ -d "$PROJECT_ROOT/.cursor" ] || [ -f "$PROJECT_ROOT/.cursorrules" ]; then
    DETECTED+=(cursor)
fi

# Check for Aider
if command -v aider &>/dev/null 2>&1 || [ -f "$PROJECT_ROOT/.aider.conf.yml" ]; then
    DETECTED+=(aider)
fi

if [ ${#DETECTED[@]} -eq 0 ] && [ ${#ARGS[@]} -eq 0 ]; then
    echo -e "${YELLOW}No AI tools detected automatically.${NC}"
    echo ""
    echo "Available adapters:"
    echo "  claude        - Claude Code (.claude/ + CLAUDE.md)"
    echo "  antigravity   - Antigravity IDE (.agent/ + .gemini/)"
    echo "  cursor        - Cursor IDE (.cursorrules)"
    echo "  aider         - Aider (.aider.conf.yml)"
    echo ""
    echo "Usage: bash .ai/scripts/install-ai-template.sh <tool1> [tool2] ..."
    echo "Example: bash .ai/scripts/install-ai-template.sh antigravity cursor"
    exit 0
fi

echo -e "Detected AI tools: ${GREEN}${DETECTED[*]}${NC}"
echo ""

# Allow overriding with command-line arguments
if [ ${#ARGS[@]} -gt 0 ]; then
    DETECTED=("${ARGS[@]}")
    echo -e "Override: installing for: ${GREEN}${DETECTED[*]}${NC}"
    echo ""
fi

# Install each adapter
INSTALLED=0
FAILED=0
INSTALLED_NAMES=()

for tool in "${DETECTED[@]}"; do
    ADAPTER_SCRIPT="$ADAPTERS_DIR/$tool/install.sh"

    if [ -f "$ADAPTER_SCRIPT" ]; then
        echo -e "${BLUE}--- Installing $tool adapter ---${NC}"
        if [ "$DRY_RUN" = true ]; then
            echo -e "${YELLOW}[DRY RUN]${NC} Would run: bash $ADAPTER_SCRIPT"
            INSTALLED=$((INSTALLED + 1))
            INSTALLED_NAMES+=("$tool")
        else
            if bash "$ADAPTER_SCRIPT"; then
                INSTALLED=$((INSTALLED + 1))
                INSTALLED_NAMES+=("$tool")
            else
                echo -e "${RED}Failed to install $tool adapter${NC}"
                FAILED=$((FAILED + 1))
            fi
        fi
        echo ""
    else
        echo -e "${YELLOW}No adapter found for: $tool (expected $ADAPTER_SCRIPT)${NC}"
        FAILED=$((FAILED + 1))
    fi
done

# Update AI_MANIFEST.yaml with installed adapters
MANIFEST="$PROJECT_ROOT/.ai/AI_MANIFEST.yaml"
if [ -f "$MANIFEST" ] && [ ${#INSTALLED_NAMES[@]} -gt 0 ]; then
    if [ "$DRY_RUN" = true ]; then
        echo -e "${YELLOW}[DRY RUN]${NC} Would update AI_MANIFEST.yaml adapters: ${INSTALLED_NAMES[*]}"
    else
        # Build the new adapters block
        {
            # Read everything before adapters:
            sed -n '1,/^adapters:/p' "$MANIFEST" | head -n -1
            # Write new adapters list
            echo "adapters:"
            for name in "${INSTALLED_NAMES[@]}"; do
                echo "  - $name"
            done
            # Read everything after the adapters block
            awk '
                /^adapters:/ { in_block=1; next }
                in_block && /^  - / { next }
                in_block && /^  #/ { next }
                in_block && /^[^ ]/ { in_block=0 }
                in_block && /^$/ { in_block=0 }
                !in_block { print }
            ' "$MANIFEST"
        } > "${MANIFEST}.tmp"
        mv "${MANIFEST}.tmp" "$MANIFEST"
        echo -e "${GREEN}✓${NC} Updated AI_MANIFEST.yaml with installed adapters: ${INSTALLED_NAMES[*]}"
    fi
fi

# Summary
echo -e "${BLUE}=== Summary ===${NC}"
echo -e "${GREEN}Installed: $INSTALLED adapter(s)${NC}"
if [ $FAILED -gt 0 ]; then
    echo -e "${RED}Failed: $FAILED adapter(s)${NC}"
fi
