#!/bin/bash
# Claude Code Adapter — Install Script
# Reads .ai/ canonical structure and generates .claude/ + CLAUDE.md for Claude Code
#
# Usage: bash adapters/claude/install.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
AI_DIR="$PROJECT_ROOT/.ai"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}=== Claude Code Adapter Install ===${NC}"
echo ""

if [ ! -d "$AI_DIR" ]; then
    echo -e "${YELLOW}ERROR: .ai/ directory not found at $AI_DIR${NC}"
    exit 1
fi

# Create target directories
echo -e "${BLUE}Creating directories...${NC}"
mkdir -p "$PROJECT_ROOT/.claude/agents"
mkdir -p "$PROJECT_ROOT/.claude/commands"
mkdir -p "$PROJECT_ROOT/.claude/skills"
mkdir -p "$PROJECT_ROOT/.claude/scripts"
mkdir -p "$PROJECT_ROOT/.claude/prompts"
mkdir -p "$PROJECT_ROOT/.claude/docs"

# 1. Merge context files into CLAUDE.md
echo -e "${GREEN}✓${NC} Generating CLAUDE.md from context files"
{
    echo "# CLAUDE.md"
    echo ""
    echo "<!-- Auto-generated from .ai/context/ by Claude Code adapter -->"
    echo "<!-- Do not edit directly. Edit .ai/context/ files and re-run adapter. -->"
    echo ""

    if [ -f "$AI_DIR/context/PROJECT.md" ]; then
        cat "$AI_DIR/context/PROJECT.md"
        echo ""
        echo "---"
        echo ""
    fi

    if [ -f "$AI_DIR/context/CONVENTIONS.md" ]; then
        cat "$AI_DIR/context/CONVENTIONS.md"
        echo ""
        echo "---"
        echo ""
    fi

    if [ -f "$AI_DIR/context/BOUNDARIES.md" ]; then
        cat "$AI_DIR/context/BOUNDARIES.md"
        echo ""
    fi

    if [ -f "$AI_DIR/prompts/system.md" ]; then
        echo "---"
        echo ""
        cat "$AI_DIR/prompts/system.md"
    fi
} > "$PROJECT_ROOT/CLAUDE.md"

# 2. Convert agents (add @agent-style frontmatter)
echo -e "${GREEN}✓${NC} Converting agents"
for f in "$AI_DIR"/agents/*.agent.md; do
    [ -f "$f" ] || continue
    BASENAME=$(basename "$f" .agent.md)
    cp "$f" "$PROJECT_ROOT/.claude/agents/$BASENAME.md"
done

# 3. Convert workflows → commands (copy with same format)
echo -e "${GREEN}✓${NC} Converting workflows to commands"
for f in "$AI_DIR"/workflows/*.md; do
    [ -f "$f" ] || continue
    cp "$f" "$PROJECT_ROOT/.claude/commands/$(basename "$f")"
done

# 4. Copy skills
echo -e "${GREEN}✓${NC} Copying skills"
for d in "$AI_DIR"/skills/*/; do
    [ -d "$d" ] || continue
    SKILL_NAME=$(basename "$d")
    mkdir -p "$PROJECT_ROOT/.claude/skills/$SKILL_NAME"
    cp -r "$d"* "$PROJECT_ROOT/.claude/skills/$SKILL_NAME/" 2>/dev/null || true
done
if [ -f "$AI_DIR/skills/README.md" ]; then
    cp "$AI_DIR/skills/README.md" "$PROJECT_ROOT/.claude/skills/README.md"
fi

# 5. Copy scripts
echo -e "${GREEN}✓${NC} Copying scripts"
for f in "$AI_DIR"/scripts/*; do
    [ -f "$f" ] || continue
    BASENAME=$(basename "$f")
    case "$BASENAME" in
        install-ai-template.sh|migrate-from-claude.sh|validate-template.sh) continue ;;
    esac
    cp "$f" "$PROJECT_ROOT/.claude/scripts/$BASENAME"
done

# 6. Copy prompts
echo -e "${GREEN}✓${NC} Copying prompt templates"
for f in "$AI_DIR"/prompts/templates/*; do
    [ -f "$f" ] || continue
    cp "$f" "$PROJECT_ROOT/.claude/prompts/$(basename "$f")"
done

# 7. Copy docs
echo -e "${GREEN}✓${NC} Copying docs"
for f in "$AI_DIR"/docs/*; do
    [ -f "$f" ] || continue
    cp "$f" "$PROJECT_ROOT/.claude/docs/$(basename "$f")"
done

echo ""
echo -e "${GREEN}=== Claude Code adapter installed successfully ===${NC}"
echo ""
echo "Generated files:"
echo "  CLAUDE.md                  (merged context + system prompt)"
echo "  .claude/agents/            ($(ls -1 "$PROJECT_ROOT/.claude/agents/" 2>/dev/null | wc -l) agent files)"
echo "  .claude/commands/          ($(ls -1 "$PROJECT_ROOT/.claude/commands/" 2>/dev/null | wc -l) command files)"
echo "  .claude/skills/            ($(ls -1d "$PROJECT_ROOT/.claude/skills"/*/ 2>/dev/null | wc -l) skill modules)"
echo "  .claude/scripts/           ($(ls -1 "$PROJECT_ROOT/.claude/scripts/" 2>/dev/null | wc -l) scripts)"
