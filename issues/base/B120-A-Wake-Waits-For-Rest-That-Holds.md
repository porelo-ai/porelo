# B120 — A Wake Waits For Rest That Holds

**Status:** Implementing.
**Size:** S - `AgentWake` in `app-messaging`, `Podman.enter` and their unit tests; no contract.

**What must be true.** Porelo types its wake line into a task's session only when the agent's declared `at_rest` has
held on two looks at the screen a short while apart, with no sign of work between them, never into a turn that is
still running.

## Why

`AgentWake.wake` reads the screen once and types at once when `at_rest` matches. An agent's work line comes and goes
between its tool calls, so one look can fall into such a gap and take a working agent for one at rest. Seen on
`porelo-claude-code`'s run `37343923956`, job "Acceptance - fedora" (`porelo` `0.4.1~snapshot.250`, Claude Code 2.1.267):
the wake line stood right under the typed prompt, before the agent's first tool call - the instant between Enter and
its working line matched `at_rest`, which asks for `bypass permissions on` and the absence of `esc to interrupt` - and
the agent then reported "empty messages that keep interrupting my tool calls" and its tool use was interrupted. The
same scenarios were green on other legs with the same code, which is what a race looks like. The acceptance kit's
own ready check already asks that a marker stay on screen for a while before it counts.

**One definition of rest:** [B118](README.md#b118) reads `at rest` for status and list from the same `at_rest`; it takes rest by this
rule too, so a status never says *at rest* for the instant the wake would not type into.

## The line must also leave the input box

Seen on 2026-10-10 in an agent repository's acceptance run: Claude Code v2.1.267 draws a notice about an unknown model
when it starts, and the Enter typed after a line is taken by it. The line stays standing in the input box, and the
agent never works. `Podman.type` types the wake line and then Enter in the same way, so a wake can be lost the same way:
typed, never submitted, and nothing says so.

So after typing, Porelo looks at the screen again. If its wake line still stands in the input box, it presses Enter once
more, as a person would, and says in the journal that it had to. A line that still stands after that is said as a wake
that did not reach the agent, never as a wake that did.

## Acceptance

- Seen to fail first: a wake whose Enter is swallowed leaves the line in the box; afterwards Enter is pressed once more and
  the journal says so, and a line still standing after that is reported as not delivered.

- A screen that matches `at_rest` on one look and shows work on the next is not typed into. Seen to fail: a unit test
  whose stand-in screen rests for one look only, against the code that types after one look.
- A screen at rest on two looks a short while apart is typed into once, as today. Seen to fail: the same test with
  the rest held, if the wake no longer types at all.
- The wake scenarios of the agent repositories stay green with the change, on both legs.

## As built, 2026-10-10

- `AgentWake.wake` reads the screen twice, `LOOK` (1.5 s) apart, and types only when both show rest and no open
  question. A moment after typing it reads again: a screen that shows work means the line was taken. One that still
  rests gets Enter once more (`Podman.enter`) and a journal line; one that rests after that is said as not delivered,
  answers `false` - so the message stays unannounced and is tried again - and leaves a mark beside the task's state,
  so the next try presses Enter alone instead of typing the line into the box a second time.
- **Answered:** the daemon's pass waits between the looks; the second look is not left to the next pass.
- **Tested:** `WakeLandsTest` - rest for one look not typed into, rest that holds typed into once, a line left in the
  box submitted once more, a line still standing not delivered and only submitted next time; `AgentWakeTest`'s agent
  now takes the line it is given, as a real one does.

## Plan

Built and on `origin/main` (`3f678c27`): `AgentWake.wake` and `rests`
(`apps/app-messaging/src/main/java/org/fuin/porelo/app/AgentWake.java`), `Podman.enter`, `WakeLandsTest` and
`AgentWakeTest`. What is left is no code here:

- The wake scenarios of the agent repositories, on both legs, against a `porelo` snapshot that holds `3f678c27`. A red
  one there is read for whether `LOOK` is too short for that agent's work line, before anything else changes.
- [B118](README.md#b118) takes its rest by this rule; the test of `rests` moves into `app-model` then, and `AgentWake` calls it there.
