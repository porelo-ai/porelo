# B455 — A First Start Says What A Plugin Could Not Place

```json
{
  "$schema": "issue-1.json",
  "id": "B455",
  "title": "A First Start Says What A Plugin Could Not Place",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 95,
  "blockedBy": [],
  "covers": "A first start's question shows what a plugin could not place and its notes."
}
```

**Why this size.** two fields on the wire, the terminal's lines, their tests; Agent Frontend then shows them.

**What must be true.** What a plugin says for a person to read before a yes reaches that person, at the terminal and in an interface.

The operator's word, 2026-11-05: built before the test.

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

## As built

As asked. A note sits on `Finding` as `note` and on the answer as `notes`, for every outcome. The scenario shows it at the terminal, and
`FirstStartMethodsTest` over the socket; what an interface shows of it is Agent Frontend's.

A reading added three: what a plugin says of a host goes among `notes`, two plugins agreeing keep both their words, and a draft that cannot
be made still says what could not be placed. A note in a draft's comment, by [B452](README.md#b452), is written, so that one does count in
the hash.
