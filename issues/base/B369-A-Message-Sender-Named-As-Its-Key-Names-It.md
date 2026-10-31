# B369 — A Message Sender Named As Its Key Names It

**Status:** Idea.
**Size:** S, proposed - two reserved task names, one comparison of the sender a message names, one peer name rule and
one command's project, each with its test.

**What must be true.** A task's message is believed for the sender its key traces to and held only when it names
another; no task can be started under a name Porelo reserves for a person or the room. A second reading of
[B344](README.md#b344) on 2026-10-31 found seven places where this does not hold yet:

- A task may be named `people`, by hand or after a repository of that name; its key is then certified for a
  principal the reader refuses, and every message it sends is held.
- A task may be named `person`, the sender Porelo writes for a person's own note, so its messages pass as a person's.
- `porelo talk pass` with `--project` for a removed task of an ambiguous project name checks it against nobody and
  holds every message, although the project was given.
- A message whose `metadata.from` is `null` is held as naming another sender, where it names none.
- A task writing its own name is held by a peer in another project, which traces it as `task:<project>:<task>`, and
  one writing that principal is held by its own sibling, which traces it as `<task>`: no name passes both.
- A project file's `mail.peers` cannot name a task of another project as `task:<project>:<task>`, the form the
  reader traces and `doc/messages.md` documents.
- `MessagingMethodsTest` never checks that `join` or `setup` is given `--login`, only that `part` is not.

Each is answered by a test that fails today.
