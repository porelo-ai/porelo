# B766 — How The Git Mirror Is Kept

```json
{
  "$schema": "issue-1.json",
  "id": "B766",
  "title": "How The Git Mirror Is Kept",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "`doc/how-it-works.md` says the Git mirror is a bare repository, how it is made, where it lies, where a task's push lands, and that work is done only in clones of it; its glossary names the Git mirror and a bare repository",
  "openQuestions": 0
}
```

**Why this size.** XS: one paragraph and two glossary rows on one page, each fact read in the code. Estimate: 9 minutes
(Core's median for XS).

**What must be true.** A reader of "The gate: work leaves only after review" learns what the Git mirror is on disk and
why nothing in it is edited or run.

Filed 2026-11-14 on the operator's order.

## Plan

- After the sentence that names the Git mirror, one paragraph. Each fact is read in the code:
  - The mirror is a bare repository: git's database alone (objects, refs, config, hooks), with no working tree. That is
    the form a forge keeps a repository in. Nothing in it is edited, built or run.
  - `GitGate.initialize` makes it with `git clone --bare --no-hardlinks` through the machine's key lending, or with
    `git init --bare` when there is no upstream. It sets `http.receivepack` so that it takes pushes over HTTP.
  - `GateSupport.mirror` puts one per project and repository under Porelo's data directory, `$XDG_DATA_HOME/porelo`:
    `mirrors/<project>/<repository>.git`, or `mirrors/<project>.git` when the repository is named as the project.
  - A task's push lands as `refs/porelo/incoming/<task>` (`GitGate.INCOMING`).
  - Work is done only in clones: `/workspace` in the container (`TaskWorkspace`), and `porelo gate checkout`
    (`GitGate.checkout`). The checkout is a new repository whose only remote is the mirror, with hooks off. It is not
    a worktree, so it shares no config or hooks with the mirror.
- The glossary's row "Mirror" becomes "Git mirror", and a row "Bare repository" is added.
- The tests tagged `documents` stay green.

## Built

- One paragraph after the gate's first one in `doc/how-it-works.md`. It says what the plan lists, with the data
  directory written `$XDG_DATA_HOME/porelo`, as `doc/credentials.md` and `doc/commands.md` write it.
- The glossary has "Bare repository", and "Git mirror" in place of "Mirror", each in alphabetical order.
- The tests tagged `documents` are green.
