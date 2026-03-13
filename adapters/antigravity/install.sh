#!/bin/bash
# Antigravity Adapter — Install Script
# Reads .ai/ canonical structure and generates .agent/ + .gemini/ for Antigravity IDE
#
# Usage: bash adapters/antigravity/install.sh [--dry-run]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
AI_DIR="$PROJECT_ROOT/.ai"

# Load shared library
source "$PROJECT_ROOT/.ai/scripts/_lib.sh"
parse_flags "$@"

echo -e "${BLUE}=== Antigravity Adapter Install ===${NC}"
echo ""

# Verify .ai/ exists
if [ ! -d "$AI_DIR" ]; then
    echo -e "${RED}ERROR: .ai/ directory not found at $AI_DIR${NC}"
    exit 1
fi

# Create target directories
echo -e "${BLUE}Creating directories...${NC}"
mkdir -p "$PROJECT_ROOT/.agent/workflows"
mkdir -p "$PROJECT_ROOT/.agent/skills"
mkdir -p "$PROJECT_ROOT/.agent/scripts"
mkdir -p "$PROJECT_ROOT/.agent/docs"
mkdir -p "$PROJECT_ROOT/.gemini"

# 1. Merge context files into .gemini/STYLE.md (using shared lib)
echo -e "${GREEN}✓${NC} Generating .gemini/STYLE.md from context files"
merge_context_files "$AI_DIR" "$PROJECT_ROOT/.gemini/STYLE.md" "# Project AI Instructions" "Antigravity"

# 2. Copy workflows
echo -e "${GREEN}✓${NC} Copying workflows"
for f in "$AI_DIR"/workflows/*.md; do
    [ -f "$f" ] || continue
    cp "$f" "$PROJECT_ROOT/.agent/workflows/$(basename "$f")"
done

# 3. Copy skills (using shared lib)
echo -e "${GREEN}✓${NC} Copying skills"
copy_skills "$AI_DIR/skills" "$PROJECT_ROOT/.agent/skills"

# 4. Copy scripts (exclude template-level scripts)
echo -e "${GREEN}✓${NC} Copying scripts"
for f in "$AI_DIR"/scripts/*; do
    [ -f "$f" ] || continue
    BASENAME=$(basename "$f")
    case "$BASENAME" in
        install-ai-template.sh|migrate-from-claude.sh|validate-template.sh|_lib.sh) continue ;;
    esac
    cp "$f" "$PROJECT_ROOT/.agent/scripts/$BASENAME"
done

# 5. Copy docs
echo -e "${GREEN}✓${NC} Copying docs"
for f in "$AI_DIR"/docs/*; do
    [ -f "$f" ] || continue
    cp "$f" "$PROJECT_ROOT/.agent/docs/$(basename "$f")"
done
# Copy doc subdirectories
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

echo ""
echo -e "${GREEN}=== Antigravity adapter installed successfully ===${NC}"
echo ""
echo "Generated files:"
echo "  .gemini/STYLE.md          (merged context + system prompt)"
echo "  .agent/workflows/         ($(ls -1 "$PROJECT_ROOT/.agent/workflows/" 2>/dev/null | wc -l) workflow files)"
echo "  .agent/skills/            ($(ls -1d "$PROJECT_ROOT/.agent/skills"/*/ 2>/dev/null | wc -l) skill modules)"
echo "  .agent/scripts/           ($(ls -1 "$PROJECT_ROOT/.agent/scripts/" 2>/dev/null | wc -l) scripts)"
echo "  .agent/docs/              ($(ls -1 "$PROJECT_ROOT/.agent/docs/" 2>/dev/null | wc -l) docs)"
