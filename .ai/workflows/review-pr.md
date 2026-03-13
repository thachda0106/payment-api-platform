---
description: Review code for quality, consistency, and architectural compliance
agent: code-reviewer
---

# Review PR / Code Review

Review code strictly against project architecture rules and coding conventions.

## Review Focus Areas

### 1. Architecture Compliance
- Verify module/component structure follows project patterns
- Check import boundaries and dependency rules
- Confirm proper use of path aliases
- No circular dependencies
- Proper module exports and imports

### 2. Code Quality
- Import ordering follows project conventions
- Consistent string quoting style
- Strict typing (no `any` without justification)
- Naming conventions followed
- No magic numbers/strings

### 3. Framework Best Practices
- Proper use of framework decorators/patterns
- Correct dependency injection
- Lifecycle hooks implementation
- Exception/error handling with standard types

### 4. Database / Data Layer
- Entity/model definitions with proper annotations
- Repository pattern usage
- Proper relations and cascades
- Transaction handling for multi-step operations
- Avoid N+1 query problems

### 5. API Design
- RESTful naming (if applicable)
- Proper HTTP methods and status codes
- Input validation
- Response serialization

### 6. Error Handling
- Standard exception/error classes used
- Consistent error response format
- No unhandled promise rejections

### 7. Testing
- Tests exist for new features
- Tests cover success and failure paths
- Proper mocking of dependencies

### 8. Security
- Input validation on all entry points
- No injection vulnerabilities
- No sensitive data exposure
- No hardcoded secrets

## Output Format

Use the review template from `prompts/templates/review-output.md`:
- **🔴 Critical**: Security, breaking changes, major bugs
- **🟡 Warning**: Anti-patterns, performance issues, maintainability
- **🔵 Info**: Minor improvements, style suggestions

### Approval Status
- ✅ **Approved**: Ready to merge
- ⚠️ **Approved with Comments**: Minor issues, can merge with follow-up
- ❌ **Changes Requested**: Must address critical issues before merge
