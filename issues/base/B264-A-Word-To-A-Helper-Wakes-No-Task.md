# B264 — A Word To A Helper Wakes No Task

**Status:** Review, published in Build 40088562915 of 2026-10-24.
**Size:** S - one reader and the hand-out in `app-messaging`, a host-made answer through the transport's `send`, their
tests and two paragraphs of `doc/transports.md`; the field is the transport's.

**What must be true.** A person's message in a project's room that names only an account that is neither a task nor
a person, such as the room's administrator or its relay, wakes no task, and the person is told that nobody here
reads such a message. A message that names nobody at all still reaches every agent in the room, as today.

## Why

Seen in the operator's test on a rented host on 2026-10-23: the room's members included `porelo`, the homeserver's
administrator, and `porelo-relay-sprint2`, the account the transport reads the room with. The operator mentioned
`porelo` to ask who it was, and a task answered instead.

Read in the code: `PersonsWords.read` keeps a mentioned account in the message's `to` only when it is a task's
account or a person who joined, so a mention of a helper leaves `to` empty. A person's word to the room with an
empty `to` is meant for every agent there. A mention of the relay never reaches porelo at all, since the Matrix
transport strikes its own poller from `to` before it hands the message over. So porelo cannot tell today "names only
a helper" from "names nobody".

The Matrix transport's half is [MX35](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx35):
its goal is no helper account in view, or none needed at all.

## Decided

- **The transport marks the message**, as agreed with porelo-message-matrix: a person file carries `"helpers": true`
  exactly when its `to` named only the room's helpers (the administrator or the relay); `to` is then empty. The field
  is absent otherwise, never `false`. A file without it and with an empty `to` still reaches every agent.
- **The person is answered in the room, by the relay**, through the transport's own `send`: no task speaks and none
  wakes. The words are the operator's: "This is Porelo's technical account; it passes your messages on to the tasks
  and does not answer itself. Mention a task to reach it." The person is mentioned, so their client shows it.
- **The answer never comes back to a task**: the transport's `poll` hands over no event the relay itself sent, in the
  room as already in a direct chat (porelo-message-matrix's side, in
  [MX35](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx35)). Porelo cannot tell the
  relay's account, which is opaque to it, so the filter belongs where the account is known.

## Plan

1. **`PersonsWords.read`** reads `helpers`: `true` gives an outcome with no readers and the person's account to
   answer. Any other value is read as absent.
2. **`TransportConversations.handOut`** gives such a file to no task and hands it to **`HelperAnswer`** (new, in
   `app-messaging`). That writes a Porelo message from `porelo` holding the standard line and signs it with the key
   that signs task messages (`MessageIntake`'s, `SshSignature`). It runs the transport's `send` with the project's
   secrets, those `setup` gave (the relay's), with `--to` the project's conversation, `--shown` the standard line and
   `--mention` the person's account, as `TransportSend` builds them for a task. An option the transport does not take
   is left out.
3. **A failed answer is said and not retried**: the hand-out's lines name it, the person file is removed as it is
   today, and nothing is queued. The person can ask again, and no task was woken either way.
4. **`doc/transports.md`**: the person file's `helpers` field, and that Porelo answers such a line itself through
   `send`.
5. **Tests, red first:**
   - `PersonsWordsTest`: a file with `"helpers": true` and an empty `to` has no reader. Today every task reads it.
   - The same without the field still reaches every task, unchanged.
   - `TransportConversationsTest`, with the fake transport its tests already use: the hand-out of such a file runs
     `send` once with the project's secrets, the standard line as `--shown`, and the person as `--mention`; no task's
     inbox gains a file.
   - A `send` that fails leaves no file behind and wakes no task.
   - On the VM: the answer is seen in the room once, and no task's inbox gains it on the next poll.
6. A changelog line under *Changed*.

- **Build estimate=90**, like porelo [B265](README.md#b265) (S) and the send path of porelo
  [B259](README.md#b259)'s person messages, with one new class.

## Acceptance

- In a room, a person's line that names only `Porelo (administrator)` or `Porelo (delivery)` wakes no task, and the
  person sees the standard line from the relay, mentioning them.
- A line naming nobody still reaches every agent; a line naming a task reaches that task.

## To be checked

- That `send` with the relay's token is accepted by the Matrix transport: [MX35](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx35)'s first test, measured there before
  this is built.
- Whether `TransportSend`'s argument building can be shared with `HelperAnswer` rather than copied.
