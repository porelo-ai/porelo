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
  "covers": "`doc/how-it-works.md` says the Git mirror is a bare repository, how it is made, where it lies, where a task's push lands, and that work is done only in clones of it, with an example of default run online against a repository on this machine; its glossary names the Git mirror and a bare repository; no page says default's settings cannot be changed",
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

## Built, second part (the operator's order of 2026-11-14, 11:11Z)

- `default`'s own settings, read in `RepositorySettings.read` and `theProjects`: a repository's `porelo.yml` and the
  checkout's `.porelo.yml` may hold the project file's form, less `project.name`, `repositories`, `mcp` and
  `readings`; what they change is allowed once per machine. Corrected where pages said otherwise:
  - `doc/how-it-works.md`: "Its settings cannot be changed" now says what a repository in `default` may change and
    links the project file. The glossary row no longer says its settings cannot be changed. The opening paragraph
    no longer says a machine follows a project against a key you gave; a project is read at each start, signed
    by one of its admins.
  - `doc/commands.md`: the same claim in the `default` paragraph.
  - `doc/decisions.md`: the superseded note of "A repository's image comes from porelo.yml" now says the files hold
    more in `default` and that "image only" still holds for a followed project's repository file
    (`RepositoryFile`).
  - `doc/project-file.md`: `mcp` and `readings` are named beside `project.name` and `repositories`.
- An example in the gate section: `default` run `online` against a bare repository on this machine. The checkout is
  cloned from it, and `.porelo.yml` sets `security_class: "online"` and `central` to the same `file://` address,
  since an online project needs a `central` (`Project`, "is online, so it needs an upstream"). The agent's branch
  `porelo/<task>` arrives there and is fetched and merged by the person. The gate may push that branch with force
  (`GitGate`, `--force` and `--force-with-lease`). A `file://` central is what the acceptance suite's online
  projects use (`TerminalSteps.anOnlineProjectSaying`). A non-bare checkout as `central` is not said: no code or
  test shows it.
- The gate section's reader is started with `porelo task start --reads`, not `task run`: the command is `start`
  (`TaskRunCommand`), as [B767](README.md#b767)'s reading found.
- The tests tagged `documents` are green.
