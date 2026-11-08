# [B513](README.md#b513) — The Agent Choice Test Holds In Any Checkout

**Status:** Review. Filed and built on 2026-11-08 at the operator's word.
**Size:** S - find why the choice differs between checkouts, and make the choice or the test not depend on it.

**What must be true.** `AgentChoiceTest.aProviderTheAgentCannotDriveIsDroppedAndTheAgentStaysSignedIn` passes in
every checkout: the main one, a git worktree under `.claude/worktrees`, and CI.

## Why

On 2026-11-08 the test failed in every sub-agent's worktree and passed in `~/git/porelo` and in CI. The test was
reproduced in `.claude/worktrees/agent-a9642fd9748b02be6`: `choice.agent().name()` was `"unknown"` where
`"wrong-dialect"` was expected. Each sub-agent had to say it was not theirs, and each report had to be checked for it.

## Analysis

Found on 2026-11-08: not the listing order, as first thought, but the length of a socket's path.

- The tests that start agents (`AgentChoiceTest`, `CredentialImportTest`, `TaskRunCommandTest`) gave the runtime
  directory relative to the working directory, "so the sockets fit the Unix limit even when the checkout's path is
  long". The directory is a `@TempDir` under `/tmp`, so the relative way climbs out of the checkout one `../` per
  directory: 3 bytes each.
- From a sub-agent's worktree (`~/git/porelo/.claude/worktrees/agent-<id>/apps/app`), the stand-in agent
  `porelo-agent-wrong-dialect`, the longest name, could not bind its socket ("Unix domain path too long"). Only
  `unknown` started, and as the one agent installed it was chosen.
- Reproduced in a worktree 12 directories deep; it passes in `~/git/porelo` and in CI, which are shallower.

## As built

- `SocketPaths.shortest` (in `apps/app`'s tests) takes the shorter of the relative and the absolute way. The three
  tests use it.
- **Red first:** `SocketPathsTest.aTemporaryDirectoryFarFromADeepCheckoutIsTakenAbsolute` was red against the old way:
  `../../../../../../../../../tmp/junit-…/run` where `/tmp/junit-…/run` was expected.
- `AgentChoiceTest` and `CredentialImportTest` pass from the 12-deep worktree where `AgentChoiceTest` failed.
- `VarlinkServerSocketTest`'s relative path stays: it tests a relative socket path on purpose.

## Plan (before it was built)


- Find where the two tie, and whether that is the choice's fault or the test's.
- **If the choice's:** a signed-in agent ranks before one that is not, whatever its provider, and equal ones are taken by
  name. A tie never falls to the listing order.
- **If the test's:** the test names what it means, without relying on order.
- **Red first:** the same case with the files listed in the other order, through a listing the test controls.
