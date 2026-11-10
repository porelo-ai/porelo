# [B539](README.md#b539) — A Missing Host Forge Token Answered On The Wire

**Status:** Review; Backlog by the operator 2026-11-10. Filed 2026-11-09 for porelo-frontend's forge token on a host, made by the host when a
start needs it, which the operator accepted with priority High; this is porelo's part. Not built before the
operator's word.
**Priority:** high (the operator, 2026-11-09, with the window's forge token on a host).
**Size:** S - one contract type, a field on `CanStart`'s answer and one on the task's builds, both filled from the check
`BuildsWiring` already makes; tests and the contract's text.

**What must be true.** A client learns, as an answer and never only as a line on standard error, that the person's
vault on a host lacks the credential the host's forge builds are read with: before a start (`CanStart`) and while a
task runs (its builds). The answer names the vault key and what to run on that machine to make it
(`porelo-host-forge-token`, nothing on standard input), so the window can offer it as one step.

## Today

- `BuildsWiring` (apps/app-task) checks at start whether the vault holds `builds.credential` of the host's project
  (`host-builds`); when not, it writes "the builds of P on this host are read with 'host-builds', which your vault
  does not hold; run porelo-host-forge-token on this host, then start the task again" to standard error and as the
  task's build note (`said`), and the task runs without its builds. `TaskRunCommandTest` holds the sentence.
- On the wire this reaches a client only as text: the task row's `buildProblem` (15-task.varlink), and nothing at all
  in `CanStart`. A window cannot tell it from any other build problem, nor offer the command.

## Proposal (to agree with Frontend)

- **The start is not held back.** The task works without its builds; only the "told what its builds did" is missing,
  and holding the start would make a host's builds a precondition of every task. `CanStart` stays `ready: true` and
  says it beside the outcome; Frontend may still choose to ask before starting.
- **One type, two places:**
  ```
  # A credential this machine's vault lacks for something beside the agent: what it is for, the vault key, and what
  # to run ON THAT MACHINE to make it. storeCommand reads nothing on standard input.
  type MissingCredential (
    # "builds" for now; show an unknown one as it comes.
    for: string,
    credential: string,
    storeCommand: []string,
    # What it takes after storeCommand ran: "ask-again" (CanStart answers anew) or "restart" (a running task follows
    # its builds only from its next start). Show an unknown one as it comes.
    afterStore: string
  )
  ```
  - `CanStart(...) -> (..., missing: []MissingCredential)`: empty when nothing is missing, or the question never got
    that far (no project, not a host, the vault shut - then VAULT_LOCKED is the answer).
  - The task's builds: `buildsCredential: ?MissingCredential` beside `buildProblem`, set while the task follows no
    builds for that reason, with `afterStore: "restart"`: `BuildsWiring` starts no build reader without the
    credential, so a running task is told its builds only after a restart; the window offers Restart beside it.
    `buildProblem` keeps its sentence for clients that read only text. In `CanStart` it is `"ask-again"`.
  - `Build.detail` of a build read for the person (`porelo build status`) on a host with no such credential gives
    the same sentence; no structured field (Frontend reads builds only through the task).
- `storeCommand` is `["porelo-host-forge-token"]` as the host's package names it today. It moves with the
  porelo-host CLI proposal, if that is taken (`porelo-host forge-token` or the like); the field, not the client,
  carries the name, so a client follows without a change.
- Red first: `CanStart` on a host project whose vault lacks `host-builds` answers `ready: true` and one
  `MissingCredential{for: builds, credential: host-builds, storeCommand: [porelo-host-forge-token]}`; with the entry
  present, none; off a host, none. The task row's `buildsCredential` likewise.

## Agreed with Frontend (2026-11-09, the room)

- Yes to the wire above. The window shows it beside a ready start (New work, the batch: one per host) and on a task's
  builds, runs `storeCommand` on that host in a terminal, then asks `CanStart` again (`missing` empty = done), and
  offers Restart where `afterStore` is `restart`. Unknown `for` and `afterStore` values are shown as they come; the
  vault key is shown as given, never composed.
- No field on `Build`: the window never runs `porelo build status`.

## As built

- The agreed `MissingCredential` type is carried by `CanStart.missing` and the task's optional
  `buildsCredential`. A missing host builds entry leaves `CanStart` ready, with `afterStore: "ask-again"`;
  a shut vault still answers `VAULT_LOCKED`, and no project, no gate or no host produces no missing entry.
- The task's existing builds record now keeps the typed reason beside its unchanged sentence. Listings read that
  record without reaching the vault. The task retains `afterStore: "restart"` after the entry is stored, and the
  next reader outcome clears the reason. An older sentence is never parsed to invent a credential.
- The read-only review found that a task restart previously replayed only helpers that had already started.
  A behavior test reproduced the missing reader after storing the token. The existing builds record now keeps
  the deferred command, including the settled branch and guarded policy; resume records and starts it only once
  the configured entry is readable. The builds record is saved with durable task state too.
- The command's name changed since this proposal: it is now `porelo host forge-token`, carried as
  `["porelo", "host", "forge-token"]`. The diagnostic and the structured field take it from one value.
- The host file's validated `builds.credential` is not restricted to `host-builds`; it can name another vault key.
  Both answers preserve that name, tested with `forge.person`. The project file's own builds still take precedence.
- A person's host build query gives the same sentence in an unknown verdict's `detail`, with no structured field.
- Behavior tests were seen red before the implementation. The build report records the selected class runs,
  contract check, document checks and any environment limitations; the supervisor runs the whole suite.
