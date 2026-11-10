# [B594](README.md#b594) — A Room For Default Is Refused By Name

**Status:** Review. Filed 2026-11-10 in `porelo-project` ST30 (Work without a project, with plain refusals), porelo's part of its PJ14.
**Size:** S - one refusal, said where a conversation or a peer is set up for `default`, in egress's words; its tests.
**Blocked by:** -.

**What must be true.** A transport or a peer for project `default` is refused, naming a project repository as the
way, as a change to `default`'s egress already is (`EgressControl.DEFAULT_REFUSED`). Today a conversation set up
for `default` is accepted, and a task's enrolment skips `default` without a word.

## Plan

Read in `TaskConversations` (`setUp`, `enrolling`'s skip of `default`), `TalkPeersCommand`, the daemon's
`SetUpMessages`/`JoinMessages`/peer methods and `EgressControl`.

- One refusal text beside `DEFAULT_REFUSED`'s: `default` talks to nobody; a project repository is the way, with its
  project.yml. `TaskConversations.setUp` and `TalkPeersCommand` refuse with it (exit 78, as a refused setting),
  before any transport runs; the daemon's methods answer it as `ConversationRefused`.
- A start in `default` keeps starting without a room; its start steps say once that `default` has no room, instead
  of skipping without a word.
- Red first: a conversation set up for `default` is accepted today (`TaskConversationsTest`); a peer added for
  `default` likewise; the daemon's `SetUpMessages` for `default`.

## Acceptance

- `porelo talk setup default` and a peer for `default` are refused with the words above and run no transport; a
  start in `default` says it has no room and starts.

## As built

- **The refusal is `DefaultProject.NO_CONVERSATION`** in `apps/app-model`, beside `DefaultProject.NAME`, in
  `EgressControl.DEFAULT_REFUSED`'s manner. `apps/app-egress` and `apps/app-messaging` both build on
  `apps/app-model` and neither on the other, so that is where `TaskConversations`, `TalkPeersCommand` and the
  daemon reach one text without a new module dependency.
- **`TaskConversations.setUp`, `join` and `part` refuse `default`** before any transport runs, as a refused
  setting (78); `part` hands it on as `Conversations.Refused`.
- **`porelo talk peers -p default` is refused (78)** by name, before the project's file is read.
- **The daemon refuses `default` by name** in `SetUpMessages`, `JoinMessages`, `RemoveMessageMember`,
  `AdmitMachine` and `DismissMachine` as `ConversationRefused`, code 78, before the project's file is read or any
  transport runs; `MessageMembers` and `RoomMembers`, which only read, keep their answers.
- **A start in `default` says once that it has no room**: `StartSteps.noConversation` starts and completes the
  conversation step with `DefaultProject.NO_ROOM` as its text, so no failure and no wait is shown; no state and no
  field of the start step's contract changed.
- **Left as they are**: `porelo talk join`, `talk remove` and `talk members` for `default` still answer, as for any
  project without a conversation, that it has none (64), since the built-in file names none and no transport is
  reached.
- Tests, each seen red first: `TaskConversationsTest` (a conversation set up for `default`, and the start's step),
  `TalkPeersCommandTest` (peers for `default`), `MessagingMethodsTest` (`SetUpMessages`, `JoinMessages` and
  `RemoveMessageMember` for `default`); every refusal asserts that no command ran.
