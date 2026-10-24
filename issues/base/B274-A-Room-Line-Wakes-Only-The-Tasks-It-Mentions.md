# B274 — A Room Line Wakes Only The Tasks It Mentions

**Status:** Accepted. Analyzed after sprint 2c. Its counterpart in porelo-message-matrix reports a line addressed
to the whole room.
**Size:** S, a guess: one rule in `AgentWake.namesIt`, one field read from the transport, `doc/messages.md`, and the
tests.

**What must be true.** A person's line in the project's room wakes only the tasks it mentions, and every task when it
is addressed to the whole room (`@room`). A line that names nobody is delivered to every task as today but wakes none:
each reads it at its next wake. A reply to a task's own message still counts as a mention of that task.

## Why

The operator decided, on 2026-10-24: "a person's room line wakes only the tasks it mentions, plus all of them on
`@room`. An unaddressed line is delivered, not woken, and read at the next wake; a reply to a task's own message stays
a mention." This turns walk 10's choice around.

Read on 2026-10-24: today `AgentWake.namesIt` wakes every task at rest for a person's room line whose `to` is empty,
written after walk 10, where a person asked "Wer ist da?" and no task answered. `doc/messages.md` promises it. So
every unaddressed line in a busy room interrupts every resting agent.

## To be decided in the analysis

- What porelo reads from the transport for `@room`: the counterpart's field, for example `"room": true`, and what a
  transport that does not say it means: no wake, as for an unaddressed line.
- Whether a direct chat and a task's own word to nobody keep today's rules: they are not part of the decision.
