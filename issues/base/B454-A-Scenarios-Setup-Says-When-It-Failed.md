# B454 — A Scenario's Setup Says When It Failed

**Status:** Review.
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

## Implementation

- The setup script stops at its first failed command and is followed immediately by `Then it exits zero`.
- A scenario tag selects a suite-local `After` hook that removes only the installed ecosystem stub's `answers.json`,
  even after a failed assertion. A failed removal fails the hook. The stub's answer-file contract stays unchanged.
- Unit tests exercise the setup script with a failing Git command, answer removal, repeated cleanup, and a cleanup
  refusal. Each failure path was observed red before its fix passed.
- The independent read-only review found no actionable findings. No other repository or window change is needed.

## Validation limits

The full dependency unit-test run stops in the unchanged wire module: socket tests refuse the temporary directory's
ancestor ownership in this environment. No acceptance scenario or VM was run. The machine check is the scenario in
`acceptance/suite/src/test/resources/org/fuin/porelo/acceptance/project/project-first-start.feature`, including a failed
setup and a failed assertion after the answers are written, followed by checking that the answers are absent.
