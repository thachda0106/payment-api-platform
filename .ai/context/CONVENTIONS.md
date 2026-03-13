# Coding Conventions

> **Instructions**: Fill in this template with your project's coding standards.
> These rules are enforced by the AI assistant during code generation and review.

## Communication Style

- Respond in a purely technical, objective manner
- No emotional language, pleasantries, or personal opinions
- Focus exclusively on technical accuracy and implementation details

## Naming Conventions

| Element | Convention | Example |
|---------|-----------|---------|
| Files | {{conventions.file_naming}} | {{conventions.file_example}} |
| Classes | {{conventions.class_naming}} | {{conventions.class_example}} |
| Variables | {{conventions.variable_naming}} | {{conventions.variable_example}} |
| Constants | {{conventions.constant_naming}} | {{conventions.constant_example}} |
| Enums | {{conventions.enum_naming}} | {{conventions.enum_example}} |

## Import Ordering

{{conventions.import_ordering}}

## Code Organization

{{conventions.code_organization}}

## Type Safety

- Use strict typing — avoid `any` without justification
- Leverage type inference where types are obvious
- Extract shared types to a common location if reused across modules

## Error Handling

{{conventions.error_handling}}

## Testing Standards

- Tests verify behavior, not implementation details
- Mock external dependencies (database, HTTP, file system)
- Arrange-Act-Assert pattern
- One behavior per test case
- Descriptive test names that explain the scenario

## Documentation

- Add comments only for non-obvious logic
- Keep inline documentation concise
- Update documentation when code changes
