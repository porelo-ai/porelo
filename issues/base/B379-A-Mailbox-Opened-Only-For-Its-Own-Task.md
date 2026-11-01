# B379 — A Mailbox Opened Only For Its Own Task

**Status:** Review.
**Size:** M, proposed - the task given to `talk pass` checked as a task name, a removed task's project read from its
own record, the certificate's principal compared, every person given to a transport checked, task names that start
with `porelo-` refused, and the daemon's two methods tested through the daemon, each with its test.

**What must be true.** `porelo talk pass` opens only the mailbox of a task it can name and of the project that task
belongs to, and believes a key only for the task its certificate names. A second reading of the fix of
[B369](README.md#b369) on 2026-10-31 found six places where this does not hold yet:

- The task given to `talk pass` is not checked as a task name: with `--project`, `fix/../porelo-other-secret` opens
  another project's mailbox and passes this project's conversation into it; without `--project`, a path that names no
  live task is taken as the mailbox's directory.
- A removed task's project is read from podman's label only: once its container is gone, its mailbox is opened under
  any project its name fits, and `MessageSigners` believes the key's certifier without comparing the certificate's
  principal with `task:<project>:<task>`.
- `talk join` and the daemon's `JoinMessages` pass a person outside `[a-z][a-z0-9-]{0,31}`, `-`-led ones among them,
  to the transport when the project has no access file, and `talk remove` passes it to `part`.
- A conversation's members are found by fitting mailbox names to project names, so a removed task's mailbox is given
  another project's messages, or a sibling's polled messages are lost when the name fits two projects.
- A peer `task:p:porelo-p-build` passes the peer rule and the start's task rule, though no start makes that task.
- `MessagingMethodsTest` calls the two new methods directly, not through the daemon, and its tests fail on a machine
  whose login is outside the transport rule.

Each is answered by a test that fails today.

## Done

- `porelo talk pass` refuses (64), before any mailbox is opened, a task that is no task name with `--project` and,
  without it, one that is no task's container name - `fix/../porelo-other-secret`, `../mail/...`, an absolute path.
- A removed task's project is the one its start recorded under the state directory, after the container's label and
  before any fitting of names: `talk pass --project` refuses (66) a removed task of another project, and a
  conversation's members are found by it, so a removed task of a project no longer followed is handed nothing of
  another project's room and a removed sibling whose name fits two projects keeps what its room held for it.
- A task of `default` believes the person who certified its own key only when that key is certified for that very
  task, `task:default:<task>`.
- `talk join`, the daemon's `JoinMessages`, `talk remove` and `RemoveMessageMember` refuse a person outside
  `[a-z][a-z0-9-]{0,31}` before any transport runs, with or without an access file.
- No task name begins with `porelo-`: a start refuses it, an unnamed start on a repository `porelo-pi` is named
  `pi`, a certificate for such a task names no one task, and `mail.peers` takes no `task:<project>:porelo-...`.
- `MessagingMethodsTest` asks `SetUpMessages`, `JoinMessages` and `RemoveMessageMember` through the daemon's socket,
  with a login of its own rather than the machine's, and holds that a login no transport takes is refused (78).
- Left: a task's own key is still used to sign when its certificate names another task.
- Measured on the Ubuntu VM: a stopped task named `porelo-old`, made by a build from before this fix, starts again
  after the update when given its task name with `--project`, and is then answered as already running when given its
  container's name; the refusal holds only for a new task.
