# Architecture Overview

## Core Concept

This template separates **AI logic** from **AI tooling** using an adapter pattern:

```
.ai/ (canonical, tool-agnostic)  →  adapters/  →  tool-native output
         source of truth              thin         (.claude/, .agent/,
                                   translators     .cursorrules, etc.)
```

## Directory Map

| Directory | Purpose |
|-----------|---------|
| `.ai/context/` | Project-level instructions for AI (what to know) |
| `.ai/agents/` | AI persona definitions (who to be) |
| `.ai/workflows/` | Step-by-step task automation (what to do) |
| `.ai/skills/` | Atomic, reusable procedures (how to do it) |
| `.ai/prompts/` | Reusable prompt fragments and templates |
| `.ai/scripts/` | Automation scripts for install, validation, migration |
| `.ai/docs/` | Human documentation |
| `adapters/` | Tool-specific translation layers |

## Operating Model

Every non-trivial task follows:

```
Plan → Review (HARD STOP) → Execute → Verify
```

The AI assistant creates a scratchpad, stops for human approval, then implements and verifies.

## Module Relationships

```
┌──────────────────────────────────────────┐
│              .ai/ (canonical)            │
├──────────────────────────────────────────┤
│ context/   → Loaded first, every task    │
│ agents/    → Define behavior per task    │
│ workflows/ → Step-by-step automation     │
│ skills/    → Referenced by agents        │
│ prompts/   → Templates for output        │
└───────────────┬──────────────────────────┘
                │
      ┌─────────┴─────────┐
      │  adapters/ (thin)   │
      ├─────────────────────┤
      │ claude/  → .claude/ │
      │ antigravity → .agent│
      │ cursor/  → .cursor  │
      │ aider/   → .aider*  │
      └─────────────────────┘
```

## Key Design Decisions

1. **`.ai/` is never modified by adapters** — adapters only read from it
2. **Skills use generic verbs** — "Search", "Read", "Edit" — not tool-specific names
3. **Workflows are adapter-compatible by default** — YAML frontmatter works in both Claude Code and Antigravity
4. **Context files split responsibilities** — PROJECT (what), CONVENTIONS (how), BOUNDARIES (don't)
