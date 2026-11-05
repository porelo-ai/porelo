# B438 — No Character Of A Token In A Line

**Status:** Review.
**Size:** S - the two abbreviations go, and the lines that used them say what they can.

**What must be true.** No character of a token appears in a start's lines, a log, an exception, or anywhere a person or a
log can read it. A prefix is part of a secret, even a task-scoped one.

## Why

A start's lines said `token ANTHROPIC_API_KEY=porelo_pt_kW89...`, and a window showed them. `gate.log`, which the daemon
streams, said the first four characters of a task's gate token.

## As built

`PhantomToken.abbreviate` and `TaskToken.abbreviate` are gone, so nothing can print a prefix. A start says the variable's
name; a credential's line says `as PORELO_TOKEN_<NAME>`; the gate and the broker say `not shown`. `toString` of each holds
no character of the value. A log written before this keeps what it holds, until
it ages out.

## Acceptance

- A task's start names the variable and holds no `porelo_pt_`, on a real start.
- **Seen to fail:** both tokens' `toString` tests, and `the agent is given a token worth nothing` against a binary from
  before this.
