---
description: Systematically diagnose and fix bugs in the codebase
agent: bug-hunter
---

# Fix Bug

Systematically diagnose and fix bugs following the Bug Hunter agent protocol.

## Steps

### 1. Understand the Bug

- Read the bug description/error message carefully
- Identify affected functionality
- Determine reproduction steps
- Check if it's a regression (did it work before?)

### 2. Locate the Issue

- Use error stack trace to find source
- Search for relevant code patterns
- Check related modules and layers
- Review recent changes (git blame/log)

### 3. Diagnose Root Cause

Common bug categories:
- **Request Lifecycle**: Middleware issues, guard failures, interceptor problems
- **Dependency Injection**: Missing providers, circular dependencies
- **Database/ORM**: Query errors, mapping issues, migration problems
- **Type Errors**: Type mismatches, null/undefined, incorrect assertions
- **Async Issues**: Unhandled promises, race conditions
- **Validation**: Incorrect validation, missing checks
- **API/Data**: Wrong endpoint, incorrect format, missing error handling

### 4. Plan the Fix

- Identify minimal change needed
- Consider side effects and edge cases
- Ensure fix doesn't break other features
- Plan verification strategy

### 5. Implement the Fix

- Make targeted changes (avoid over-engineering)
- Add defensive checks if needed
- Update types if necessary
- Add comments for non-obvious fixes

// turbo
### 6. Verify the Fix

- Test the specific bug scenario
- Test related functionality
- Run unit/integration tests
- Check for new type errors
- Verify no regressions

## Output Format

### 1. Bug Analysis
- **Symptom**: What's wrong
- **Root Cause**: Why it's happening
- **Impact**: What's affected

### 2. Solution
- **Approach**: How to fix it
- **Changes**: What code will change
- **Trade-offs**: Any considerations

### 3. Verification
- How to test that the fix works

### 4. Prevention
- How to avoid similar bugs in the future
