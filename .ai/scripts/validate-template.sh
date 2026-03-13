#!/bin/bash
# validate-template.sh
# Validates .ai/ template structure and file format integrity
#
# Usage: bash .ai/scripts/validate-template.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AI_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

ERRORS=0
WARNINGS=0
PASSED=0

echo -e "${BLUE}=== AI Template Validation ===${NC}"
echo ""

check_pass() {
    echo -e "${GREEN}✓${NC} $1"
    PASSED=$((PASSED + 1))
}

check_fail() {
    echo -e "${RED}✗${NC} $1"
    ERRORS=$((ERRORS + 1))
}

check_warn() {
    echo -e "${YELLOW}⚠${NC} $1"
    WARNINGS=$((WARNINGS + 1))
}

# 1. Required directories
echo -e "${BLUE}Checking directory structure...${NC}"
for dir in context agents workflows skills prompts scripts docs; do
    if [ -d "$AI_DIR/$dir" ]; then
        check_pass "$dir/ exists"
    else
        check_fail "$dir/ missing"
    fi
done

# 2. AI_MANIFEST.yaml
echo ""
echo -e "${BLUE}Checking manifest...${NC}"
if [ -f "$AI_DIR/AI_MANIFEST.yaml" ]; then
    check_pass "AI_MANIFEST.yaml exists"
else
    check_fail "AI_MANIFEST.yaml missing"
fi

# 3. Context files
echo ""
echo -e "${BLUE}Checking context files...${NC}"
for f in PROJECT.md CONVENTIONS.md BOUNDARIES.md; do
    if [ -f "$AI_DIR/context/$f" ]; then
        check_pass "context/$f exists"
    else
        check_fail "context/$f missing"
    fi
done

# 4. Agent files have frontmatter
echo ""
echo -e "${BLUE}Checking agent files...${NC}"
AGENT_COUNT=0
for f in "$AI_DIR"/agents/*.agent.md; do
    [ -f "$f" ] || continue
    AGENT_COUNT=$((AGENT_COUNT + 1))
    BASENAME=$(basename "$f")

    # Check YAML frontmatter
    if head -1 "$f" | grep -q '^---'; then
        # Check required fields
        if grep -q '^name:' "$f"; then
            if grep -q '^description:' "$f"; then
                check_pass "$BASENAME — valid frontmatter"
            else
                check_fail "$BASENAME — missing 'description:' field"
            fi
        else
            check_fail "$BASENAME — missing 'name:' field"
        fi
    else
        check_fail "$BASENAME — missing YAML frontmatter"
    fi
done
[ $AGENT_COUNT -eq 0 ] && check_warn "No agent files found"

# 5. Workflow files have frontmatter
echo ""
echo -e "${BLUE}Checking workflow files...${NC}"
WORKFLOW_COUNT=0
for f in "$AI_DIR"/workflows/*.md; do
    [ -f "$f" ] || continue
    WORKFLOW_COUNT=$((WORKFLOW_COUNT + 1))
    BASENAME=$(basename "$f")

    if head -1 "$f" | grep -q '^---'; then
        if grep -q '^description:' "$f"; then
            check_pass "$BASENAME — valid frontmatter"
        else
            check_fail "$BASENAME — missing 'description:' field"
        fi
    else
        check_fail "$BASENAME — missing YAML frontmatter"
    fi
done
[ $WORKFLOW_COUNT -eq 0 ] && check_warn "No workflow files found"

# 6. Skill SKILL.md files exist
echo ""
echo -e "${BLUE}Checking skill modules...${NC}"
SKILL_COUNT=0
for d in "$AI_DIR"/skills/*/; do
    [ -d "$d" ] || continue
    SKILL_COUNT=$((SKILL_COUNT + 1))
    SKILL_NAME=$(basename "$d")

    if [ -f "$d/SKILL.md" ]; then
        if head -1 "$d/SKILL.md" | grep -q '^---'; then
            check_pass "$SKILL_NAME/SKILL.md — valid"
        else
            check_fail "$SKILL_NAME/SKILL.md — missing YAML frontmatter"
        fi
    else
        check_fail "$SKILL_NAME/ — missing SKILL.md"
    fi
done
[ $SKILL_COUNT -eq 0 ] && check_warn "No skill modules found"

# 7. Check for tool-specific references in core .ai/ files
echo ""
echo -e "${BLUE}Checking for tool-specific references...${NC}"
TOOL_PATTERNS="mcp__serena\|mcp__context7\|grep_search\|view_file\|find_by_name\|run_command\|replace_file_content\|write_to_file"
TOOL_REFS=$(grep -rl "$TOOL_PATTERNS" "$AI_DIR/agents/" "$AI_DIR/workflows/" "$AI_DIR/skills/" 2>/dev/null || true)
if [ -z "$TOOL_REFS" ]; then
    check_pass "No tool-specific references found in core files"
else
    for ref in $TOOL_REFS; do
        check_warn "Tool-specific reference in: $(basename "$ref")"
    done
fi

# Summary
echo ""
echo -e "${BLUE}=== Summary ===${NC}"
echo -e "${GREEN}Passed: $PASSED${NC}"
echo -e "${RED}Errors: $ERRORS${NC}"
echo -e "${YELLOW}Warnings: $WARNINGS${NC}"
echo ""
echo "Counts: $AGENT_COUNT agents, $WORKFLOW_COUNT workflows, $SKILL_COUNT skills"

if [ $ERRORS -eq 0 ]; then
    echo -e "${GREEN}✓ Template validation passed!${NC}"
    exit 0
else
    echo -e "${RED}✗ $ERRORS validation errors found.${NC}"
    exit 1
fi
