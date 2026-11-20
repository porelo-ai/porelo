# B808 — What Core Keeps Of The Markdown Issue Store Removed

```json
{ "$schema": "issue-1.json", "id": "B808", "title": "What Core Keeps Of The Markdown Issue Store Removed",
  "state": "Analyzed", "size": "S", "priority": "High", "rank": 7, "blockedBy": [],
  "covers": "After the cut-over's copy, as its own step: porelo's `stats/`, porelo-parent's `issues/` and its daily issue check, and the project file page's Markdown store go; the shared skills' issue file rules change only as agreed in the room" }
```

**Why this size.** Files, one workflow step in another repository of this agent's, one page and a few texts; no
product code and no contract another repository reads. Porelo's own `issues/` is not part of it; that removal is
[B803](README.md#b803).

**What must be true.** Porelo keeps stories and issues only in a tracker. Once the cut-over has copied the planner's
Markdown store into GitHub Issues, nothing this agent owns creates, checks or names `issues/` or `stats/` in a
repository: no folder, no template, no workflow step, no page that says issues may be Markdown files.

## Plan

Built only after the copy, when the operator says when; the carry-over reads the Markdown store once before.

- **porelo**: delete `stats/` whole (the planner's stage records). `doc/project-file.md`, the `tracker` key: drop
  "Absent, the issues are Markdown files in the repositories"; absent `tracker` now means no planner keeps this
  project's issues. Any other text that says issues or stage records live in Git (`doc/running.md`, `doc/decisions.md`,
  `CONTRIBUTING.md`), read in the tree at that time.
- **porelo-parent**: delete `issues/` whole (`README.md`, `workflow.yml`, the template other repositories copied
  theirs from); drop the step "Every issue head holds the issue format" and the comment naming it from
  `.github/workflows/workflows.yml`, and from its README anything that names them. `pom.xml`'s `issueManagement`
  (GitHub Issues) stays. This goes in its own Push ready.
- **The shared skills, proposed in the room, changed only after agreement**: `porelo-issues` (the file rules: the
  skeleton as a file, `issues/README.md` as the index, the anchor rows, links to a file's row, "a repository without
  `issues/` gets one with the shared `workflow.yml` copy"), the shared area's line "Every open task is an issue in
  `issues/`", and `porelo-code`'s line on the planner's executable checking the repository's issues and stories.
  The texts that replace them come from the planner's tracker rules.
- **Not this agent's, named so nothing falls between**: porelo-release's `issues` and `stories` commands, the Issues
  sync and the stories check workflow belong to their owners.
- **No test goes red first**: no product code changes. The proof is the Shared rules and Workflow check runs green in
  both repositories without the removed steps, `check-shared` and `check-doc-site` green, and a search of both trees
  for `issues/` and `stats/` that finds only what is meant to stay, shown to find a known hit first.

## Acceptance

- Neither repository holds `issues/` (porelo's by [B803](README.md#b803)) or `stats/`, and no workflow step reads them.
- `doc/project-file.md` says nothing of a Markdown issue store.
- The shared skills say what was agreed in the room, in every copy at once.
