---
description: Master AI-assisted development lifecycle — plan, review, execute, verify
---

# AI Workflow — Plan → Review → Execute → Verify

This is the **mandatory workflow** for all non-trivial tasks. It defines the execution lifecycle that any AI assistant must follow.

## STEP 1 — SCRATCHPAD (WHY / SCOPE / INVARIANTS)

**You are NOT allowed to write code in this step.**

1. Read project context files (`context/PROJECT.md`, `context/CONVENTIONS.md`, `context/BOUNDARIES.md`)
2. Analyze the task
3. Create a scratchpad using `prompts/templates/scratchpad.md` with:
   - Current Objective
   - Context / Scope
   - Architecture Invariants
   - Decisions Made (if any)
   - Risks / Open Questions
4. Do NOT implement anything
5. **STOP and wait for human approval**

## STEP 2 — HUMAN REVIEW (HARD STOP)

Reviewer actions:
- ✅ Approve scratchpad
- ❌ Request changes (scope, invariants, decisions)

If changes are requested, update scratchpad only and stop again.

**This step is mandatory. No planning or coding may proceed without approval.**

## STEP 3 — PLAN / PRD / PRP (HOW)

Scratchpad is now APPROVED. Based strictly on the scratchpad:

1. Create a detailed implementation plan:
   - Tasks in execution order
   - Files to modify or create
   - Validation strategy (tests, checks)
   - Explicit non-goals
2. Do NOT write final code yet
3. Highlight any new decisions that would require scratchpad updates
4. If the plan contradicts scratchpad invariants → **STOP** and request scratchpad update

## STEP 4 — EXECUTION (CODE)

Proceed with implementation:
- Follow scratchpad invariants strictly
- Implement one task at a time
- If a decision changes → update scratchpad FIRST
- Include tests and validation
- Do not introduce scope creep

## STEP 5 — VALIDATION & DONE

Validate:
- Tests passing
- Lint / type checks clean
- Behavior matches scratchpad objective

If something deviates:
- Explain why
- Propose scratchpad update
- Check logs / metrics (if applicable)

## Dev Checklist (Mental Model)

Before coding, ask:
- ❓ Do we have a scratchpad?
- ❓ Is it approved by a human?
- ❓ Are invariants explicit?
- ❓ Is scope locked?

If any answer is NO → **STOP**.

> **GOLDEN RULE**: Scratchpad is the source of truth. Plans explain HOW. Code is the last step.
