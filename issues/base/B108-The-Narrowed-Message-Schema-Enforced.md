# B108 — The Narrowed Message Schema Enforced

**Status:** Analyzed.
**Size:** M - one schema document both sides read, the guide made from it, and the filter given it: a contract.

**What must be true.** An agent in a task can send only messages of the narrowed schema [B14](README.md#b14)'s design describes, and
the guide it reads tells it exactly that schema, not a looser one.

## Why

[B14](README.md#b14)'s design narrows A2A 1.0 for Sokar's messages, and nothing enforces it; decided that it is to
be enforced. The mailbox guide (`/run/sokar/mail/README.md`, in `doc/messages.md`) tells agents the looser rules that are enforced today. The filter's side, if it checks it, is
`sokar-message-sluice`'s.

## The shape

- The narrowed schema of [B14](README.md#b14)'s design, enforced where a message leaves a task:
  - `metadata` holds `kind`, `to`, `from`, `thread` and nothing else; `kind` is one of `question`, `answer`,
    `review-request`, `handover`, `status`, each with its fixed data part.
  - Exactly one text part within the peer's limit, at most one data part; no file, raw or url part.
  - `extensions` exactly a Sokar messaging extension URI, which has to be defined first.
- The mailbox guide and `doc/messages.md` say it the day it is enforced, from the same constants.

## Plan

Read in the code on 2026-10-11:

- `MessageIntake` (`apps/app-messaging/`) takes a message from a task's outbox, reads it once up to 1 MiB, holds
  one whose role claims a person, and signs the rest; it leaves a missing or nonsense role "to the filter, which
  answers the sender properly". `MessageFiltering` runs the filter (`--mail <root>`), and only what it passes is
  released. Whom `metadata.to` may name is decided by the host at dispatch.
- **The guide already asks for a key the narrowed schema forbids**: `MailboxGuide` tells an agent to write
  `metadata.via` (`"room"` or `"direct"`), which the transports read to answer in the room or the direct chat.
  [B14](README.md#b14)'s table lists `kind`, `to`, `from` and `thread` only. And a person's `metadata.from` in the room is a
  Matrix id. The schema is written with what is sent today, or the guide changes with it.

**Which side checks what** - the answer for the filter's [SL23](https://github.com/sokar-ai/sokar-message-sluice/blob/main/issues/README.md#sl23): **the filter checks the shape, the host does not
check it again.** The envelope is the filter's (its decisions say so), the host's own checks stay what only the
host can judge - the role, `to` naming one peer - and every message leaves a task through the filter, so a
check at the host first would refuse the same message with other words and no answer to the sender.

**Where the schema lives**: one JSON Schema document in this repository,
`apps/app-messaging/src/main/resources/org/fuin/sokar/app/message.schema.json`, installed with the packages at
`/usr/share/sokar/message.schema.json` and given to the filter as `--schema <path>` by `MessageFiltering`.
The filter's tests read the copy published with the Sokar release they build against. The extension URI is a
constant in it.

Steps:

1. **The document**, with `via` decided (To be checked) and the five kinds' data parts. A test in this tree reads
   it and the guide: red first, since `MailboxGuide.text()` today lists no kind and asks for no `extensions`.
2. **`MailboxGuide` and `doc/messages.md`** made from the document's kinds, keys and limits; the comparing test
   goes green.
3. **`MessageFiltering`** passes `--schema` once the filter takes it ([SL23](https://github.com/sokar-ai/sokar-message-sluice/blob/main/issues/README.md#sl23)); until then a filter that does not
   know the option is not given it, so nothing breaks in between.
4. **`MessageSay`** (`talk say`) writes the narrowed shape itself, `--kind` among the five.
5. **The acceptance scenario** in `talk-commands.feature`: one message of each refused shape, each held with
   the filter's rule named.

## Acceptance

- A message whose `metadata` holds a key other than `kind`, `to`, `from`, `thread`, or whose `kind` is not one of
  the five, or whose data part does not match its kind, is refused where it leaves the task. Seen to fail: a test
  sending each such message is accepted.
- A message with no text part, more than one text part, a text part over the peer's limit, more than one data part,
  or any file, raw or url part, is refused. Seen to fail: a test sending each such message is accepted.
- A message whose `extensions` is not exactly the Sokar messaging extension URI is refused. Seen to fail: a test
  sending a message with no or another extension URI is accepted.
- The mailbox guide and `doc/messages.md` state the narrowed schema from the same constants the check uses. Seen to fail:
  a test comparing the guide's kinds and limits with the enforcing constants finds a difference.

## To be checked

- Whether `metadata.via` joins the narrowed schema, as the guide and the transports use it now, or room and
  direct are told another way (Agent Core's, with sokar-message-sluice's agent).
- How a person is named in `metadata.from`: their Matrix id, or a plain name the host maps (Agent Core's, with
  sokar-message-sluice's agent).
- The Sokar messaging extension's URI (Agent Core's).
- Whether a message from before is refused or converted: [SL23](https://github.com/sokar-ai/sokar-message-sluice/blob/main/issues/README.md#sl23) refuses it, since the filter never rewrites a
  message; whether the host converts what sits in an outbox on the day of the switch (Agent Core's).
