#!/bin/bash
# Aider Adapter — Install Script
# Reads .ai/ canonical structure and generates .aider.conf.yml + CONVENTIONS.md for Aider
#
# Usage: bash adapters/aider/install.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
AI_DIR="$PROJECT_ROOT/.ai"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}=== Aider Adapter Install ===${NC}"
echo ""

if [ ! -d "$AI_DIR" ]; then
    echo -e "${YELLOW}ERROR: .ai/ directory not found at $AI_DIR${NC}"
    exit 1
fi

# 1. Generate CONVENTIONS.md (Aider reads this via --read flag)
echo -e "${GREEN}✓${NC} Generating CONVENTIONS.md"
{
    echo "# AI Coding Conventions"
    echo ""
    echo "<!-- Auto-generated from .ai/context/ by Aider adapter -->"
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
} > "$PROJECT_ROOT/CONVENTIONS.md"

# 2. Generate .aider.conf.yml
echo -e "${GREEN}✓${NC} Generating .aider.conf.yml"
cat > "$PROJECT_ROOT/.aider.conf.yml" <<EOF
# Aider Configuration
# Auto-generated from .ai/ by Aider adapter
# Do not edit directly. Edit .ai/context/ files and re-run adapter.

# Read convention files automatically
read:
  - CONVENTIONS.md

# Auto-commit settings
auto-commits: true
EOF

echo ""
echo -e "${GREEN}=== Aider adapter installed successfully ===${NC}"
echo ""
echo "Generated files:"
echo "  .aider.conf.yml    (Aider configuration)"
echo "  CONVENTIONS.md     (merged context + system prompt for Aider to read)"
echo ""
echo "Usage: aider will automatically read CONVENTIONS.md on startup."
