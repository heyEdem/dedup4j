# PR Writer Skill Design

**Date:** 2026-08-18
**Skill:** `pr-writer`

## Goal

Create a globally reusable skill for Claude and Codex that writes detailed, reviewer-oriented pull request messages in the style demonstrated by `/Users/Edem/Documents/pr-kado-65.md`.

## Installations

Install identical native copies in:

- `~/.codex/skills/pr-writer/`
- `~/.claude/skills/pr-writer/`

The Codex copy includes generated `agents/openai.yaml` metadata. Claude may ignore that metadata, but keeping both directories identical makes maintenance and parity verification straightforward.

## Skill Contract

Trigger `pr-writer` whenever an agent drafts, rewrites, updates, or creates a pull request title or body, including immediately before `gh pr create` or `gh pr edit`.

The skill must gather facts from the actual commit range, diff, tests, branch, and ticket context. It must not invent endpoints, test results, issue IDs, or implementation details.

The output follows this adaptable structure:

1. Conventional title with optional issue ID.
2. Tight overview describing outcome, scope, and meaningful non-goals.
3. Grouped `What Changed` subsections using tables where they improve scanning.
4. Implementation notes for non-obvious choices, boundaries, and trade-offs.
5. Test coverage naming concrete tests and assertions.
6. Numbered `How to Test` steps with commands and expected results.
7. Related metadata for issue, branch, and base when known.

Sections may be omitted only when their subject genuinely does not apply. The writing should retain the depth and reviewer utility of the KADO-65 example without copying its domain-specific content.

## Resources

- Keep the core workflow concise in `SKILL.md`.
- Store the generalized output template and quality checklist in `references/pr-template.md`.
- Do not add scripts because PR facts and repository tooling vary by project.

## Validation

1. Run multiple no-skill baseline prompts and record common shape failures.
2. Initialize the Codex skill with the official skill-creator tooling.
3. Validate the skill with `quick_validate.py`.
4. Copy it to Claude and verify directory parity.
5. Run fresh forward tests with the skill and compare them with the baseline.
6. Use the skill to refresh the open dedup4j PR as a live integration test.

## Repository Enforcement

Update this repository's `AGENTS.md` and `CLAUDE.md` to require `$pr-writer` whenever either agent writes or creates a pull request.
