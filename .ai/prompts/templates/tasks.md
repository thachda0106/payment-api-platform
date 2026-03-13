# Tasks Template

> Use this template during Phase 3 (Task Breakdown) of any workflow.
> Fill in based on the approved plan.

## Task List

_Ordered implementation steps. Each task should be small and independently verifiable._

### Task 1: [Title]

- **Description**: _What to do_
- **Files**:
  - `path/to/file.ext` — Create / Modify / Delete
- **Dependencies**: None / Task N
- **Expected Output**: _What should exist after this task_

---

### Task 2: [Title]

- **Description**: _What to do_
- **Files**:
  - `path/to/file.ext` — Create / Modify / Delete
- **Dependencies**: Task 1
- **Expected Output**: _What should exist after this task_

---

### Task N: [Tests / Validation]

- **Description**: _Write tests and validate changes_
- **Files**:
  - `path/to/file.spec.ext` — Create
- **Dependencies**: All previous tasks
- **Expected Output**: _All tests pass, lint clean_

---

## Dependency Graph

```
Task 1 → Task 2 → ... → Task N (Tests)
```

## Summary

| Total Tasks | Files Created | Files Modified | Files Deleted |
|------------|--------------|---------------|--------------|
| | | | |

---

**Status**: ⏳ Awaiting human review

> [!IMPORTANT]
> These tasks must be approved by a human before implementation begins.
> Do NOT write any code until this task list is approved.
