# Universal AI Development Template

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A **tool-agnostic AI development template** that works across Claude Code, Antigravity, Cursor, Aider, and future AI IDEs.

Define your AI agents, workflows, skills, and conventions **once** in a canonical `.ai/` directory. Adapters translate them into tool-native formats automatically.

## Architecture

```
.ai/                     ← Canonical source of truth (tool-agnostic)
├── AI_MANIFEST.yaml     ← Template metadata and module registry
├── context/             ← Project-level AI instructions
│   ├── PROJECT.md       ← What: tech stack, commands, architecture
│   ├── CONVENTIONS.md   ← How: coding standards, naming rules
│   └── BOUNDARIES.md    ← Don't: forbidden actions, safety constraints
├── agents/              ← AI persona definitions (6 canonical agents)
├── workflows/           ← Step-by-step task automation (12 workflows)
├── skills/              ← Atomic, reusable procedures (7 skills)
├── prompts/             ← Reusable prompt fragments and templates
├── scripts/             ← Automation (install, validate, migrate)
└── docs/                ← Documentation

adapters/                ← Tool-specific translation layers
├── claude/              ← Claude Code → .claude/ + CLAUDE.md
├── antigravity/         ← Antigravity → .agent/ + .gemini/
├── cursor/              ← Cursor → .cursorrules
└── aider/               ← Aider → .aider.conf.yml
```

## Quick Start

### 1. Copy the template

Copy `.ai/` and `adapters/` into your project root.

### 2. Fill in context files

Edit these files with your project's details:

| File | Purpose |
|------|---------|
| `.ai/context/PROJECT.md` | Project name, tech stack, commands, architecture |
| `.ai/context/CONVENTIONS.md` | Coding standards, naming conventions |
| `.ai/context/BOUNDARIES.md` | What the AI should never do |

### 3. Install for your AI tool

```bash
# Auto-detect installed tools
bash .ai/scripts/install-ai-template.sh

# Or install a specific adapter
bash adapters/antigravity/install.sh
bash adapters/claude/install.sh
bash adapters/cursor/install.sh
bash adapters/aider/install.sh
```

### 4. Validate

```bash
bash .ai/scripts/validate-template.sh
```

## Design Principles

| Principle | Explanation |
|-----------|-------------|
| **Tool-agnostic core** | All agents, workflows, skills live in `.ai/` using generic language |
| **Adapter pattern** | Each AI tool gets a thin adapter translating `.ai/` into tool-native format |
| **Convention over configuration** | Standard filenames and YAML frontmatter |
| **Composable** | Each module (agent, skill, workflow) is independent and opt-in |
| **PREV operating model** | Plan → Review → Execute → Verify → Reflect |

## Operating Model

Every non-trivial task follows:

```
PLAN → REVIEW (hard stop) → EXECUTE → VERIFY → REFLECT
```

The AI creates a scratchpad, stops for human approval, implements the plan, verifies results, and reflects on what was learned.

## Adapters

| Feature | Claude Code | Antigravity | Cursor | Aider |
|---------|:-----------:|:-----------:|:------:|:-----:|
| Context files | ✅ CLAUDE.md | ✅ STYLE.md | ✅ .cursorrules | ✅ CONVENTIONS.md |
| Agent definitions | ✅ | ❌ (in docs) | ❌ (embedded) | ❌ |
| Workflows | ✅ commands/ | ✅ workflows/ | ❌ | ❌ |
| Skills | ✅ | ✅ | ❌ | ❌ |
| Scripts | ✅ | ✅ | ❌ | ❌ |
| Clean/uninstall | ✅ | ✅ | ✅ | ✅ |

## Migrating from Claude Code

```bash
bash .ai/scripts/migrate-from-claude.sh
```

See [Migration Guide](.ai/docs/MIGRATION_GUIDE.md) for details.

## Adding a New Adapter

See [Adapter Guide](.ai/docs/ADAPTER_GUIDE.md) for the adapter contract specification.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on adding agents, workflows, skills, and adapters.

## License

This project is licensed under the MIT License — see [LICENSE](LICENSE) for details.
