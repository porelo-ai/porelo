# [B513](README.md#b513) — The Agent Choice Test Holds In Any Checkout

**Status:** Backlog. Filed 2026-11-08 at the operator's word; built after [B507](README.md#b507).
**Size:** S - find why the choice differs between checkouts, and make the choice or the test not depend on it.

**What must be true.** `AgentChoiceTest.aProviderTheAgentCannotDriveIsDroppedAndTheAgentStaysSignedIn` passes in
every checkout: the main one, a git worktree under `.claude/worktrees`, and CI.

## Why

On 2026-11-08 the test failed in every sub-agent's worktree and passed in `~/git/porelo` and in CI. The test was
reproduced in `.claude/worktrees/agent-a9642fd9748b02be6`: `choice.agent().name()` was `"unknown"` where
`"wrong-dialect"` was expected. Each sub-agent had to say it was not theirs, and each report had to be checked for it.

## Analysis

Seen, not yet proven:

- The test installs two agents, `wrong-dialect` (signed in, with a provider it cannot drive) and `unknown` (not signed
  in), as files in one directory.
- If `AgentChoice.considerAgent` takes them in the directory's listing order and the two tie once the provider is
  dropped, the order the file system lists them in decides. That order differs between checkouts.

## Plan

- Find where the two tie, and whether that is the choice's fault or the test's.
- **If the choice's:** a signed-in agent ranks before one that is not, whatever its provider, and equal ones are taken by
  name. A tie never falls to the listing order.
- **If the test's:** the test names what it means, without relying on order.
- **Red first:** the same case with the files listed in the other order, through a listing the test controls.
