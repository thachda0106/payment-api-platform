---
description: Trace and explain the execution flow of a specific code path
---

# Explain Logic

Trace and explain how a specific code path works, from entry point to final output.

## Steps

### 1. Identify Entry Point

Determine the starting point:
- API endpoint / route handler
- Function / method call
- Event handler / message consumer
- CLI command

### 2. Trace Execution Flow

Follow the call chain from entry point:
1. Read the entry function
2. Identify functions called within it
3. Navigate to those function definitions
4. Track data transformation at each step
5. Identify branching logic (if/else, switch)
6. Map external interactions (DB, APIs, file system)

### 3. Document the Flow

Create a clear, ordered description:
- Sequence of operations (A → B → C)
- Data mutations along the path
- Key decision points
- Error handling paths

## Output Format

### Execution Flow
```
Entry Point → Step 1 → Step 2 → ... → Output
```

### Detailed Explanation
For each step:
- **What**: What happens at this step
- **Where**: File and function name
- **Data**: What data flows in and out
- **Decisions**: Any branching logic
