# B380 — Small Edge Cases Left By Readings And Fixes

**Status:** Idea.
**Size:** -, not yet placed - one gathering issue for the small edge cases that readings and fixes left, since
none of them reaches another person's key, card, socket, mailbox or container, shows or lends a credential, trusts a
key wrongly or loses data. Each one placed is split out with its own size.

**What must be true.** Each point below holds, or is decided not to be worth it.

- An scp address without a user, `host:a@b:c`, is cut like `user:password@host:path` to `b:c`, though git connects to
  `host` with the path `a@b:c`; the two spellings cannot be told apart from the address alone. Found by the fix of
  the address check for a hidden credential.
- Low: if `podman create` succeeds but recording its id fails, the container Porelo made stays without a record;
  `task remove` cannot reach it and the name stays taken. Found while fixing
  a failed start's cleanup.
- Low: with `--no-gate`, `/workspace` is never a git checkout, so a stopped no-gate task's census reads as unknown
  and `task remove` needs `--force` (read in `UnhandedWork.CENSUS`, not measured). Found while fixing
  a failed start's cleanup.
- Medium for the tests only: `TransportConversationsTest` and `MachineTransportsTest` still expect the machine's
  own login to be one a transport takes, so they fail under a login such as `Alex` or `runner_1`. Found while
  fixing the mailbox check.
- Low: a person recorded as joined under a name outside the rule, by a join before that check, can no longer be
  removed through `talk remove`; the transport never made such an account. Found while fixing
  the mailbox check.
- Medium: Porelo reads a lone `\r` as a line end in an allowed-signers file, but ssh-keygen reads
  `alice\rssh-ed25519 K` as one line, so Porelo refuses a file git reads. Found while fixing the signers file reading.
- Low: a line ssh-keygen cannot parse is skipped by git, while Porelo refuses the whole signers file (66).
  `AllowedSigner` trims only spaces and tabs, so `U+2000#x ssh-ed25519 K` is not skipped as a comment.
  The whole-file refusal remains fail closed. Found while fixing the signers file reading.
- Low: task and message certifiers both come from `ProjectMembers.certifiers`; the earlier difference between
  `TaskCertifying` and `MessageSigners.certifiers` is gone. Check that a backup key cannot enter that list or
  certify either. Left when the task-key messages met the backup keys that are never pinned.
- Low: a task's own key is still used to sign when its certificate names another task.
- Low: three cases of a commit signature are left open and not measured: a commit in another `encoding`, every
  `gpgsig` header appended, and text after the armor's end or a non-empty reserved field. As the code reads, each
  refuses a commit git accepts and accepts none git refuses.
- Low: on an https origin with a host-wide token in the vault and a more specific one declared in a variable that is
  unset, `OrganizationAccess.forgeToken` asks ownership with no token rather than the vault's.
- Low: `Directories.create` walks a path with `..` after a missing part as it stands, so such a path could take the
  group's write from a directory that was there; every caller passes a clean path.
- Low, for the tests only: `DirectoriesTest` and the tests of the directories made show a fault only under a umask
  that leaves the group's write, such as 002; `SocketPeerTest` cannot show a peer of another account, which takes a
  second uid.
- Done, for the tests only: tests in `apps/app` left their `ssh-agent -D` processes running after a failure or
  an interrupt; 43 were found on the host, the oldest 21 hours old. One shared helper now stops each test's
  agents and their descendants in `afterEach` or `close`, with a JVM shutdown hook as the last resort.
