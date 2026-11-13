# B651 — Tests That Pass Under Any Login And Umask

```json
{
  "$schema": "issue-1.json",
  "id": "B651",
  "title": "Tests That Pass Under Any Login And Umask",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 42,
  "blockedBy": [],
  "covers": "The transport tests pass under a login such as Alex or runner_1, the directory tests show their fault under umask 002, and a tagged VM test shows a peer of another account",
  "openQuestions": 0
}
```

Split out of the gathering issue for small edge cases on 2026-11-12 (ST56), by the operator's decision. Each point was found by a reading or a fix, and reaches no other person's key, card, socket, mailbox or container.

**What must be true.** A test's result does not depend on the login or the umask of whoever runs it:

- Medium for the tests only: `TransportConversationsTest` and `MachineTransportsTest` still expect the machine's
  own login to be one a transport takes, so they fail under a login such as `Alex` or `runner_1`. Found while
  fixing the mailbox check.
- Low, for the tests only: `DirectoriesTest` and the tests of the directories made show a fault only under a umask
  that leaves the group's write, such as 002; `SocketPeerTest` cannot show a peer of another account, which takes a
  second uid.

## Plan

- `TransportConversationsTest`, `MachineTransportsTest`: the machine's login comes from the test context, and a case runs under `Alex`.
- `DirectoriesTest`: the umask is set by the test (002 and 022), not inherited.
- `SocketPeerTest`: a tagged integration case on the VM with a second uid.
- First red: the two transport tests under a login named `Alex`.

## Acceptance

- The suite passes under login `Alex` and umask 002, and each test above fails first under them.

## Done

Built as planned.

- **The login.** `TransportLogin`, a JUnit extension, sets `user.name` to `porelo-test` for each test and restores it
  after. `TransportConversationsTest`, `MachineTransportsTest` and `TalkMachineCommandTest` run under it, so a
  transport's own login no longer comes from whoever runs them. Red first: under `-Duser.name=Alex` the two transport
  classes failed with 51 failures, and `TalkMachineCommandTest` failed too.
- **The umask.** `Umask.under(mask, body)` sets the process's umask through a foreign call to libc and restores it.
  `DirectoriesTest` runs its cases under 0002 and 0022 and holds that, under 002, the system alone leaves the
  group's write. Red first: without the strip of group and others' write, three cases failed under 002. The wire
  rule against foreign memory reads main code only, so the test's foreign call is not judged by it.
- **A peer of another account.** `SocketPeerOtherAccountTest` is tagged `second-account`. The root pom excludes it from
  every run, and the whole profile excludes nothing else (`WholeSuiteProfileTest`). It runs where
  `PORELO_TEST_AS_OTHER` names how to run a command as the other account; `doc/build.md` says how. On the test VM,
  run as one account with another reached over ssh on the loopback, its first run failed on the test's own
  expectation that the peer's uid is named. `SocketPeer` gives `-1` for anybody but its own account, by contract.
  The test now holds that and that the peer is not this account, and it passes there.
- **After the reading.**
  - The second-account test waits for the other account's connection for at most 30 s, then fails with what that
    account said.
  - `WholeSuiteProfileTest` pins the default exclusion to `slow` and `second-account` exactly.
  - `DirectoriesTest` is `@Isolated`, since the umask is the process's. `Umask.under` keeps the body's failure, with
    a failure to restore attached to it. The cases compare with the mode the file system kept, and skip where a
    default ACL decides the mode instead of the umask.
  - `TransportLogin` clears a login that was not set, instead of leaving it empty.
  - `TransportLoginExtensionTest` runs under `Alex` and fails without the extension (seen red).
  - The second-account test still runs only by hand, on a machine with two accounts.
- **Locally on this commit.** The whole reactor's `install` under umask 002 with the real login is green, with 9538
  tests. Under a faked `user.name` of `Alex`, all modules up to `git-tree` pass. Beyond them the only failures come
  from the faking itself: lookups of the owner by `user.name` (`TreeRepository`, `host whoami`, the daemon's
  hardening) find no account named `Alex` on the machine. A real login of that name has one, so the acceptance's
  "under login `Alex`" holds for the tests this issue names, not for a JVM told a name the machine does not have.

