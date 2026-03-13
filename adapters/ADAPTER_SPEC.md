# Adapter Contract Specification

This document defines the contract that every AI tool adapter must satisfy.

## What Is an Adapter?

An adapter is a thin translation layer that reads the canonical `.ai/` directory and generates tool-specific configuration files. The `.ai/` directory is the **single source of truth**; adapters produce derived output.

## Required Files

Every adapter directory must contain:

| File | Purpose |
|------|---------|
| `install.sh` | Reads `.ai/` and generates tool-native directory structure |
| `mapping.yaml` | Declares path and format mappings from `.ai/` → tool-native |
| `templates/` | Template files for generating tool-specific output (optional) |

## `mapping.yaml` Schema

```yaml
adapter: "<tool-name>"        # e.g., claude, antigravity, cursor, aider
version: "<semver>"
target_dirs:                   # Directories the adapter creates
  - "<path>"

mappings:
  <category>:                  # e.g., context, agents, workflows, skills, scripts
    "<source-glob>":
      target: "<target-path>"  # null if tool doesn't support this file type
      transform: "<transform>" # copy, merge, add_frontmatter, etc.
```

## `install.sh` Contract

The install script must:

1. Read `.ai/` directory as input (never modify it)
2. Create tool-native directories
3. Copy/transform files per `mapping.yaml`
4. Be idempotent (safe to run multiple times)
5. Print what it creates
6. Exit 0 on success, non-zero on failure

## Supported Transforms

| Transform | Description |
|-----------|-------------|
| `copy` | Copy file as-is |
| `merge_context_files` | Merge multiple context files into one |
| `append` | Append to existing target file |
| `add_frontmatter` | Add/modify YAML frontmatter |
| `claude_agent_format` | Convert to Claude Code agent format |
| `add_agent_directive` | Add `@agent` directive (Claude Code commands) |
| `cursorrules_format` | Convert to .cursorrules format |

## Adding a New Adapter

1. Create a directory under `adapters/<tool-name>/`
2. Create `mapping.yaml` with path mappings
3. Create `install.sh` that implements the mappings
4. Add templates for tool-specific file formats (optional)
5. Test with `validate-template.sh`
