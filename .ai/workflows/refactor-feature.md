---
description: Refactor code for clarity and maintainability without changing behavior
agent: feature-builder
---

# Refactor Feature

Refactor the selected code for clarity and maintainability without changing behavior.

## Refactoring Principles

1. **Code Clarity** — Extract complex logic into named functions, use descriptive names
2. **DRY** — Extract repeated logic into utilities, create shared services
3. **Type Safety** — Strict typing, leverage inference, extract shared types
4. **Single Responsibility** — Each function/class does one thing
5. **Performance** — Optimize queries, avoid N+1, cache where appropriate

## Process

1. **Understand** — Read and comprehend current code
2. **Identify** — Find code smells and improvement areas
3. **Analyze** — Find all references and dependencies of the code being refactored
4. **Plan** — Outline refactoring steps
5. **Execute** — Refactor step by step
6. **Verify** — Ensure behavior unchanged (run tests)

## Code Smells to Address

- Long functions (>80 lines)
- Deep nesting (>3 levels)
- Repeated code blocks
- Magic numbers/strings
- Complex boolean expressions
- Large modules/classes (>500 lines)
- API layer with business logic
- Missing error handling

## Output Format

For each refactoring:
1. **What** — Describe what is being refactored
2. **Why** — Explain the problem or improvement
3. **How** — Show before/after with explanation
4. **Impact** — Performance or maintainability gains

// turbo
## Verification

Run tests, type check, and lint after refactoring.
