# B628 — A Removed Task Leaves No Direct Chats

```json
{
  "$schema": "issue-1.json",
  "id": "B628",
  "title": "A Removed Task Leaves No Direct Chats",
  "state": "Review",
  "size": "XS",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "Retiring a task from a conversation removes what its direct chats were polled into, `transport/<scheme>/direct/<container>`, so a removed task leaves nothing of itself in the machine's state."
}
```

**Why this size.** one delete in `TransportLifecycle.retire`, one test.

Filed 2026-11-11 from a joint VM round on 47c946ff (host 192.168.122.108, tasks.log line 74): after `rehearsal-all`
was stopped and removed, the host kept
`$XDG_STATE_HOME/porelo/transport/matrix/direct/porelo-foo-project_rehearsal-all`. The round on aa7d65c3 left nothing.

**What must be true.** A removed task leaves no directory or file named after it under the transport state.

## As built

- **Where it comes from.** `TransportConversations.direct` creates `transport/<scheme>/direct/<container>` before it
  polls a task's direct chats; it does so only for a task that holds its own transport secrets. Nothing removes it:
  `TransportLifecycle.retire` forgets the task's secrets and frees its name, `clear` removes a project's files, and
  Prune sweeps project-keyed files only. The leak predates [B627](README.md#b627); it changed only `TaskLaunch`,
  which writes no transport state, so the round on aa7d65c3 most likely just never polled that task's direct chats
  before its removal. Not proven.
- **Fix.** `retire` deletes the task's `direct/<container>` tree after `release` frees its name, whether or not the
  task held secrets. The walk follows no link, so a link in it is removed, never what it points to. A tree that
  cannot be removed is refused, so the removal says so.
- **Test.** `TransportConversationsTest.aRetiredTaskLeavesNoDirectChatsBehind`: a task's direct directory with a
  file in it is gone after `retire`, its parent stays. Red on 47c946ff9, green with the fix.

## Acceptance

- The test above, seen red first.
- The rehearsal's `tasks` step reports `rehearsal-all is gone` on a host with this build.
