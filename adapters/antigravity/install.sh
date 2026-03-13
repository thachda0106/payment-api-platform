#!/bin/bash
# Antigravity Adapter — Install Script
# Reads .ai/ canonical structure and generates .agent/ + .gemini/ for Antigravity IDE
#
# Usage: bash adapters/antigravity/install.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
AI_DIR="$PROJECT_ROOT/.ai"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}=== Antigravity Adapter Install ===${NC}"
echo ""

# Verify .ai/ exists
if [ ! -d "$AI_DIR" ]; then
    echo -e "${YELLOW}ERROR: .ai/ directory not found at $AI_DIR${NC}"
    exit 1
fi

# Create target directories
echo -e "${BLUE}Creating directories...${NC}"
mkdir -p "$PROJECT_ROOT/.agent/workflows"
mkdir -p "$PROJECT_ROOT/.agent/skills"
mkdir -p "$PROJECT_ROOT/.agent/scripts"
mkdir -p "$PROJECT_ROOT/.agent/docs"
mkdir -p "$PROJECT_ROOT/.gemini"

# 1. Merge context files into .gemini/STYLE.md
echo -e "${GREEN}✓${NC} Generating .gemini/STYLE.md from context files"
{
    echo "# Project AI Instructions"
    echo ""
    echo "<!-- Auto-generated from .ai/context/ by Antigravity adapter -->"
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
} > "$PROJECT_ROOT/.gemini/STYLE.md"

# 2. Copy workflows
echo -e "${GREEN}✓${NC} Copying workflows"
for f in "$AI_DIR"/workflows/*.md; do
    [ -f "$f" ] || continue
    cp "$f" "$PROJECT_ROOT/.agent/workflows/$(basename "$f")"
done

# 3. Copy skills
echo -e "${GREEN}✓${NC} Copying skills"
for d in "$AI_DIR"/skills/*/; do
    [ -d "$d" ] || continue
    SKILL_NAME=$(basename "$d")
    mkdir -p "$PROJECT_ROOT/.agent/skills/$SKILL_NAME"
    if [ -f "$d/SKILL.md" ]; then
        cp "$d/SKILL.md" "$PROJECT_ROOT/.agent/skills/$SKILL_NAME/SKILL.md"
    fi
    # Copy subdirectories (references, scripts, assets)
    for sub in references scripts assets; do
        if [ -d "$d/$sub" ]; then
            cp -r "$d/$sub" "$PROJECT_ROOT/.agent/skills/$SKILL_NAME/"
        fi
    done
done

# 4. Copy scripts
echo -e "${GREEN}✓${NC} Copying scripts"
for f in "$AI_DIR"/scripts/*; do
    [ -f "$f" ] || continue
    BASENAME=$(basename "$f")
    # Skip install/migrate/validate scripts (they're template-level, not project-level)
    case "$BASENAME" in
        install-ai-template.sh|migrate-from-claude.sh|validate-template.sh)
            continue
            ;;
    esac
    cp "$f" "$PROJECT_ROOT/.agent/scripts/$BASENAME"
done

# 5. Copy docs
echo -e "${GREEN}✓${NC} Copying docs"
for f in "$AI_DIR"/docs/*; do
    [ -f "$f" ] || continue
    cp "$f" "$PROJECT_ROOT/.agent/docs/$(basename "$f")"
done
# Copy subdirectories
for d in "$AI_DIR"/docs/*/; do
    [ -d "$d" ] || continue
    DIRNAME=$(basename "$d")
    mkdir -p "$PROJECT_ROOT/.agent/docs/$DIRNAME"
    cp -r "$d"* "$PROJECT_ROOT/.agent/docs/$DIRNAME/" 2>/dev/null || true
done

# 6. Copy README
if [ -f "$AI_DIR/README.md" ]; then
    cp "$AI_DIR/README.md" "$PROJECT_ROOT/.agent/README.md"
fi

# 7. Copy skills README
if [ -f "$AI_DIR/skills/README.md" ]; then
    cp "$AI_DIR/skills/README.md" "$PROJECT_ROOT/.agent/skills/README.md"
fi

echo ""
echo -e "${GREEN}=== Antigravity adapter installed successfully ===${NC}"
echo ""
echo "Generated files:"
echo "  .gemini/STYLE.md          (merged context + system prompt)"
echo "  .agent/workflows/         ($(ls -1 "$PROJECT_ROOT/.agent/workflows/" 2>/dev/null | wc -l) workflow files)"
echo "  .agent/skills/            ($(ls -1d "$PROJECT_ROOT/.agent/skills"/*/ 2>/dev/null | wc -l) skill modules)"
echo "  .agent/scripts/           ($(ls -1 "$PROJECT_ROOT/.agent/scripts/" 2>/dev/null | wc -l) scripts)"
echo "  .agent/docs/              ($(ls -1 "$PROJECT_ROOT/.agent/docs/" 2>/dev/null | wc -l) docs)"
