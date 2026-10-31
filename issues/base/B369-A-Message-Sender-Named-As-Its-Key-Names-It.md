# B369 — A Message Sender Named As Its Key Names It

**Status:** Review.
**Size:** S, proposed - two reserved task names, one comparison of the sender a message names, one peer name rule and
one command's project, each with its test.

**What must be true.** A task's message is believed for the sender its key traces to and held only when it names
another; no task can be started under a name Porelo reserves for a person or the room. A second reading of
[B344](README.md#b344) on 2026-10-31 found seven places, and Agent Matrix one more, where this does not hold yet:

- A task may be named `people`, by hand or after a repository of that name; its key is then certified for a
  principal the reader refuses, and every message it sends is held.
- A task may be named `person`, the sender Porelo writes for a person's own note, so its messages pass as a person's.
- `porelo talk pass` with `--project` for a removed task of an ambiguous project name checks it against nobody and
  holds every message, although the project was given.
- A message whose `metadata.from` is `null` is held as naming another sender, where it names none.
- A task writing its own name is held by a peer in another project, which traces it as `task:<project>:<task>`, and
  one writing that principal is held by its own sibling, which traces it as `<task>`: no name passes both.
- A project file's `mail.peers` cannot name a task of another project as `task:<project>:<task>`, the form the
  reader traces and `doc/messages.md` documents. A peer name takes `:` in that one form only, `task:`, a project name
  or `default`, `:` and a task name, as Agent Sluice's filter takes it (agreed in the room on 2026-10-31).
- The login a transport is given passes as `[A-Za-z0-9][A-Za-z0-9_-]{0,31}`, wider than every other login rule of
  Porelo and than the transports', `[a-z][a-z0-9-]{0,31}`; another login is refused before any transport runs.
- `MessagingMethodsTest` never checks that `join` or `setup` is given `--login`, only that `part` is not.

Each is answered by a test that fails today.

## Done

- `people` and `person` are no task names: a start refuses them before anything is built, an unnamed start on a
  repository of either name is named `work`, and a key certified for `task:<project>:people` or
  `task:<project>:person` is held as certified for no one task, by the same rule.
- `porelo talk pass --project` takes that project's task, by its task name or its container's, never another
  project's task of the same name, and refuses (66) a running container of that name another project's label names;
  it takes that project's certifiers, conversation, peers and holds, never ones derived again from the container's
  name.
- A message whose `metadata.from` is `null` names nobody, as one without it does.
- A message's `metadata.from` passes when it is the peer its key traced to or that key's whole principal,
  `task:<project>:<task>`, so the principal passes in its own project and in every other; anything else is held.
- A peer name in `mail.peers` is what it was, or exactly `task:`, a project name, `:` and a task name a start can
  make: not `people`, `person` or only digits, and with `porelo-<project>-<task>` at most 64 characters. It is one
  regular expression, `Mail.Peer.NAME_PATTERN`, which another filter can copy as it is, and a test holds it to the
  task start's own refusal over every boundary. The project and task name rules are one constant each, shared by the
  project file, the task start and the certificate check.
- A transport is given a login of `[a-z][a-z0-9-]{0,31}` only, as every other login rule of Porelo; another is refused
  with 78 before any transport runs.
- The daemon's `SetUpMessages` and `JoinMessages` are tested as the daemon answers them: each gives the transport
  `--login` with this account's login.
- The tests of `talk pass --project` deliver a certified message of the built-in project, whose certifier is who
  certified the task's own key, through a filter that accepts as the real one does; they leave another project's
  live mailbox as it was and ask the given project's conversation.
- Not claimed: a named project's certifiers need a verified access commit the unit tests do not build, so the
  certifiers of a named project given to `talk pass` are not held by a test of their own; neither are the peers and
  holds it takes. A task already named `people` or `person` before this change is not renamed; its messages are held
  as before.
