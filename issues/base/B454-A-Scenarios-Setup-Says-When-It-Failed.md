# B454 — A Scenario's Setup Says When It Failed

**Status:** Backlog.
**Size:** XS - steps in one feature.

**What must be true.** The first-start scenario fails where its setup failed, and its red does not bring others.

## Found

Codex's reading of the fix for Build 407, in `project-first-start.feature`. Both are older than that fix, and the operator's Coordinator
kept them out of that tip:

- **Setup failures are unchecked.** `a script runs` records its exit and asserts nothing, and the scenario never asks for it. When an
  account had no `ecosystems` directory, writing `answers.json` failed unseen, and the red came later, under a message saying nothing
  of it (line 18).
- **A failure leaves its answers behind.** Clean-up is a later step (line 66), which a failed assertion skips. The stub ignores what a
  checkout holds, so it goes on answering Maven and a host for later scenarios of that account, and one red can bring others.

## What Is Asked

- The setup step is followed by a check of its exit.
- The answers are removed whatever happens: an `After` hook, or a stub reading them from the checkout rather than the account.
