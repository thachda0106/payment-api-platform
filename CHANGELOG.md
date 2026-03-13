# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-03-13

### Added
- Root `README.md` for GitHub/GitLab landing page
- `.gitignore` for generated adapter output
- `LICENSE` (MIT)
- `CHANGELOG.md` (this file)
- `CONTRIBUTING.md` contributor guide
- Shared shell library `.ai/scripts/_lib.sh` — eliminates duplication across adapters
- `clean.sh` for every adapter — removes generated output
- REFLECT phase added to operating model (Plan → Review → Execute → Verify → Reflect)
- Reflection output template (`prompts/templates/reflection.md`)
- Cross-reference validation in `validate-template.sh` (agent→skills, workflow→agents)
- Placeholder detection in `validate-template.sh`
- `--dry-run` flag for `install-ai-template.sh`
- Troubleshooting and quick reference sections in `ONBOARDING.md`

### Changed
- All 4 adapter `install.sh` scripts refactored to use shared `_lib.sh`
- `install-ai-template.sh` now writes installed adapters to `AI_MANIFEST.yaml`
- `refactor-feature.md` agent changed from `code-reviewer` to `feature-builder`
- Operating model updated from 4-phase to 5-phase (added REFLECT)
- `ARCHITECTURE.md` updated to reflect shared lib and clean system

### Fixed
- `AI_MANIFEST.yaml` operating model string inconsistency
- Tool-specific "Context7 MCP" reference removed from `get-docs.md`

## [1.0.0] - 2026-03-13

### Added
- Initial canonical `.ai/` directory structure
- 6 AI agents: bug-hunter, code-reviewer, doc-keeper, feature-builder, performance-optimizer, test-engineer
- 12 workflows: ai-workflow, create-feature, fix-bug, review-pr, add-tests, get-docs, review-architecture, refactor-feature, add-api-integration, explain-logic, optimize-performance, update-docs
- 7 skills: analyze-project-structure, apply-targeted-fix, diagnose-bug-root-cause, locate-code-patterns, trace-execution-flow, validate-architecture, verify-bug-regression
- 4 adapters: Claude Code, Antigravity, Cursor, Aider
- 3 automation scripts: install, migrate, validate
- System prompt and templates (scratchpad, review output)
- Documentation: architecture, onboarding, adapter guide, migration guide
