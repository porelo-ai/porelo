# B136 — An Approve Bound To What Was Reviewed

**Status:** Analyzed.
**Size:** M - the gate's two commands, the daemon's `Approve` with a contract note, enrolment, docs, a scenario.

**What must be true.** What `sokar gate approve` forwards is the commit the person read, on the command line as in
the interface and the plugin. A push the agent makes between `sokar gate review` and `sokar gate approve` is never
forwarded unseen.

## Why

A task keeps running while its work is read. The incoming ref holds the agent's latest push, so a second push after
the review replaces what was read. Three ways to approve already bind to the reviewed commit and refuse a moved one as
`MovedSinceReview`: `sokar gate approve --commit <full id>`, `sokar approve` in a checkout (it lands the commit it
showed), and the daemon's `Approve` with `commit`, which the interface and the plugin always send. A plain
`sokar gate approve -p <project> <task>`, the form the documentation shows first, takes whatever the ref holds at that
moment. `--signed` binds only when the key is enrolled. `reach.md` calls approval a control; on this path it holds only
for the commit nobody read.

## The shape

- `sokar gate review` ends with the commit it showed in full and the approve line that names it:
  `sokar gate approve -p <project> <task> --commit <full id>`.
- `sokar gate approve` without `--commit`, where a person can be asked, shows the commit that waits now and asks
  before forwarding it; where nobody can be asked, it is refused with the commit that waits, so a script names it.
- The daemon's `Approve` refuses an empty `commit`, with the commit that waits, instead of taking what the ref holds.
- Enrolment's approve is bound alike: `sokar project enroll` names the enrolment's commit in the line it prints.
- `doc/commands.md`, `doc/how-it-works.md` and `doc/reach.md` show the bound form first.

## Plan

Analysed on 2026-10-11 in today's code. `GitGate.approve(name, branch, reviewed)` and `approveSigned(..., reviewed)`
in `gate/src/main/java/org/fuin/sokar/gate/GitGate.java` already bind through `reviewedCommit`: a full id that is
not what `refs/sokar/incoming/<name>` holds is `MovedSinceReview`; `null` or blank takes whatever the ref holds now.
That `null` comes from three places: `GateApproveCommand` without `--commit` (`apps/app-gate`), the daemon's
`Approve` without `commit` (`daemon/.../GateMethods.java`, documented in `35-gate.varlink` as "Empty: whatever it
holds now"), and the line `ProjectEnrollCommand` prints (`apps/app-project`), which has no `--commit`.
`CheckoutApproval` (`sokar approve`) passes the commit it showed and stays as it is.

1. **The commit is read once.** `GitGate.waiting(name)` (public, beside `waitingWork`, for every mode) answers the
   full commit and subject the incoming ref holds. `reviewedCommit` keeps its checks.
2. **`gate review` ends with it.** `GateReviewCommand` reads the commit first, renders the review and the log
   against that commit rather than the ref (`rankedReview` and `log` take it), and ends with
   `commit    <full id>` and `approve   sokar gate approve -p <project> <task> --commit <full id>`, so a push during
   the review is not what the lines name.
3. **`gate approve` without `--commit`.** At a terminal and without `--no-input`: it shows
   `waits     <full id>  <subject>` and asks through `Offer.confirm` whether to forward exactly that, no by default;
   a yes approves with that commit as `reviewed`, so a push between the question and the answer is
   `MovedSinceReview`; a no ends with exit 70 and the bound line. Without a terminal, or with `--no-input`: refused,
   exit 70, `sokar: name the commit you reviewed: --commit <full id> waits now`. The same for `--signed`.
4. **The daemon's `Approve` refuses an empty `commit`.** A new error in `35-gate.varlink`,
   `CommitNotNamed(name: string, waiting: string)`, raised in `GateMethods` before the gate is touched; the comment
   on `commit` says it is required in effect. The interface and the plugin send it already (`sokar-frontend`
   `gate.dart`, `sokar-intellij` `Gate`), so the change is announced on the channel and breaks no client that sends
   the commit it showed.
5. **Enrolment.** `ProjectEnrollCommand` knows the commit it pushed to `enroll-<machine>` and prints
   `merge     sokar gate approve <ref> -p <project> --signed --commit <full id>`; `doc/commands.md` (line on
   `enroll-<machine>`) and `doc/project-file.md` follow.
6. **Every other line that names `gate approve`** names `--commit`: `GateCheckoutCommand`, `GateCheckCommand`,
   `WorkspaceSetup` (an earlier task's work waiting), and the docs: `doc/commands.md`, `doc/how-it-works.md`,
   `doc/reach.md`, `doc/security.md`, `doc/getting-started.md`.
7. **Acceptance:** in `acceptance/suite/.../gate/gate-review.feature`, a task pushes, `gate review` is read, the task
   pushes again; a script's plain `gate approve` is refused, exit 70, naming the second commit, and nothing is
   forwarded; at a terminal the second commit is shown and a no forwards nothing.

Red first: a test beside `ApproveOfferTest` in `apps/app` - a pending push moved after the review, a plain
`gate approve` with `Offer.Asker.NOBODY` - forwards today and must be refused with exit 70; then `SokarDaemonTest`'s
`Approve` without `commit`, which forwards today and must answer `CommitNotNamed`.

## Acceptance

- Seen to fail first: a push after `gate review` and before a plain `gate approve` is forwarded today; afterwards it
  is shown and asked about, or refused without a terminal.
- `gate review`'s last lines name the full commit and the bound approve line.
- `--commit` with the commit that waits forwards it, as now; with another, `MovedSinceReview` as now.

## Open questions

- Whether a stopped task is reason enough to approve without asking, since nothing can move its ref any more
  (the operator's). The plan asks anyway: a stopped task can be started again before the answer.

## To be checked

- How the interface answers `CommitNotNamed` where it sends no commit to a Sokar it thinks too old to take one
  (Agent Frontend's).

**Guideline points, 2026-10-09:** a review of security guidelines counts this as its item `F20`,
answering AISVS 9.2.8, OWASP AI Agent Cheat Sheet §1/§4.
