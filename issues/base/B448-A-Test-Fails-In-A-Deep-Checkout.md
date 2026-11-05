# B448 — A Test Fails In A Deep Checkout

**Status:** Idea.
**Size:** XS - one test's fixture.

**What must be true.** The unit tests pass wherever the repository is checked out.

## Found

`AgentChoiceTest.aProviderTheAgentCannotDriveIsDroppedAndTheAgentStaysSignedIn` fails in a worktree below
`.claude/worktrees/`. A sub-agent reported that its fixture makes the socket path relative to the working directory, which
reached about 110 characters and failed with `Unix domain path too long`. It failed in 6 of 7 runs there, and passed 4 of 4
on the base under a short path. Reported, not run by whoever wrote this down. A shorter place for the socket, such as a
temporary directory, is what it asks for.
