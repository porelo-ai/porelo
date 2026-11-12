# B380 — Small Edge Cases Left By Readings And Fixes

```json
{
  "$schema": "issue-1.json",
  "id": "B380",
  "title": "Small Edge Cases Left By Readings And Fixes",
  "state": "Analyzed",
  "size": "L",
  "priority": "Medium",
  "rank": 44,
  "blockedBy": [],
  "covers": "The small edge cases readings and fixes left, gathered to be placed; test SSH-agent cleanup done",
  "openQuestions": 2
}
```

**Why this size.** L, analyzed 2026-11-12 (ST56): about a dozen S fixes across app-model, app-task, app-messaging,
app-organization and the tests, each with its own red test. They are built as three issues of M at most (below). Before:
one gathering issue for the small edge cases that readings and fixes left, since
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
- Low: a project's name written by hand at 55 characters or more leaves a task so little room that even the default
  `work-2` does not fit; the start then refuses with the sentence that names the room, or that the project's name is
  too long, rather than choosing a name.
- Low: the refusal of a task name with characters no task may have suggests a lowercased one checked only against the
  pattern, so it can suggest a name another rule refuses: digits only (`123 ` suggests `123`), `people`, one beginning
  `porelo-`, or one too long for the project.
- Low: a start wrong twice, an unknown `-r` and another refusal, hears whichever check comes first, and the unknown
  name exits 64 where the other would exit 2; both refuse before anything is made. Found by [B491](README.md#b491)'s reading.
- Low: on a host whose builds come from its file, a start with the vault locked and an agent that takes no credential
  cannot see whether the vault holds the builds entry, so the helper starts and, once the vault opens without it,
  says to store it with `porelo vault put` instead of naming `porelo-host-forge-token`. Found by [B493](README.md#b493)'s reading.
- Done, for the tests only: tests in `apps/app` left their `ssh-agent -D` processes running after a failure or
  an interrupt; 43 were found on the host, the oldest 21 hours old. One shared helper now stops each test's
  agents and their descendants in `afterEach` or `close`, with a JVM shutdown hook as the last resort.

## Plan

Triaged 2026-11-12. Each point is built in one of three groups, or dropped once the operator agrees.

| Group | Points | Size | First red test |
|---|---|---|---|
| A: signers and signatures | lone `\r` in an allowed-signers file; `U+2000` before `#`; a line ssh-keygen cannot parse refuses the whole file; a backup key in `ProjectMembers.certifiers`; a task's key signing under another task's certificate | M | `AllowedSignerTest`: `alice\rssh-ed25519 K` reads as one line, as ssh-keygen does |
| B: task names and starts | an unrecorded `podman create`; the `--no-gate` census; a project name of 55+ characters; the lowercased suggestion that another rule refuses; two refusals at once (64 against 2); the builds entry when the vault is locked on a host | M | `TaskRunCommandTest`: a failed record after create removes the container it made |
| C: tests only | `TransportConversationsTest` and `MachineTransportsTest` under a login like `Alex`; `DirectoriesTest` under umask 002; `SocketPeerTest` with a second uid (tagged, VM) | S | the two transport tests run under a login named `Alex` |
| Drop, with why | the scp address `host:a@b:c` (cannot be told from `user:password@host:path` by the address; documented instead); a name joined before the rule (the transport never made such an account); the three open commit-signature cases (each refuses what git accepts and accepts nothing git refuses: fail closed); `Directories.create` with `..` (every caller passes a clean path); `forgeToken` with an unset variable (asks with no token: it fails, it lends nothing) | - | - |

The done point (`ssh-agent -D` processes) is deleted from the list.

## Open questions

1. **The drops.** Should the five points under *Drop* be decided not worth it, as this issue's rule allows?
2. **Split or one.** Should groups A, B and C be filed as their own issues and this gathering issue closed, or stay one
   issue built in that order?
