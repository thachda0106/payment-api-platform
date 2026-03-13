---
description: Create a new feature module following project architecture and conventions
agent: feature-builder
---

# Create Feature

Create a new module/component following the project's architecture and conventions.

## Steps

### 1. Gather Requirements

Ask user for:
- Module/component name
- Entity/model fields and types
- API endpoints or interface (if applicable)
- Relations with other modules (if any)

### 2. Analyze Architecture

Use the `analyze-project-structure` skill to understand:
- Current module layout and patterns
- Naming conventions in use
- Existing similar modules to follow as reference

### 3. Generate Module Structure

Create the full module following project conventions:
- Model/entity definition
- Data access layer (repository/DAO)
- Business logic layer (service)
- API layer (controller/handler)
- Data transfer objects (DTOs/schemas)
- Tests

### 4. Register Module

Register the new module in the project's root configuration (e.g., AppModule, router config, etc.).

### 5. Create Database Migration

If the project uses database migrations, generate the appropriate migration file.

// turbo
### 6. Validate

Run project lint, type check, and tests to ensure the generated code is valid.

## Implementation Checklist

- [ ] Create model/entity with proper annotations
- [ ] Define DTOs with validation
- [ ] Implement repository/data access layer
- [ ] Create service with business logic
- [ ] Build controller/handler with endpoints
- [ ] Configure module with dependencies
- [ ] Register module in root configuration
- [ ] Create database migration
- [ ] Write unit tests for service
- [ ] Write unit tests for controller
- [ ] Run full test suite

## Best Practices

1. Follow existing naming conventions exactly
2. Use project path aliases for imports
3. Use validation decorators/schemas in DTOs
4. Use framework-standard exception/error types
5. Test behavior, mock dependencies
6. Use feature/use-case classes for complex operations
