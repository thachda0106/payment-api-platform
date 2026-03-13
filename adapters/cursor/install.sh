#!/bin/bash
# Cursor Adapter — Install Script
# Reads .ai/ canonical structure and generates .cursorrules for Cursor IDE
#
# Usage: bash adapters/cursor/install.sh [--dry-run]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
AI_DIR="$PROJECT_ROOT/.ai"

# Load shared library
source "$PROJECT_ROOT/.ai/scripts/_lib.sh"
parse_flags "$@"

echo -e "${BLUE}=== Cursor Adapter Install ===${NC}"
echo ""

if [ ! -d "$AI_DIR" ]; then
    echo -e "${RED}ERROR: .ai/ directory not found at $AI_DIR${NC}"
    exit 1
fi

# Cursor uses a single .cursorrules file at the project root.
# We merge all context files + system prompt + agent summaries into it.
echo -e "${GREEN}✓${NC} Generating .cursorrules from context files"
{
    echo "# Cursor AI Rules"
    echo "# Auto-generated from .ai/context/ by Cursor adapter"
    echo "# Do not edit directly. Edit .ai/context/ files and re-run adapter."
    echo ""

    if [ -f "$AI_DIR/context/PROJECT.md" ]; then
        cat "$AI_DIR/context/PROJECT.md"
        echo ""
    fi

    if [ -f "$AI_DIR/context/CONVENTIONS.md" ]; then
        cat "$AI_DIR/context/CONVENTIONS.md"
        echo ""
    fi

    if [ -f "$AI_DIR/context/BOUNDARIES.md" ]; then
        cat "$AI_DIR/context/BOUNDARIES.md"
        echo ""
    fi

    if [ -f "$AI_DIR/prompts/system.md" ]; then
        cat "$AI_DIR/prompts/system.md"
    fi

    # Append agent summaries as context for Cursor
    echo ""
    echo "---"
    echo ""
    echo "# Agent Personas"
    echo ""
    echo "When working on different task types, adopt the following personas:"
    echo ""
    for f in "$AI_DIR"/agents/*.agent.md; do
        [ -f "$f" ] || continue
        AGENT_NAME=$(basename "$f" .agent.md)
        DESCRIPTION=$(grep '^description:' "$f" | head -1 | sed 's/^description: *//')
        echo "- **$AGENT_NAME**: $DESCRIPTION"
    done
} > "$PROJECT_ROOT/.cursorrules"

echo ""
echo -e "${GREEN}=== Cursor adapter installed successfully ===${NC}"
echo ""
echo "Generated files:"
echo "  .cursorrules    (merged context + system prompt + agent summaries)"
echo ""
echo "Note: Cursor doesn't support workflows or skills natively."
echo "These are embedded in .cursorrules as context."
