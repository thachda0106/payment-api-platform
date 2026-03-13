#!/bin/bash
# install-ai-template.sh
# Auto-detects installed AI tools and runs the appropriate adapter(s)
#
# Usage: bash .ai/scripts/install-ai-template.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ADAPTERS_DIR="$PROJECT_ROOT/adapters"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${BLUE}=== AI Template Installer ===${NC}"
echo ""

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

if [ ${#DETECTED[@]} -eq 0 ]; then
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
if [ $# -gt 0 ]; then
    DETECTED=("$@")
    echo -e "Override: installing for: ${GREEN}${DETECTED[*]}${NC}"
    echo ""
fi

# Install each adapter
INSTALLED=0
FAILED=0

for tool in "${DETECTED[@]}"; do
    ADAPTER_SCRIPT="$ADAPTERS_DIR/$tool/install.sh"

    if [ -f "$ADAPTER_SCRIPT" ]; then
        echo -e "${BLUE}--- Installing $tool adapter ---${NC}"
        if bash "$ADAPTER_SCRIPT"; then
            ((INSTALLED++))
        else
            echo -e "${RED}Failed to install $tool adapter${NC}"
            ((FAILED++))
        fi
        echo ""
    else
        echo -e "${YELLOW}No adapter found for: $tool (expected $ADAPTER_SCRIPT)${NC}"
        ((FAILED++))
    fi
done

# Summary
echo -e "${BLUE}=== Summary ===${NC}"
echo -e "${GREEN}Installed: $INSTALLED adapter(s)${NC}"
if [ $FAILED -gt 0 ]; then
    echo -e "${RED}Failed: $FAILED adapter(s)${NC}"
fi

# Update AI_MANIFEST.yaml with installed adapters
if [ -f "$PROJECT_ROOT/.ai/AI_MANIFEST.yaml" ]; then
    echo ""
    echo "Installed adapters: ${DETECTED[*]}"
fi
