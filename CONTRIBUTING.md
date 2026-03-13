# Contributing to Universal AI Dev Template

Thank you for contributing! This document explains how to add new components to the template.

## Getting Started

1. Clone the repository
2. Review the [Architecture](.ai/docs/ARCHITECTURE.md) to understand the structure
3. Run validation: `bash .ai/scripts/validate-template.sh`

## Adding Components

### Adding an Agent

1. Create `.ai/agents/<name>.agent.md` with YAML frontmatter:
   ```yaml
   ---
   name: <name>
   description: What this agent does
   skills:
     - skill-1
     - skill-2
   boundaries:
     - Rule 1
     - Rule 2
   ---
   ```
2. Add the agent name to `AI_MANIFEST.yaml` under `modules.agents`
3. Run `bash .ai/scripts/validate-template.sh` to verify

### Adding a Workflow

1. Create `.ai/workflows/<verb>-<noun>.md` with YAML frontmatter:
   ```yaml
   ---
   description: What this workflow does
   agent: <agent-name>
   ---
   ```
2. Add numbered steps with clear action verbs
3. Use `// turbo` annotation above steps that are safe to auto-run
4. Add the workflow name to `AI_MANIFEST.yaml` under `modules.workflows`
5. Run validation

### Adding a Skill

1. Create `.ai/skills/<verb>-<noun>/SKILL.md` with YAML frontmatter:
   ```yaml
   ---
   name: <skill-name>
   description: What this skill does
   category: core | debug | quality
   inputs:
     - input_1
   outputs:
     - output_1
   ---
   ```
2. Include sections: When to Use, Step-by-Step Procedure, Decision Rules, Non-Goals
3. Optionally add `references/`, `scripts/`, or `assets/` subdirectories
4. Add the skill name to `AI_MANIFEST.yaml` under `modules.skills`
5. Run validation

### Adding an Adapter

1. Create `adapters/<tool-name>/` with:
   - `mapping.yaml` — path and format mappings
   - `install.sh` — installation script (source `_lib.sh`)
   - `clean.sh` — cleanup script
2. Follow the [Adapter Spec](adapters/ADAPTER_SPEC.md)
3. Source shared library: `source "$PROJECT_ROOT/.ai/scripts/_lib.sh"`
4. Use `merge_context_files()` for context merging (don't duplicate logic)
5. Run `bash .ai/scripts/validate-template.sh`

## Conventions

| Component | Naming Pattern | Example |
|-----------|---------------|---------|
| Agent files | `{name}.agent.md` | `bug-hunter.agent.md` |
| Workflow files | `{verb}-{noun}.md` | `create-feature.md` |
| Skill directories | `{verb}-{noun-phrase}/` | `diagnose-bug-root-cause/` |
| Context files | `UPPERCASE.md` | `PROJECT.md` |
| Scripts | `{verb}-{noun}.sh` | `install-ai-template.sh` |

## Validation

Always run the validation script before submitting changes:

```bash
bash .ai/scripts/validate-template.sh
```

This checks:
- Directory structure completeness
- YAML frontmatter validity
- Cross-references (agent→skills, workflow→agents)
- Tool-specific reference leaks
- Unresolved placeholders
- Adapter structure

## Code Style for Scripts

- Use `set -euo pipefail` in all Bash scripts
- Source `_lib.sh` for shared utilities (colors, merge functions)
- Always check for `.ai/` existence before proceeding
- Print what you create/modify
- Exit 0 on success, non-zero on failure
- Be idempotent (safe to run multiple times)
