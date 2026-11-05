# B455 — A First Start Says What A Plugin Could Not Place

**Status:** Backlog.
**Size:** S - two fields on the wire, the terminal's lines, their tests; Agent Frontend then shows them.

**What must be true.** What a plugin says for a person to read before a yes reaches that person, at the terminal and in an interface.

Not in the test's version unless the operator says so.

## Found

Agent Ecosystems, reading what the Maven adapter says at a first start, and read here in the code:

- **`unplaced` stops at the proposal.** `FirstStart.java:642` takes the draft, the tools and the unknowns. `ProjectProposal.java:302` puts
  `unplaced` into an answer only `project init --json` shows. So the container warning the operator asked for, and what the adapter says
  of Maven extensions, reach nobody at the question.
- **A found value's `note` is dropped,** as `Found` has what, value and source.
- **`notes` do not reach the wire.** `Answer.notes` is printed at the terminal (`FirstStartAtTerminal.java:43`), but `asMap` leaves them out
  and `method FirstStart` has no such field. So an interface is not told that a plugin failed or was refused either: it sees
  `NO_PLUGIN` or `NOTHING_RECOGNIZED` with no reason.

## What Is Asked

- `Answer` carries each unplaced thing as a note, with its plugin's name, file, line and note, made printable.
- `Found` carries its note, printed beside it at the terminal.
- `method FirstStart` grows `notes: []string`, and `Finding` a `note`, for every outcome. An interface shows `notes` above the question, and
  each note beside its finding, before a yes.
- They are not in the hash: the file and what it names are what a yes takes, and a note changes nothing written.
- A scenario with the stub's `unplaced`, at the terminal and over the socket.
