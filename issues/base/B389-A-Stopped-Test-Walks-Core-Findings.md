# B389 — A Stopped Test Walk's Core Findings

**Status:** Review.
**Severity:** medium.
**Size:** S, proposed - four small fixes the stopped test walk of 2026-11-01 asked for before it resumes.

**What must be true.** An account set up by the host's helpers, with an umask of 002 and a host vault, is set up
without a refusal, a warning or a message that reads like a failure, and a follow that fails with a key the vault
lent says which key it was.

## Acceptance

- Porelo makes no directory writable by the group or others, whatever the umask; one that was there keeps its mode.
- A failed follow with an ssh key nobody declared names the vault entry and the `--agent` declaration.
- porelod starts and answers its own account without a sun.misc.Unsafe warning, in the native image too.
- `porelo setup` without a terminal on a vault with no passphrase slot says what opens it and refuses nothing.

## Done

- `wire.Directories.create` makes each missing directory and takes the group's and others' write from it; not every
  directory maker uses it: `OrgAccess.settle` and `ProjectFile.remember` call `Files.createDirectories`
  without removing group write, so the directory acceptance line remains unmet. `GitTreeStore.keep` sets its
  directory to `0700` after creation. A person's state home uses `Directories.create`
  (`DirectoriesTest`, red under umask 002 without the change).
  Natively on the Ubuntu VM, with umask 002 and a fresh home: 5 group-writable directories with 900, none with 955.
- `FollowCredential.impliedKeyNote` names a lent key; the follow command that appended it is gone
  (`FollowCredentialTest`;
  the declaration check seen red under a mutant). The agent does not win over the vault's key by itself: the
  message sluice reaches its own repository with the vault's `git.ssh.github.com`.
- `SocketPeer.read` asks `ExtendedSocketOptions.SO_PEERCRED` and compares the caller with the socket's owner; the
  pid, read by nothing, is no longer answered (`SocketPeerTest`). Natively on the VM: porelod 955 answers `doctor`
  and `agents` of its own account, and its journal holds no Unsafe line. A caller of another account was not tried
  natively.
- `SetupCommand` says what opens a vault without a passphrase slot and does not try to open it
  (`SetupCommandTest`, red first); `vault login` and the passphrase commands still name `porelo host unlock`.
- Before the registry commands were removed, `registry joinable` took a `unix:` socket path as
  `transports.matrix.homeserver`
  (`AddressForm.isUnixSocket`; `RegistryJoinableCommandTest`, red first, the segment check seen red under a
  mutant). A project with an access file names its conversation with a peer at `matrix:` in `project.yml`
  (documented); left: whether such a project should take its conversation from the applied access file's
  `transports` instead, which every reader of `Mail.conversations()` would have to learn.
- Before the registry commands were removed, `registry verify` answered every other card under `people/` whose history
  leads back to the first key filed for
  its login, after the admins; one that does not verify is left out, since the host keeps a login answered revoked
  revoked for good (`RegistryVerifyCommandTest`, red first; the chain check for an unpinned card seen red under a
  mutant). The host refused the test reviewer as "not named in the host's applied registry", which named its admin
  alone.
- Built during the stopped walk with unit tests only; the reading follows after the walk.
