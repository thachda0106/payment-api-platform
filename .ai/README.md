# Universal AI Development Template

A **tool-agnostic AI development template** that works across Claude Code, Antigravity, Cursor, Aider, and future AI IDEs.

## Architecture

```
.ai/                     ← Canonical source of truth (tool-agnostic)
├── AI_MANIFEST.yaml     ← Template metadata
├── context/             ← Project-level AI instructions
├── agents/              ← AI persona definitions
├── workflows/           ← Step-by-step task automation
├── skills/              ← Atomic, reusable procedures
├── prompts/             ← Reusable prompt fragments
├── scripts/             ← Utility automation scripts
└── docs/                ← Documentation

adapters/                ← Tool-specific translation layers
├── claude/              ← Claude Code adapter
├── antigravity/         ← Antigravity adapter
├── cursor/              ← Cursor adapter
└── aider/               ← Aider adapter
```

## Quick Start

### 1. Fill in context files

Edit these files with your project's details:

| File | Purpose |
|------|---------|
| `.ai/context/PROJECT.md` | Project name, tech stack, commands, architecture |
| `.ai/context/CONVENTIONS.md` | Coding standards, naming conventions |
| `.ai/context/BOUNDARIES.md` | What the AI should never do |

### 2. Install for your AI tool

```bash
bash .ai/scripts/install-ai-template.sh
```

This auto-detects installed AI tools and generates the correct configuration.

### 3. Install a specific adapter manually

```bash
bash adapters/antigravity/install.sh   # For Antigravity
bash adapters/claude/install.sh        # For Claude Code
bash adapters/cursor/install.sh        # For Cursor
bash adapters/aider/install.sh         # For Aider
```

### 4. Validate template integrity

```bash
bash .ai/scripts/validate-template.sh
```

## Operating Model

Every non-trivial task follows 5 phases:

```
PLAN → REVIEW (hard stop) → EXECUTE → VERIFY → REFLECT
```

In workflows, these expand into 6 detailed steps: **Scratchpad → Plan → Tasks → Execute → Verify → Reflect**, with approval gates between each phase.

## Design Principles

| Principle | Explanation |
|-----------|-------------|
| **Tool-agnostic core** | All agents, workflows, skills live in `.ai/` using generic language |
| **Adapter pattern** | Each AI tool gets a thin adapter translating `.ai/` into tool-native format |
| **Convention over configuration** | Standard filenames and YAML frontmatter |
| **Project-parameterized** | Templates use placeholders filled at install time |
| **Composable** | Each module (agent, skill, workflow) is independent and opt-in |

## Migrating from Claude Code

```bash
bash .ai/scripts/migrate-from-claude.sh
```

See [Migration Guide](docs/MIGRATION_GUIDE.md) for details.

## Adding a New Adapter

See [Adapter Guide](docs/ADAPTER_GUIDE.md) for the adapter contract specification.

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for guidelines on adding agents, workflows, skills, and adapters.

