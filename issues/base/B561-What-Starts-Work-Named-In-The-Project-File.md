# [B561](README.md#b561) — What Starts Work Named In The Project File

**Status:** Backlog. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** M - signed work chains, feedback targets and a round cap add graph checks to the model, schema and runtime workflow check.
**Blocked by:** -.

**What must be true.** The signed project file chooses the project's planning adapter and says which working states
start an agent's work, and nothing else may say it:

```yaml
planning:
  adapter: stories-git
  credential:                            # what porelod hands the adapter as the token of each call
    kind: github-app                     # or token: the vault entry's value as it is
    entry: porelo-project-planning-app   # the vault entry; for github-app the app's private key
    app: 123456                          # github-app only: the app's id
    installation: 7890123                # github-app only: its installation on the store's owner
  settings: { repository: porelo-project, stories: stories }   # passed to the adapter unread
  reading: { rounds: 2 }                 # default maximum Reading attempts per issue and work cycle
  work:
    In analysis:
      from: [Accepted]
      done: Analyzed
      per: story
      modes: [UNATTENDED, AGENT]
      order: "Analyse {story}. When done, end with a last line 'Outcome: done' or 'Outcome: blocked - <why>'."
      model: { anthropic: claude-opus-5-5 }
    Implementing:
      from: [Backlog]
      done: Review
      then: Reading
      per: repository
      modes: [UNATTENDED, AGENT]
      order: "Build {issues} of {story}. End with 'Outcome: done' or 'Outcome: blocked - <why>'."
    Reading:
      from: [Implementing, Reworking]
      done: Review
      blocked: Reworking
      per: repository
      modes: [UNATTENDED]
      order: "Read the recorded commit for {issues} as a stranger. End with 'Outcome: done' or 'Outcome: blocked - <why>'."
    Reworking:
      from: [Reading, Rework]
      done: Review
      then: Reading
      per: repository
      modes: [UNATTENDED, AGENT]
      order: "Fix the findings for {issues}. End with 'Outcome: done' or 'Outcome: blocked - <why>'."
  coordinator:
    order: "Oversee {story} and its tasks {tasks}."
```

A file whose `planning` is malformed is refused with the key and why. A repository's `porelo.yml` cannot set it.
The state names are the adapter's, from its one workflow of stories and issues (`stories/workflow.yml` for
stories-git); they are checked against it where porelod asks it ([B562](README.md#b562)), not here. A working state,
a key of `work`, is entered only with work: by Start or a recorded completion's configured chain, never by a
move ([B564](README.md#b564)). On success, `then` enters its working target instead of publishing `done`; without
`then`, `done` is the waiting/closed destination. Thus Implementing and Reworking enter Reading directly, and
Reading succeeds into Review. Only blocking findings follow `blocked`; notes go to Review. The signed
`planning.reading.rounds` defaults to 2; Start may override it per story or issue and records the effective limit
with the work ([B565](README.md#b565)). Blocking findings at that limit leave the issue in Reading with *Needs you*
and the last findings ([B567](README.md#b567)). These are example adapter states, never names to branch on in
porelo. No agent starts another or calls MCP to do so.

## Plan

Read in `core/project/file` (`Mcp`, `Sections`, `ProjectFile`, `ProjectFileReader`, `FileBinder`,
`ProjectFileWriter`, `FileYaml`, `ProjectFileCheck`), `core/project/ProjectSchema`, `Project` and `ModelDefault`.
There is no planning model or runtime chain today; the adapter/client and `WorkCheck` are planned in
[B560](README.md#b560) and [B562](README.md#b562).

- Add `Planning` (adapter, credential, unread settings, work, coordinator, reading),
  `PlanningReading` (rounds, default 2),
  `PlanningCredential` (`GITHUB_APP | TOKEN`, entry, app, installation), and `PlanningWork` (from, done, optional
  then and blocked, per `STORY | REPOSITORY`, modes, inline order, model by provider), on `Mcp`'s model pattern.
  `UNATTENDED` and `AGENT` are the modes; `SHELL` has no agent and is refused. The cap is the signed
  `planning.reading.rounds`, a positive whole number; absent reading or rounds resolves to 2. It supplies Start
  with a default, not a fixed upper bound on the limit the operator may choose for each issue.
- `Sections` excludes planning from both repository readers; `ProjectFile`, `FileBinder`, `FileYaml` and
  `Project` carry it, with `ProjectFileReader`/`ProjectFileWriter` round trips. Keep absent planning compatible.
- `ProjectFileCheck`: check adapter and credential names; app/installation required whole numbers for github-app
  and absent for token; nonempty from; terminal done distinct from the key/from; valid per/modes; nonempty inline
  order with only `{story}`, `{issues}`, `{tasks}`; model one word per provider; bounded unread settings. Check
  then/blocked targets name work entries and disallow self-targets and unbounded success-only cycles. The intended
  then/blocked feedback loop is permitted because the round cap stops it. No coordinator-specific MCP exception.
- Extend the `WorkCheck` contract of [B562](README.md#b562): every work key is an adapter WORKING state, every
  from/done/then/blocked name exists; done is non-WORKING; then/blocked are WORKING with a work entry and permit
  their source in from. Every adapter WORKING state has work configured. Unlike the old terminal-only check,
  success may chain into WORKING through then. Surface faults in `GetPlanningWorkflow`; reads remain available,
  starting work with faults is refused. Core does only structural checks, with no adapter dependency.
- `ProjectSchema` and `doc/project-file.md` document the fields, default cap and bounded feedback semantics.
- Red first: extend existing `FileRoundTripTest` with the chain above: omitted reading/rounds resolves to 2 and
  explicit reading.rounds round trips; today planning is unknown. `ProjectFileFaultsTest` rejects zero/fractional rounds,
  missing target and success-only cycle. The planned `WorkCheckTest` uses the adapter workflow to reject a missing
  Reading, a WAITING then target or an incompatible from while accepting Reading/Reworking feedback.

## Acceptance

- The section reads, writes back unchanged and is answered by `Project`; each malformed key is refused by name; the
  same section in a `porelo.yml` is refused; default reading.rounds is 2 and Start may raise or lower it per story
  or issue. Adapter state faults are reported before any start; the bounded Reading/Reworking loop passes without
  hardcoded state names.

