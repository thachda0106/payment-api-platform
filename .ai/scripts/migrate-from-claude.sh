#!/bin/bash
# migrate-from-claude.sh
# Migrates an existing .claude/ project setup into the .ai/ canonical format
#
# Usage: bash .ai/scripts/migrate-from-claude.sh
#
# This script:
# 1. Reads existing .claude/ structure
# 2. Generates .ai/ canonical format
# 3. Preserves all agents, commands, skills, prompts, scripts, docs

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CLAUDE_DIR="$PROJECT_ROOT/.claude"
AI_DIR="$PROJECT_ROOT/.ai"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${BLUE}=== Claude Code → .ai/ Migration ===${NC}"
echo ""

# Check .claude/ exists
if [ ! -d "$CLAUDE_DIR" ]; then
    echo -e "${RED}ERROR: .claude/ directory not found${NC}"
    exit 1
fi

# Create .ai/ structure
echo -e "${BLUE}Creating .ai/ directories...${NC}"
mkdir -p "$AI_DIR/context"
mkdir -p "$AI_DIR/agents"
mkdir -p "$AI_DIR/workflows"
mkdir -p "$AI_DIR/skills"
mkdir -p "$AI_DIR/prompts/templates"
mkdir -p "$AI_DIR/scripts"
mkdir -p "$AI_DIR/docs"
mkdir -p "$AI_DIR/docs/mcp"

# 1. Migrate CLAUDE.md → context/PROJECT.md
echo -e "${GREEN}✓${NC} Migrating context files"
if [ -f "$PROJECT_ROOT/CLAUDE.md" ]; then
    # Copy entire CLAUDE.md as PROJECT.md (user should refine later)
    cp "$PROJECT_ROOT/CLAUDE.md" "$AI_DIR/context/PROJECT.md"
    echo "  → CLAUDE.md → context/PROJECT.md (review and split into CONVENTIONS.md and BOUNDARIES.md)"
fi

# Create placeholder context files if they don't exist
if [ ! -f "$AI_DIR/context/CONVENTIONS.md" ]; then
    echo "# Coding Conventions" > "$AI_DIR/context/CONVENTIONS.md"
    echo "" >> "$AI_DIR/context/CONVENTIONS.md"
    echo "> TODO: Extract coding conventions from PROJECT.md into this file" >> "$AI_DIR/context/CONVENTIONS.md"
fi

if [ ! -f "$AI_DIR/context/BOUNDARIES.md" ]; then
    echo "# Boundaries and Safety Invariants" > "$AI_DIR/context/BOUNDARIES.md"
    echo "" >> "$AI_DIR/context/BOUNDARIES.md"
    echo "> TODO: Extract boundaries and safety rules from PROJECT.md into this file" >> "$AI_DIR/context/BOUNDARIES.md"
fi

# 2. Migrate agents
echo -e "${GREEN}✓${NC} Migrating agents"
for f in "$CLAUDE_DIR"/agents/*.md; do
    [ -f "$f" ] || continue
    BASENAME=$(basename "$f" .md)

    # Check if frontmatter already exists
    if head -1 "$f" | grep -q '^---'; then
        cp "$f" "$AI_DIR/agents/$BASENAME.agent.md"
    else
        # Add basic frontmatter
        {
            echo "---"
            echo "name: $BASENAME"
            echo "description: Migrated from Claude Code agent"
            echo "---"
            echo ""
            cat "$f"
        } > "$AI_DIR/agents/$BASENAME.agent.md"
    fi
    echo "  → agents/$BASENAME.md → agents/$BASENAME.agent.md"
done

# 3. Migrate commands → workflows
echo -e "${GREEN}✓${NC} Migrating commands → workflows"
for f in "$CLAUDE_DIR"/commands/*.md; do
    [ -f "$f" ] || continue
    BASENAME=$(basename "$f")

    # Check if frontmatter already exists
    if head -1 "$f" | grep -q '^---'; then
        cp "$f" "$AI_DIR/workflows/$BASENAME"
    else
        # Extract @agent directive if present
        AGENT=$(grep -m1 '^@agent ' "$f" | sed 's/@agent //' || echo "")
        TITLE=$(head -1 "$f" | sed 's/^# //')

        {
            echo "---"
            echo "description: $TITLE"
            if [ -n "$AGENT" ]; then
                echo "agent: $AGENT"
            fi
            echo "---"
            echo ""
            # Remove the @agent line from body
            grep -v '^@agent ' "$f"
        } > "$AI_DIR/workflows/$BASENAME"
    fi
    echo "  → commands/$BASENAME → workflows/$BASENAME"
done

# 4. Migrate skills
echo -e "${GREEN}✓${NC} Migrating skills"
for d in "$CLAUDE_DIR"/skills/*/; do
    [ -d "$d" ] || continue
    SKILL_NAME=$(basename "$d")
    mkdir -p "$AI_DIR/skills/$SKILL_NAME"

    if [ -f "$d/SKILL.md" ]; then
        # Check if frontmatter exists
        if head -1 "$d/SKILL.md" | grep -q '^---'; then
            cp "$d/SKILL.md" "$AI_DIR/skills/$SKILL_NAME/SKILL.md"
        else
            {
                echo "---"
                echo "name: $SKILL_NAME"
                echo "description: Migrated from Claude Code skill"
                echo "---"
                echo ""
                cat "$d/SKILL.md"
            } > "$AI_DIR/skills/$SKILL_NAME/SKILL.md"
        fi
    fi

    # Copy subdirectories
    for sub in references scripts assets; do
        if [ -d "$d/$sub" ]; then
            cp -r "$d/$sub" "$AI_DIR/skills/$SKILL_NAME/"
        fi
    done
    echo "  → skills/$SKILL_NAME/"
