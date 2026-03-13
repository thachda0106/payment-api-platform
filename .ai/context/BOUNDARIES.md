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

- Scratchpad/planning phase: **HARD STOP** — requires human approval
- Architecture decisions: require explicit approval
- Breaking changes: require explicit approval
- New dependencies: verify existing alternatives first
