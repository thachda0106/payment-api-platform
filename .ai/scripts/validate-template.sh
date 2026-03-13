#!/bin/bash
# validate-template.sh
# Validates .ai/ template structure, file format integrity, and cross-references
#
# Usage: bash .ai/scripts/validate-template.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AI_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Load shared library
source "$SCRIPT_DIR/_lib.sh"

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

# ============================================================
# 1. Required directories
# ============================================================
echo -e "${BLUE}Checking directory structure...${NC}"
for dir in context agents workflows skills prompts scripts docs; do
    if [ -d "$AI_DIR/$dir" ]; then
        check_pass "$dir/ exists"
    else
        check_fail "$dir/ missing"
    fi
done

# ============================================================
# 2. AI_MANIFEST.yaml
# ============================================================
echo ""
echo -e "${BLUE}Checking manifest...${NC}"
if [ -f "$AI_DIR/AI_MANIFEST.yaml" ]; then
    check_pass "AI_MANIFEST.yaml exists"
else
    check_fail "AI_MANIFEST.yaml missing"
fi

# ============================================================
# 3. Context files
# ============================================================
echo ""
echo -e "${BLUE}Checking context files...${NC}"
for f in PROJECT.md CONVENTIONS.md BOUNDARIES.md; do
    if [ -f "$AI_DIR/context/$f" ]; then
        check_pass "context/$f exists"
    else
        check_fail "context/$f missing"
    fi
done

# ============================================================
# 4. Agent files — frontmatter validation
# ============================================================
echo ""
echo -e "${BLUE}Checking agent files...${NC}"
AGENT_COUNT=0
AGENT_NAMES=()
for f in "$AI_DIR"/agents/*.agent.md; do
    [ -f "$f" ] || continue
    AGENT_COUNT=$((AGENT_COUNT + 1))
    BASENAME=$(basename "$f")

    # Check YAML frontmatter
    if head -1 "$f" | grep -q '^---'; then
        # Check required fields
        if grep -q '^name:' "$f"; then
            AGENT_NAME=$(grep '^name:' "$f" | head -1 | sed 's/^name: *//')
            AGENT_NAMES+=("$AGENT_NAME")
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

# ============================================================
# 5. Workflow files — frontmatter and agent cross-reference
# ============================================================
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

        # Cross-reference: check agent field references an existing agent
        AGENT_REF=$(grep '^agent:' "$f" | head -1 | sed 's/^agent: *//' || echo "")
        if [ -n "$AGENT_REF" ]; then
            FOUND=false
            for name in "${AGENT_NAMES[@]+"${AGENT_NAMES[@]}"}"; do
                if [ "$name" = "$AGENT_REF" ]; then
                    FOUND=true
                    break
                fi
            done
            if [ "$FOUND" = true ]; then
                check_pass "$BASENAME — agent '$AGENT_REF' exists"
            else
                check_fail "$BASENAME — references unknown agent '$AGENT_REF'"
            fi
        fi
    else
        check_fail "$BASENAME — missing YAML frontmatter"
    fi
done
[ $WORKFLOW_COUNT -eq 0 ] && check_warn "No workflow files found"

