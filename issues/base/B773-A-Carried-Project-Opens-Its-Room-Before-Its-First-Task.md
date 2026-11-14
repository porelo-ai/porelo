# B773 — A Carried Project Opens Its Room Before Its First Task

```json
{
  "$schema": "issue-1.json",
  "id": "B773",
  "title": "A Carried Project Opens Its Room Before Its First Task",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 130,
  "blockedBy": [],
  "covers": "A project a host carries for a person and not taken on this machine yet lists its conversation, says why it is not ready, and is set up on the person's word without a task",
  "openQuestions": 0
}
```

**Why this size.** M: the daemon's project row, `SetUpMessages`'s way to a carried project, and their tests,
with a line in the docs and the contract's comments. No shape another repository reads changes, only rows that were
absent are filled. Estimate: 63 minutes (Core's median for M).

**What must be true.** Right after a host carries a project for a person, the person's window shows the project's
room and can open it, without starting a task first. Until it is set up, the row says why it is not ready. A project
file that cannot be read never makes the row lose its conversation without a word.

Filed 2026-11-14 from the operator's live test on a rented host (porelo 0.5.1~snapshot.543), under ST361.

## Why

Seen: after the host carried project porelo (host-projects.json: carried, room set, homeserver set), the window's
"Open the project's room" stayed disabled with "porelo has no conversation: none of its peers is reached through a
transport of its own". Before the hand-over it worked: the person had the project's file taken here.

Read in today's code: `PoreloDaemon.projectRow` returns a row without `messages` as soon as the summary has no file.
A project the host carries and the person has not taken here is listed from the host's read with no file
(`ProjectInventory.projects`, `hosted`), so it never has `messages`. A `RuntimeException` while reading a file is
swallowed in the same method and also drops `messages`. `SetUpMessages` reads the project through
`MessagingMethods.talking` → `conversational`, which needs the file taken here, so it refuses a carried, untaken project.

## Plan

1. `projectRow`: a carried, untaken row with a host read (`OrganizationProjects.Placed`, with `homeserver` and
   `room`) gets `messages`: transport `matrix`, `conversation` the host's room, `reaches` empty, ready false, admitted
   false, and the detail that it is set up when the person sets it up or the first task starts.
2. `projectRow`: a file that does not read gives `messages` with ready false and the error, bounded and printable, as
   the detail, instead of none. A project whose file reads and has no conversation still has none.
3. `SetUpMessages` on a carried, untaken project takes it first, as a start does (`ProjectTaking.take`), then
   sets the conversation up against the host's homeserver and room as today. A refused take answers
   `ConversationRefused` with its message.
4. Contract comments (`ProjectMessages`, `SetUpMessages`) and `doc/messaging.md` say so; a changelog entry.

The window's half (Agent Frontend): a row whose `messages.ready` is false with a host room offers "Set up the room",
calls `SetUpMessages` and opens the room when it answers ready.

## Built

- `Conversations.ofProject(context, summary, hostFiles)` (app-messaging) gives a listed project's `messages`.
  For a carried, untaken row it reads the host's file (`OrganizationProjects.host`) and gives the host's room, ready
  and admitted false, with a detail saying to set it up or start the first task. A host file naming no homeserver
  gives none. A project file or host file that does not read gives one not ready; its detail names
  `porelo project check`, never the error's words. A file that reads with no conversation still gives none.
- `PoreloDaemon.projectRow` calls it with `OrganizationProjects.HOST_FILES`, and no longer swallows an exception.
- `MessagingMethods.setUpMessages`: for a project not taken here as the organization's (`ProjectSource` other than
  ORGANIZATION), it takes it first through `ProjectMethods.taken`, as `TakeProject` does. A refused take answers `ConversationRefused`
  with code 69 and the take's words. A project no host carries goes on as before (`NoSuchProject`).
- Contract comments (`Project.messages`, `SetUpMessages`), `doc/hosts.md`, changelog
  `carried-project-room-before-first-task.yml`.

Tests: `ProjectRowConversationTest` (apps/app, four cases) and two cases in `MessagingMethodsTest`. Red first was
a compile failure (no `ofProject`, no `setUpMessages` with a taker), not a failing assertion; the old
`projectRow` returned no `messages` for a row with no file, as the first case expects it now.

### Reading (Codex gpt-6.1-sol, one reading)

- Red, fixed: the setup skipped the take where a file of the project's name was written here (`RECORDED`), which
  the listing calls carried. It now takes unless the project resolves as the organization's.
  `aSetupTakesACarriedProjectEvenWhereAFileOfItsNameWasWrittenHere`, seen failing first.
- Red, fixed: the detail of a file that does not read carried the refusal's words, which quote the refused value -
  a password in a builds address. The detail no longer carries the error.
  `aRefusedValueInTheFileNeverReachesTheDetail`, seen failing first.
- Yellow, fixed: the no-homeserver test wrote no host file; it now writes one with `homeserver: null`. The contract
  and `doc/hosts.md` name the homeserver condition.
- Yellow, left: a taken project whose placement record is malformed resolves UNKNOWN and is taken again, which
  fetches before it refuses; a successful take through to setup is shown on the VM, not by a unit test.

## Red-first test

- A daemon test lists a carried, untaken project with a host room and expects `messages` with the host's room and
  ready false; today the row has none.
- A row whose file does not parse carries `messages` with ready false and the error.
- `SetUpMessages` on a carried, untaken project takes it and answers its messages; today it refuses.

## Risks

- Taking a project on a room button's press writes its file here, as a first start would. The button says so.
- The host's room is the person's to join only through the host's gate; a setup the transport refuses answers as
  today, never half set up.

## Blockers

None.

## Acceptance

- The red-first tests pass; the daemon's project and messaging tests still pass.
- On the VM or the host: a carried project's window row shows its room right after the hand-over, and one setup
  opens it.

## Open questions

None.

## To be checked

- That a project taken by the setup lists with its file and its conversation from the next answer on.
