# Boundaries and Safety Invariants

> **Instructions**: Define what the AI assistant is NOT allowed to do.
> These are hard constraints enforced across all tools and workflows.

## Scope Rules

- Do NOT modify code outside the scope of the current task
- Do NOT refactor unrelated code while fixing bugs
- Do NOT optimize unless explicitly requested
- If > 3 files need changes for a single task, verify scope is appropriate
- Each file is written once per task — re-read before re-writing

## Forbidden Actions

### Destructive Operations
- Never execute `rm -rf` or equivalent destructive commands
- Never run `git push` or `git reset --hard` without explicit approval
- Never modify build output directories (`dist/`, `build/`, `out/`)
- Never modify `node_modules/` or dependency lock files directly

### Sensitive Data
- Never read `.env` files or environment variable files
- Never read secrets, credentials, or API keys
- Never read Terraform state or infrastructure secrets
- Never expose sensitive data in code, logs, or documentation

### Code Integrity
- Never mix different editing strategies on the same file
- Never wipe entire files — edit section by section
- If a file changes externally, re-read before writing
- When multiple files are related, read all first, then write one by one

### Skipping Approval Gates
- **Never skip a human approval gate** — this is a critical safety violation
- Never generate SCRATCHPAD and PLAN in the same response
- Never generate PLAN and TASKS in the same response
- Never write implementation code before TASKS are approved
- Never run all workflow phases in a single execution
- Never assume approval — wait for explicit user confirmation

## MCP / External Tool Usage Rules

### Documentation Tools (e.g., Context7)
- Use for external library documentation only
- Do NOT use as a substitute for reading project code
- Do NOT rely on assumed knowledge — fetch latest docs when uncertain

### Code Analysis Tools (e.g., Serena, language servers)
- Use for symbol-level and semantic code analysis
- Do NOT use for broad text searches — use grep/search instead
- Do NOT use for file listing — use directory tools instead

### Repository Tools (e.g., Bitbucket, GitHub)
- Use for PR management, code review, issue tracking
- Authentication must be configured separately per developer
- Never commit secrets through repository tools

## Human Review Checkpoints

> [!CAUTION]
> Every checkpoint below is a **HARD STOP**. The AI must pause and wait for explicit human approval.

| # | Checkpoint | Output Artifact | AI Must Say |
|---|-----------|----------------|-------------|
| 1 | After SCRATCHPAD phase | `SCRATCHPAD.md` | "Please review the scratchpad. Reply APPROVE to continue." |
| 2 | After PLAN phase | `PLAN.md` | "Please review the plan. Reply APPROVE to continue." |
| 3 | After TASKS phase | `TASKS.md` | "Please review the tasks. Reply APPROVE to continue." |
| 4 | After IMPLEMENTATION | Completed code | "Implementation complete. Please review before final testing." |

Additional review triggers:
- Architecture decisions: require explicit approval
- Breaking changes: require explicit approval
- New dependencies: verify existing alternatives first
- If a decision changes during execution: update scratchpad and STOP for re-approval
