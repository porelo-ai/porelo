# B379 — A Mailbox Opened Only For Its Own Task

**Status:** Idea.
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
