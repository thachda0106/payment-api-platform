#!/bin/bash
# Aider Adapter — Install Script
# Reads .ai/ canonical structure and generates .aider.conf.yml + CONVENTIONS.md for Aider
#
# Usage: bash adapters/aider/install.sh [--dry-run]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
AI_DIR="$PROJECT_ROOT/.ai"

# Load shared library
source "$PROJECT_ROOT/.ai/scripts/_lib.sh"
parse_flags "$@"

echo -e "${BLUE}=== Aider Adapter Install ===${NC}"
echo ""

if [ ! -d "$AI_DIR" ]; then
    echo -e "${RED}ERROR: .ai/ directory not found at $AI_DIR${NC}"
    exit 1
fi

# 1. Generate CONVENTIONS.md (using shared lib)
echo -e "${GREEN}✓${NC} Generating CONVENTIONS.md"
merge_context_files "$AI_DIR" "$PROJECT_ROOT/CONVENTIONS.md" "# AI Coding Conventions" "Aider"

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
