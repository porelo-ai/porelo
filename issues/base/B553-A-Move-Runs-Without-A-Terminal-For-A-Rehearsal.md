# [B553](README.md#b553) — A Move Runs Without A Terminal, For A Rehearsal

**Status:** Review. Built on the operator's order of 2026-11-09 for the rehearsal; nothing of it reaches
`main` before the operator's move-4 test. Filed 2026-11-09 for the operator's automated rehearsal of a move on a rented
server.
**Size:** M - a walk through every porelo command a move's person runs, unattended, each gap closed with a test.

**What must be true.** Every porelo command a person runs in a move works with no terminal and no person at a keyboard,
given an SSH agent that serves the person's key and a vault opened beforehand by `porelo vault unlock
--passphrase-command` (or a kept passphrase): `identity create`, `org create` and its `--check`, a project's founding
and `project sign`, `vault put` of a forge token from stdin, `task start` of an unattended task, `gate review` and
`approve`, the build reads, and the person's central push and pull. None of them prompts; where one would, it refuses
at once, naming what it lacks, never waiting on stdin. Every exit code stays what the command answers today, with or
without a terminal: porelo's window runs it without one and reads the codes as a contract.

## Why

The operator wants a move rehearsed by the agents on a rented server before their own test. The run is driven by a
script over SSH: a prompt it cannot answer hangs the run until its hard stop, and the failure reads as a timeout, not as
the command that asked. Several commands open a shut vault at a terminal (`openIfShut`), and only some take a
`--passphrase-command`; which of a move's commands still ask has not been walked.

## Plan

- List the commands of a move's person (the move's guide and the acceptance features) and run each without a
  terminal against a VM: stdin closed, no TTY, vault unlocked by a passphrase command, the key in an agent.
- Each that prompts or waits: a refusal naming what it lacks instead, with a test seen red first; each that needs a
  secret takes it on stdin or from the vault, never on a command line.
- One acceptance scenario that runs the person's part of a move from founding to an approved push without a TTY.

## As built

Built by Codex CLI on Agent Core's brief, reviewed and reworked by Agent Core.
- **What waited or asked, and now refuses at once.**
  - `vault put` and `vault unlock` read the context's stdin, not `System.in`, and offer no console without a terminal.
  - Every organization and gate lease turns Git's and SSH's askpass off and clears inherited credential helpers, even when nothing is lent (`FollowCredential`, `GateSupport`, the new `UnattendedGit`).
  - `gate approve` and `approve` make no offer without a terminal and read the context's stdin: they refuse naming `--yes` (1, or 64 where several tasks wait, as an empty stdin answered before).
  - With stdin a terminal but no console (stdout piped), `vault put` and `vault unlock` never read the terminal with echo on: they refuse, naming a pipe on stdin.
  - `gate approve --signed` signs without a terminal only with a public key the SSH agent serves: inline, `key::`, a `.pub` file, or a private key file whose `.pub` the agent serves. The signing merge gets exactly the checked agent socket, and inherited `GIT_CONFIG_COUNT`/`KEY`/`VALUE`/`PARAMETERS` are stripped from it. A private key without such a `.pub`, a default key command or GPG is refused before Git is started, since ssh-keygen and pinentry can open `/dev/tty` with askpass off.
  - `org create --check` refuses a shut vault before asking the forge; plain `org create` refuses it only where it would first open the vault, so every earlier answer (65, 64, 69, 0) keeps its code.
  - `credentials declare` handles an agent key it cannot select instead of failing unhandled.
  - The KeePassXC path refuses without a terminal before starting KeePassXC.
- **Exit codes.** None changed. A path that used to wait now answers the code its command already gives for the nearest refusal: 70 for the gate's signing refusals, 77 for a shut vault in `org create --check`, and 70 for `credentials declare`'s absent or malformed agent key, as before [B553](README.md#b553). The first build had split codes by terminal (69 where 77 was answered), which would have told the window that a write refused before anything happened "may have been made". The review took that out.
- **Words.** Each refusal without a terminal names the step that gives what is missing: `porelo vault unlock --passphrase-command`, `SSH_AUTH_SOCK`, or `--stdin`.
- **Tests.** `MoveWithoutTerminalTest`, `UnattendedGateTest` and `UnattendedGitTest`, plus updated cases in `BuildStatusCommandTest`, `CheckoutApprovalTest`, `OrgWritingCommandsTest`, `IdentityKeePassXcTest` and `VaultPassphraseStdinTest`.
  - Existing tests that pinned what [B553](README.md#b553) changed on purpose were adjusted, each with its reason: a cleared inherited credential helper, the gate's Git now run through the context and so through the fixture's runner, shell passphrase commands run in the fixture.
  - `apps/app` ran whole outside a sandbox: 3692 tests green.
  - Agent Core also saw these go red: `approve` reading `System.in` (a timeout in a separate thread, since a blocked read cannot be interrupted), echo with no console, the shut-vault guard before validation, and the stripped Git configuration.
  - Agent Core saw the askpass suppression, the agent-served signing key and the shut vault before the forge go red with their checks broken.
  - The real signing through an SSH agent ran green outside Codex's sandbox, after its fixture's upstream (a non-bare checkout) was set to `updateInstead`.
- **Not done here.** The plan's walk of each command against a VM, and the scenario from founding to an approved push without a TTY. The acceptance suite has no forge organization to found against. The rehearsal on the rented server is that run, so its findings come back here.

