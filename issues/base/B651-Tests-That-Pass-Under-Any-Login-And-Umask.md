# B651 — Tests That Pass Under Any Login And Umask

```json
{
  "$schema": "issue-1.json",
  "id": "B651",
  "title": "Tests That Pass Under Any Login And Umask",
  "state": "Estimated",
  "size": "S",
  "priority": "Medium",
  "rank": 76,
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
