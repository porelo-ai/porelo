# B721 — The First Start Asks Once

```json
{
  "$schema": "issue-1.json",
  "id": "B721",
  "title": "The First Start Asks Once",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": [
    "B718",
    "B719"
  ],
  "covers": "`porelo task start` in `default` on a repository whose proposal is UNASKED asks once at a terminal whether to propose its tools, never answered by `--yes`; without a terminal it starts as today and names `porelo project init` in one line.",
  "openQuestions": 0
}
```

**Why this size.** One question in the task start on the recorded state, and its tests.

Split from PJ52 on 2026-11-13. [B221](README.md#b221) left out step 8: the question at `task start`. Any part of [B222](README.md#b222) not covered by [B467](README.md#b467) belongs here.

## Plan

- `TaskLaunch` reads the repository's proposal state through [B718](README.md#b718).
- UNASKED at a terminal: one question; the answer recorded as ASKED either way; yes writes the draft through [B719](README.md#b719).
- `--yes` never answers this question; without a terminal one line names `porelo project init` and the start goes on.

## Acceptance

- Red first: an UNASKED start at a terminal asks once, the second start does not.
- A start with `--yes` and no terminal does not ask and names `porelo project init`.

## Built

- `TaskRunCommand.proposalOffered`: runs for a start in project `default` with a known repository, and reads that
  repository's proposal state the way the daemon answers it (`DefaultProject.proposal`). A task brought back keeps
  its image and is never asked.
- **UNASKED at a terminal:** the start asks one question, defaulting to no: "The tools of X are not proposed yet.
  Propose them from its build files? …". A yes takes the proposal as tools (`DefaultProposal.take(…, TOOLS)`). It
  writes the `.porelo.yml` draft beside the checkout and kept out of git, or the remote one for a repository added by
  address. The start then reads the draft, and asks for its lines before building them as for any `.porelo.yml`. A
  no records NONE, and no start asks again. Either way, one line follows: what was written, that the adapters found
  nothing, or that nothing was proposed. If the proposal cannot be taken, the start says why in one line, records
  nothing, and goes on.
- **No terminal, or `--yes`, `--no-input` or `--dry-run`:** no question, only one line naming
  `porelo project init <checkout>` and that a start at a terminal asks. ASKED, CONFIGURED and other projects get
  nothing.
- **One question per start about the build files.** When this start's first look (`FirstStartAtTerminal`) has already
  spoken of the adapters, the proposal question is left out. That covers asking its own question, saying none are
  installed, or saying nothing was recognized. It is checked through the new `spokeOfAdapters()`.
- The daemon's `Start` and a task's MCP Start never ask.
- Docs: `doc/commands.md` (`task start`, and `TakeProposal`), `doc/project-file.md`.
- Tests in `TaskRunCommandTest`:
  - asked once, and the next start does not ask
  - a yes writes the draft, whose line the same start reads, with nothing built
  - `--yes` with and without a terminal asks nothing and names `project init`
  - a no to the first-start question is not followed by the proposal question

  They failed on the build box before the change (`buildbox-porelo-ceedc1dbd320-20261113T171009Z.log`, 4 of 5) and
  passed after it. The targeted run (`buildbox-porelo-6dc976e150ed-20261113T171416Z.log`) ran 454 tests with 0
  failures and 0 skipped.
