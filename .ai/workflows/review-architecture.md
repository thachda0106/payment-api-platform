---
description: Review code strictly against project architecture rules and call out violations
agent: code-reviewer
---

# Review Architecture

Review the codebase against the project's architecture rules as defined in `context/PROJECT.md` and `context/CONVENTIONS.md`.

## What to Check

### Directory Structure
- Files are in correct locations per project conventions
- Module/component boundaries are respected
- Naming patterns match conventions

### Import Boundaries
- No cross-boundary imports that violate architecture rules
- Path aliases used instead of deep relative imports
- No circular dependencies between modules

### Module Structure Rules
- Separation of concerns (controller → service → repository)
- Business logic in correct layer
- API layer is thin, delegates to business logic
- Data access is isolated in its own layer

### Dependency Injection Rules
- Constructor injection used properly
- No circular dependencies
- Providers registered and exported correctly

### Naming Conventions
- Files follow naming pattern (e.g., kebab-case with type suffix)
- Classes follow pattern (e.g., PascalCase)
- Variables follow pattern

## Validation Checklist

- [ ] Import from wrong level
- [ ] Missing path aliases (deep relative imports)
- [ ] Wrong directory structure
- [ ] Incorrect naming
- [ ] Circular dependencies
- [ ] Business logic in API layer
- [ ] Missing input validation
- [ ] Test files in wrong location
- [ ] Hardcoded values

## Output Format

### Violations Found

For each violation:
- **File**: Path to file
- **Line**: Line number(s)
- **Rule**: Which architecture rule is violated
- **Impact**: Why this matters
- **Fix**: How to resolve

### Architecture Score

Rate compliance: **Pass** | **Minor Issues** | **Major Violations**
