---
description: Generate comprehensive tests following project testing standards
agent: test-engineer
---

# Add Tests

Generate comprehensive tests for the selected code following project testing standards.

## Steps

### 1. Select Test Type

Ask user which test type to generate:
- **Unit Test** — Isolated module testing with mocked dependencies (default)
- **Integration Test** — Full flow with real dependencies
- **E2E Test** — End-to-end API/UI testing

### 2. Analyze Target Code

- Read the code to be tested
- Understand business logic and edge cases
- Identify dependencies to mock
- Find existing test patterns for consistency

### 3. Generate Tests

Follow project conventions:
- Arrange-Act-Assert pattern
- Descriptive test names explaining the scenario
- Proper mock setup and cleanup
- One behavior per test case

### 4. Coverage Areas

- Service/business logic — rules, validations, transformations
- Error handling — exception throwing, error responses
- Data access — CRUD operations, queries
- API/Handler — request handling, response formatting
- Validation — input validation rules
- Edge Cases — null handling, empty inputs, boundary conditions

// turbo
### 5. Validate

Run the generated tests and confirm they pass.

## Best Practices

- Mock external dependencies (database, HTTP clients, file system)
- Use descriptive test names that explain the scenario
- Test one behavior per test case
- Keep tests independent and isolated
- Use factories for creating test data
- Clean up state between tests
- Test both success and failure paths