# ============================================================
# 6. Skill SKILL.md files — existence and frontmatter
# ============================================================
echo ""
echo -e "${BLUE}Checking skill modules...${NC}"
SKILL_COUNT=0
SKILL_NAMES=()
for d in "$AI_DIR"/skills/*/; do
    [ -d "$d" ] || continue
    SKILL_COUNT=$((SKILL_COUNT + 1))
    SKILL_NAME=$(basename "$d")
    SKILL_NAMES+=("$SKILL_NAME")

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

# ============================================================
# 7. Agent → Skill cross-reference validation
# ============================================================
echo ""
echo -e "${BLUE}Checking agent → skill cross-references...${NC}"
for f in "$AI_DIR"/agents/*.agent.md; do
    [ -f "$f" ] || continue
    BASENAME=$(basename "$f")

    # Extract skills from frontmatter
    IN_SKILLS=false
    while IFS= read -r line; do
        if [[ "$line" == "skills:" ]]; then
            IN_SKILLS=true
            continue
        fi
        if [ "$IN_SKILLS" = true ]; then
            if [[ "$line" =~ ^[[:space:]]*-[[:space:]]+(.*) ]]; then
                SKILL_REF="${BASH_REMATCH[1]}"
                FOUND=false
                for name in "${SKILL_NAMES[@]+"${SKILL_NAMES[@]}"}"; do
                    if [ "$name" = "$SKILL_REF" ]; then
                        FOUND=true
                        break
                    fi
                done
                if [ "$FOUND" = false ]; then
                    check_fail "$BASENAME — references unknown skill '$SKILL_REF'"
                fi
            elif [[ "$line" =~ ^[a-z] ]]; then
                IN_SKILLS=false
            fi
        fi
    done < "$f"
done

# ============================================================
# 8. Check for tool-specific references in core .ai/ files
# ============================================================
echo ""
echo -e "${BLUE}Checking for tool-specific references...${NC}"
TOOL_PATTERNS="mcp__serena\|mcp__context7\|Context7\|grep_search\|view_file\|find_by_name\|run_command\|replace_file_content\|write_to_file"
TOOL_REFS=$(grep -rl "$TOOL_PATTERNS" "$AI_DIR/agents/" "$AI_DIR/workflows/" "$AI_DIR/skills/" 2>/dev/null || true)
if [ -z "$TOOL_REFS" ]; then
    check_pass "No tool-specific references found in core files"
else
    for ref in $TOOL_REFS; do
        check_warn "Tool-specific reference in: $(basename "$ref")"
    done
fi

# ============================================================
# 9. Check for unresolved placeholders in context files
# ============================================================
echo ""
echo -e "${BLUE}Checking for unresolved placeholders...${NC}"
PLACEHOLDER_PATTERN='\{\{[a-zA-Z_.]*\}\}'
PLACEHOLDER_COUNT=0
for f in "$AI_DIR"/context/*.md; do
    [ -f "$f" ] || continue
    MATCHES=$(grep -c "$PLACEHOLDER_PATTERN" "$f" 2>/dev/null || echo "0")
    if [ "$MATCHES" -gt 0 ]; then
        check_warn "$(basename "$f") has $MATCHES unresolved placeholder(s) — fill in before production use"
        PLACEHOLDER_COUNT=$((PLACEHOLDER_COUNT + MATCHES))
    fi
done
if [ "$PLACEHOLDER_COUNT" -eq 0 ]; then
    check_pass "No unresolved placeholders in context files"
fi

# ============================================================
# 10. Check adapters have required files
# ============================================================
echo ""
echo -e "${BLUE}Checking adapter structure...${NC}"
ADAPTER_ROOT="$(cd "$AI_DIR/.." && pwd)/adapters"
if [ -d "$ADAPTER_ROOT" ]; then
    for adapter_dir in "$ADAPTER_ROOT"/*/; do
        [ -d "$adapter_dir" ] || continue
        ADAPTER_NAME=$(basename "$adapter_dir")
        if [ -f "$adapter_dir/mapping.yaml" ] && [ -f "$adapter_dir/install.sh" ]; then
            check_pass "adapters/$ADAPTER_NAME — has mapping.yaml + install.sh"
        else
            [ -f "$adapter_dir/mapping.yaml" ] || check_fail "adapters/$ADAPTER_NAME — missing mapping.yaml"
            [ -f "$adapter_dir/install.sh" ] || check_fail "adapters/$ADAPTER_NAME — missing install.sh"
        fi
        if [ -f "$adapter_dir/clean.sh" ]; then
            check_pass "adapters/$ADAPTER_NAME — has clean.sh"
        else
            check_warn "adapters/$ADAPTER_NAME — missing clean.sh (recommended)"
        fi
    done
fi

# ============================================================
# Summary
# ============================================================
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
