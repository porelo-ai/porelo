# B459 — Every Credential's Failure Says What To Do

**Status:** Backlog.
**Size:** M - a guess until they are read; the first step says it again.

**What must be true.** Every failure of a credential says what happened and what to do now, with a control for it in a window, and the command at a
terminal. Nothing shows a tool's raw line alone.

The operator's word, 2026-11-05: a rule beyond [B458](README.md#b458). Nothing is built before the test.

## Plan

1. List the ways a credential fails today, by kind and place: a key refused or locked, a token expired or without the right, a vault locked, an agent
   with no keys, a host key unknown. Each with what a person now reads.
2. Say the size again from that list, and agree the field with Agent Frontend.
3. Each gets its words and its remedy, only where the remedy has been seen to work; where there is none, it says so plainly.
