# B112 — A Nickname For Each Agent

**Status:** Estimated.
**Size:** M - the label's rules, name resolution, the agent card, and two transport verbs a contract already names.

**What must be true.** A person can call each agent by a nickname given when its work starts: it is
shown in the window, the room and the direct chat, named with `@`, and the agent knows when a
message names it by either its nickname or its task.

## Why

From walk 10; this is Sokar's side of the window's [F88](https://github.com/sokar-ai/sokar-frontend/blob/main/issues/README.md#f88) in sokar-frontend. Decided: Later.

## The shape

Each agent has a nickname, given when its work starts, shown in the window, the room and the direct chat, and named
with `@`. Sokar's part:

- **The nickname is the task's label.** `@label` in the room resolves to that task, as its task name does now.
- **`agent-card.json` says who the agent itself is:** `"you": {"task": …, "nickname": …}`, so it knows when a message
  names it by either.
- **The display name in the conversation** goes through the transport: `enroll --display` at enrolment and
  `rename --display` when the nickname changes. Only a transport whose `describe` says `"display": true` is asked.
  `sokar-message-matrix` has both.
- **`metadata.to` and `metadata.from` stay task names**, so the filter and every record keep one name per task.

## Plan

Read in the code on 2026-10-11:

- **A label is a free caption today**: `TaskControl.label` (`apps/app-task/`) writes any text into the task's
  profile (`TaskProfile.withLabel`), spaces included, and nothing keeps two tasks of a project apart by it. A
  nickname that `@` names has to be one word and one task's.
- `TransportConversations.acting` maps each task of the project by its name and its short name to its account
  (`lifecycle.addresses`), which is whom a message mentions; a person's `@` in the room resolves through it.
- `MailboxGuide.write` writes `agent-card.json` as `{"peers": [...]}` only.
- `TransportDescription` reads `describe`'s flags into `takes`; nothing reads `display`, and `TransportLifecycle`
  has no `rename`.

Steps:

1. **The label as a nickname**: `TaskControl.label` refuses a label that is not one word of the characters a task name
   takes, or that another task of the project has as its label or its name; there is no label that is only a caption.
   The refusal names the rule the label breaks - not one word of the characters a task name takes, already the label of
   `<task>`, or already the name of `<task>` - and nothing is written. A label set before the rule that breaks it is
   shown, and is no nickname until it is changed. Red first: a test labelling two tasks of one project alike.
2. **`@label` reaches the task**: `TransportConversations` adds each task's label to the map beside its short
   name; a message's `metadata.to` is still written with the task's name (`MessageDispatch`). Red first: a test
   whose room message names `@label` and reaches no task.
3. **The card**: `MailboxGuide.write` adds `"you": {"task": ..., "nickname": ...}`; the guide says a message names
   it by either. Red first: `MailboxGuideTest` reading `you`.
4. **The display name**: `TransportDescription` reads `"display": true`; `TransportLifecycle.enroll` passes
   `--display <label>` and a new `rename(scheme, project, task, display)` runs the transport's `rename` verb when
   the label changes (from `TaskControl.label` and the daemon's `Label`). Red first: a fake transport that does
   not declare `display` and is asked anyway.
5. `doc/messages.md` and the room's help say the nickname.

## Acceptance

- `@label` in the room reaches the task whose label it is, as `@` with its task name does.
- `agent-card.json` carries `"you": {"task": …, "nickname": …}`.
- The transport is asked to set the display name at enrolment and when the nickname changes, and only when its
  `describe` says `"display": true`.
- `metadata.to` and `metadata.from` carry task names, never a nickname.
- A label with a space, or one another task of the project has as its label or name, is refused with the rule it
  breaks, and the task keeps the label it had.
- **Seen to fail:** a test that writes `@label` in the room goes red when the task is not reached; a test of the
  card goes red when `you` is missing either name; a test with a transport that does not declare `"display": true`
  goes red when it is asked; a test of a message to a nickname goes red when its metadata carries the nickname; a
  test labelling two tasks alike goes red when the second label is written.

## To be checked

- Whether a nickname must also be unique across a project's tasks on other machines, which this machine cannot
  see (Agent Core's, with [B86](README.md#b86)).
