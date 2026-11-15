# B790 — A Planned Start on a Host Works on Central's Newest Main

```json
{
  "$schema": "issue-1.json",
  "id": "B790",
  "title": "A Planned Start on a Host Works on Central's Newest Main",
  "state": "Backlog",
  "size": "L",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "On a host, a start given work first has the host follow central, which fast-forwards a carried repository's copy where it can, and refuses while central still holds commits the copy lacks or its standing cannot be read, naming the way back; the host's central state tells a copy behind central from one that diverged, so a task never works on a copy that misses what the planner just saved",
  "openQuestions": 1
}
```

**Why this size.** L: the follow's fast-forward for every carried repository, a follow asked at the start, a fresh
read of central's `main` with the person's central credential as `porelo host pull` reads it, a judgment against the
copy, a new start outcome on the contract, a new `diverged` state in the host's central state and its readers, the
start's words, with their tests and `doc/`. Estimate: 114 minutes (Core's median for L).

**What must be true.** A task started on a host for a planned issue works on a copy that holds everything central's
`main` holds; when central moved on, the start says so and names the way back, and the agent never writes a second
file for an issue that central already has.

Filed 2026-11-15 from the operator's live test on a rented host (porelo 0.5.1~snapshot.552), as a bug.

## Why

The window's planner saved an idea and moved it to In analysis on central's `main` of a repository the host carries
(two commits, 10:25 and 10:27). The In-analysis task started at 10:27 on the host's copy, which did not hold them,
found no file for its issue and wrote a second one under another name. Its pull request was merged into the host's
`main`, so the host's and central's `main` now diverge and hold two files for one issue.

Read in the code: by design the host's copy is where work lands and goes to central by the person's gathered push;
central's commits come back only by `porelo host pull` into `central/main`, merged by a task (`doc/hosts.md`, "A
person's gathered push and pull"). The host's follow moves the host's `main` by itself only for the project's own
repository and only for a change of `project.yml` (`ProjectAdvance.move`, `other-changes` otherwise). The host's
central-state file marks a copy `moved-on`, but it is measured by the follow's pass, not at the start, and no start
reads it. So nothing at the start sees that central is ahead.

The window showed the repository afterwards as "central moved on" alone, though the host's merged pull request is
ahead of central as well: `ProjectReadKeys` answers `moved-on` whenever central's `main` is not an ancestor of the
copy's, so a copy behind central and one that diverged from it read the same, and the second needs a reconciling
task, not a pull alone.

## Plan

Agent Frontend, 2026-11-15: the planner writes to central by design, as planning is one board read from central by
every machine and person; the host's copy follows central. So the start brings the copy up first and refuses only
where it cannot. Agent Machines, 2026-11-15: nothing in porelo-host refreshes a copy; only `org follow` moves one.

Decided by the operator, 2026-11-15 (~11:03 Berlin): yes, automatically. The follow fast-forwards every carried
repository's copy to central's `main` where the copy's `main` is an ancestor of it, every five minutes and right
before a task start, under the `tip` reach's exclusions; where the copy holds work of its own, it is left as it is
and the start refuses with the way back. When built, `doc/decisions.md` records this decision.

1. The follow fast-forwards a carried repository's copy to central's `main` where the copy's `main` is an ancestor of
   it, for every carried repository and not only the project's own, under the rules the `tip` reach keeps today
   (`ProjectAdvance.move`: a change of `.forgejo/`, `.gitea/` or `.github/workflows`, or a `snippet_file`, is not
   moved by itself and stays `other-changes`). The project's own repository keeps its `signed` reach while a task
   works in it, as now.
2. A start on a host with `work` (`Start`, and the window's ordered and discussion starts through it) first asks the
   host to follow now (`porelo host follow`, `HostFollowNow`, the existing sudo rule), after it knows the repository
   (`workedOn`) and before `reserveTask`, so a refusal creates nothing.
3. Then it judges the copy against central's `main` read afresh: its ref alone, with `<forge>.central` from the
   person's vault on the host, by the bounded `ls-remote` `HostPush.forgeBranch` uses (or a small helper both share),
   never `HostGatherFlow`'s transfer, which writes `central/main`. The central address is `repository.central()`,
   the project's upstream for its own repository. Central's commit held by the copy (present in the task's mirror of
   the copy and an ancestor of the copy's `main`, `HostForgeAddress.copyOf`) starts as today.
4. Anything else refuses the start with a new `StartOutcome` `CENTRAL_AHEAD` before any container is made. Its
   detail names the repository and why it could not be brought up: central moved on with a change the follow does
   not take by itself, or the copy diverged (it holds commits central lacks). It names the way back, `porelo host
   pull` then a task that merges `central/main`, approved and pushed, and, where the copy is ahead, the gathered
   push. Nothing of the credential reaches it.
5. A central that cannot be read (no `<forge>.central`, a shut vault, a network failure) or a follow that does not
   answer refuses as well, with words that the copy's standing against central is not known: a planned task on a
   copy that may miss its own issue is what this prevents.
6. A start without `work`, and a start off a host, are unchanged.
7. The host's central state gains `diverged` beside `moved-on` (`ProjectReadKeys`: central not an ancestor of the
   copy and the copy not an ancestor of central), read by `HostCentral` and the inventory; `moved-on` keeps meaning
   behind alone. An added value: a window that does not know it shows it as it shows `moved-on`.
8. `15-task.varlink` names the outcome and the state; `doc/hosts.md` says what the follow moves by itself, when a
   start refuses, and what `diverged` asks of the person. The window says the refusal (F586) and offers the push
   where the host is ahead (F590).

## Red-first test

- The follow, a carried repository that is not the project's own, central one commit ahead with an issue file: the
  copy's `main` moves to it. Fails today: unmoved.
- The same with a change under `.forgejo/workflows`: unmoved, `other-changes`.
- A host start with work whose central `main` holds a commit the follow could not take: `CENTRAL_AHEAD`, no
  container made. Fails today: the task starts.
- A start with work after the follow moved the copy: it starts on central's commit.
- Central unreadable: `CENTRAL_AHEAD` with the standing not known; nothing made.
- No work given: unchanged.
- The host's central state of a copy holding a commit central lacks while central holds one it lacks: `diverged`.
  Fails today: `moved-on`.

## Acceptance

- The red-first tests pass, each seen red first.
- On the rented host, a planned start right after the planner saved to central works on the planner's file; on a
  diverged copy it is refused with the way back named.

## Open questions

1. To be checked on the host: the PI68 task still shows "1 waiting at the gate" after its pull request was merged.
   Whether that push still waits in the gate's incoming refs, whether the gate's measurement sees it landed
   (`alreadyUpstream`), and whether the task's end removes it; if it stays, it is a bug of its own.
