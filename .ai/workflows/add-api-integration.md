---
description: Add integration with an external API or service
agent: feature-builder
---

# Add API Integration

Add integration with an external API or third-party service.

## Steps

### 1. Gather Requirements

- What API/service to integrate?
- Which endpoints/operations are needed?
- Authentication method (API key, OAuth, token)?
- Rate limiting considerations?

### 2. Analyze Existing Integrations

- Check for existing HTTP client setup
- Find similar integrations for pattern consistency
- Review error handling patterns for external calls

### 3. Implement Integration

- Create service/client for the external API
- Implement proper error handling and retries
- Add request/response DTOs
- Handle authentication securely
- Add timeout configuration

### 4. Add Tests

- Unit tests with mocked HTTP responses
- Test error scenarios (timeouts, 4xx, 5xx)
- Test retry logic if applicable

// turbo
### 5. Validate

Run tests and verify integration works correctly.

## Best Practices

- Never hardcode API keys or secrets
- Use environment variables for configuration
- Implement circuit breaker pattern for critical APIs
- Add structured logging for API calls
- Handle rate limiting gracefully
- Document the integration in project docs
