# B201 — A Start Now, Built Or Removed

**Status:** Analyzed.
**Size:** S - one option, a comment in the contract and two lines of docs; no change in what the daemon does.

**What must be true.** `porelo task start --now` does what it says, or is no longer offered: an option a person can
type and that changes nothing is either built or removed, with whichever is chosen written down.

**Why.** Found in the rereading of `app-task` against the house skills (2026-10-17): `--now` is accepted by `task start`
and does nothing today. Its help says what it was meant for: "Returns as soon as the task exists, without waiting for
the image build. Needs --detach" - what the daemon's `Start` does with its `now`. The analysis finds why the command
line lost it and whether a script relies on the option being accepted. Removing it changes the command line; building it
may change what the command waits for.

## Plan

Read in the code on 2026-10-20 (`apps/app-task`, `daemon`).

**What is there.** `--now` was never built, not lost: it came with "WIP: the lifecycle cut, contract and CLI done,
tests in progress" on 2026-09-11, and from that commit on its only use is `TaskRunCommand`'s refusal without
`--detach` (`:300`). The daemon's `Start` declares `now: ?bool` (`15-task.varlink:454`) and promises "returns as
soon as the task exists" (`:398`), but `TaskMethods.Start` (`:616-760`) never reads it; `phase` is always ""
(`TaskInventory.java:556`). `doc/commands.md:118` promises it too. No script, scenario or test passes it, but
porelo-frontend's *start again* does (`PoreloBackend.startAgain`, `fleet_backend.dart:498`, `now: true`), so every
installed interface sends it. `task start --detach` today waits for the image, the
container, the workspace and the helpers, then prints `attached  no`.

**Removed**, as decided:
1. `--now` goes from `TaskRunCommand` (`:195`) with its refusal; picocli then refuses it as an unknown option.
2. `Start` keeps `now` declared, since `Tasks1` only grows, and ignores it, as it does today; its comment and `:398`
   say that it has no effect, so no client is promised an early return. Refusing it would break *start again* in
   every installed interface. `phase` stays, documented as unused. porelo-frontend stops sending it on its side.
3. `doc/commands.md:118` loses the clause.

**Red first:** `TaskRunCommandTest`: `--now` is refused as an unknown option, where today it is accepted and does
nothing. The daemon's behavior does not change, so it gets no new test.

## Acceptance

- No option or parameter a person or a client can give does nothing; the docs promise nothing about it.
