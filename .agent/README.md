# Universal AI Development Template

A **tool-agnostic AI development template** that works across Claude Code, Antigravity, Cursor, Aider, and future AI IDEs.

## Architecture

```
.ai/                     ← Canonical source of truth (tool-agnostic)
├── AI_MANIFEST.yaml     ← Template metadata
├── context/             ← Project-level AI instructions
├── agents/              ← AI persona definitions
├── workflows/           ← Step-by-step task automation (16 workflows)
│   ├── build-large-system.md          ← 🏗️ 30-phase lifecycle (master)
│   ├── build-large-system-phases-*.md ← Detailed phase descriptions
│   └── ...                            ← 12 more workflow files
├── skills/              ← Atomic, reusable procedures
├── prompts/             ← Reusable prompt fragments
├── scripts/             ← Utility automation scripts
└── docs/                ← Documentation
    └── BUILD_LARGE_SYSTEM_GUIDE.md    ← 📖 How to use the lifecycle
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

## 🏗️ Build Large System Workflow

The flagship workflow: **30 phases × 8 stages** for building large-scale distributed systems from scratch.

| Stage | Phases | Focus |
|-------|--------|-------|
| A. Discovery & Requirements | 01–03 | Problem, users, risks |
| B. Domain & Architecture | 04–11 | Domain, security, APIs, events, flows |
| C. Platform & Infrastructure | 12–16 | IaC, core libs, testing, CI/CD |
| D. Service Development | 17–19 | Vertical slice → full build → migration |
| E. Hardening | 20–25 | Observability, perf, compliance, chaos, DR, readiness |
| F. Launch | 26–27 | Deploy + 2-week stabilization |
| G. Operations | 28–29 | On-call, incidents, SLO review |
| H. Evolution | 30 | Tech debt, FinOps, v2 planning |

📖 **[How to use this workflow →](docs/BUILD_LARGE_SYSTEM_GUIDE.md)**

**Workflow files**: [Master](workflows/build-large-system.md) · [Phases 01–10](workflows/build-large-system-phases-01-10.md) · [Phases 11–20](workflows/build-large-system-phases-11-20.md) · [Phases 21–30](workflows/build-large-system-phases-21-30.md)

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

## Documentation

| Doc | Purpose |
|-----|---------|
| [Build Large System Guide](docs/BUILD_LARGE_SYSTEM_GUIDE.md) | How to use the 30-phase lifecycle |
| [Architecture](docs/ARCHITECTURE.md) | Template architecture details |
| [Onboarding](docs/ONBOARDING.md) | New contributor onboarding |
| [Migration Guide](docs/MIGRATION_GUIDE.md) | Migrate from Claude Code |
| [Adapter Guide](docs/ADAPTER_GUIDE.md) | How to write adapters |

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for guidelines on adding agents, workflows, skills, and adapters.

