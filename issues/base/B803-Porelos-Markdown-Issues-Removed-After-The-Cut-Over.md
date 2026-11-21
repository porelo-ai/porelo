# B803 — Porelo's Markdown Issues Removed After The Cut-Over

```json
{ "$schema": "issue-1.json", "id": "B803", "title": "Porelo's Markdown Issues Removed After The Cut-Over", "state": "Implementing",
  "size": "S", "priority": "High", "rank": 2, "blockedBy": [],
  "covers": "Once the project's issues live in GitHub Issues: `issues/`, its index, its check in the Shared rules workflow, its build exclusion and every text that points at it removed; the acceptance story that reads the index reads the tracker" }
```

**Why this size.** Files, one workflow step, a few texts and one acceptance script in this repository; no product code
and no contract another repository reads.

**What must be true.** On the day the project moves its issues to GitHub Issues, this repository holds no second copy
of them: no `issues/`, no index, no check that still demands one, and nothing that tells an agent or a person to look
there. Until that day nothing here changes.

## Plan

Searched, so the rest of the product is known not to depend on the files: every `*.java`, `*.sh`, `*.yml`,
`*.yaml`, `*.xml`, `*.json` and `*.py` outside `issues/` for `issues/` and `stories/`; the search finds the guide
text and the acceptance story below, so it does see a known hit. Porelo itself reads and writes no issue file: a
task's work (`--work <issue>:<phase>`) is an opaque name, the issue reaches a task only as a given file, and a
finding leaves it only as its result file or a proposed idea, which the person's planner takes.

- **Delete** `issues/` whole (`base/`, `providers/`, `README.md`, `workflow.yml`).
- **`.github/workflows/shared-rules.yml`**: drop the step "The issue heads".
- **`.github/workflows/build.yml`**: drop `issues/**` from `paths-ignore`.
- **`TaskGuideText`**: a reading task is told never to edit "any file of the repositories", without naming `issues/`;
  `TaskGuideTest` follows.
- **`acceptance/stories/st56.sh`**: it fetches each repository's `issues/README.md` to tell which issues are not yet
  in Review; it takes them from `--expect-fail` alone and leaves where each issue stands to the tracker.
- **Texts**: `AGENTS.md` (where issues are), `README.md`, `CONTRIBUTING.md`, `doc/decisions.md` where it states a
  rule about issue files, and the `core-*` skills; shared texts (the shared block, `porelo-issues`) change only
  through the channel.
- **The test that goes red first**: `TaskGuideTest` on the new wording; then the whole build and the Shared rules
  workflow green without `issues/`.

## Acceptance

- `git ls-files issues` is empty; the Shared rules workflow and the build pass on the commit.
- A search for `issues/` outside `CHANGELOG.md` and `changelog/` finds only what speaks of GitHub Issues.
- `st56.sh` runs without fetching an index file.

## To be checked

- On which day the cut-over happens and that every open issue here was carried over first; it is built on that
  day only.