done

# Copy skills README
if [ -f "$CLAUDE_DIR/skills/README.md" ]; then
    cp "$CLAUDE_DIR/skills/README.md" "$AI_DIR/skills/README.md"
fi

# 5. Migrate prompts
echo -e "${GREEN}✓${NC} Migrating prompts"
for f in "$CLAUDE_DIR"/prompts/*.md; do
    [ -f "$f" ] || continue
    cp "$f" "$AI_DIR/prompts/templates/$(basename "$f")"
    echo "  → prompts/$(basename "$f") → prompts/templates/$(basename "$f")"
done

# 6. Migrate scripts
echo -e "${GREEN}✓${NC} Migrating scripts"
for f in "$CLAUDE_DIR"/scripts/*; do
    [ -f "$f" ] || continue
    cp "$f" "$AI_DIR/scripts/$(basename "$f")"
    echo "  → scripts/$(basename "$f")"
done

# Migrate hooks as scripts
for f in "$CLAUDE_DIR"/hooks/*; do
    [ -f "$f" ] || continue
    cp "$f" "$AI_DIR/scripts/$(basename "$f")"
    echo "  → hooks/$(basename "$f") → scripts/$(basename "$f")"
done

# 7. Migrate docs
echo -e "${GREEN}✓${NC} Migrating docs"
for f in "$CLAUDE_DIR"/docs/*; do
    [ -f "$f" ] || continue
    cp "$f" "$AI_DIR/docs/$(basename "$f")"
    echo "  → docs/$(basename "$f")"
done

# Migrate MCP docs
for f in "$CLAUDE_DIR"/*_MCP.md "$CLAUDE_DIR"/MCP_SETUP.md; do
    [ -f "$f" ] || continue
    cp "$f" "$AI_DIR/docs/mcp/$(basename "$f")"
    echo "  → $(basename "$f") → docs/mcp/$(basename "$f")"
done

# Migrate top-level docs
for f in "$CLAUDE_DIR"/README.md "$CLAUDE_DIR"/ONBOARDING.md "$CLAUDE_DIR"/TROUBLESHOOTING.md "$CLAUDE_DIR"/QUICK_REFERENCE.md; do
    [ -f "$f" ] || continue
    cp "$f" "$AI_DIR/docs/$(basename "$f")"
    echo "  → $(basename "$f") → docs/$(basename "$f")"
done

# Create AI_MANIFEST.yaml if it doesn't exist
if [ ! -f "$AI_DIR/AI_MANIFEST.yaml" ]; then
    echo -e "${GREEN}✓${NC} Creating AI_MANIFEST.yaml"
    cat > "$AI_DIR/AI_MANIFEST.yaml" <<EOF
template:
  name: "migrated-from-claude"
  version: "1.0.0"
  description: "Migrated from Claude Code setup"
  source: "claude-code"

adapters: []

operating_model: "plan-review-execute-verify"
EOF
fi

echo ""
echo -e "${GREEN}=== Migration complete! ===${NC}"
echo ""
echo "Next steps:"
echo "  1. Review context/PROJECT.md and split into CONVENTIONS.md and BOUNDARIES.md"
echo "  2. Remove tool-specific references from skills (replace with generic actions)"
echo "  3. Run: bash .ai/scripts/validate-template.sh"
echo "  4. Run: bash .ai/scripts/install-ai-template.sh"
