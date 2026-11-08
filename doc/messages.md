# Messages between tasks

A task's agent exchanges messages with the other tasks of its project and with the people in the
project's conversation, through a mailbox in the task. Every agent reads everything said in the
conversation but what another task sends to the persons alone; a person names the one meant with `@` and its task's name, or writes to it in a direct
chat. What a person writes is not filtered; what an agent sends is. Every task that has a mailbox starts with its
agent told how it works: Porelo writes the text below to `/run/porelo/mail/README.md`, and an agent that
declares how it takes standing instructions gets it at every start, after the part on `/porelo/files` in the guide
to all of Porelo in the task ([what a task's agent is told](running.md#what-a-tasks-agent-is-told)). Whom the task can reach right now is
in `/run/porelo/mail/agent-card.json`, kept current by Porelo.

How closely messages are watched is set once, in the project file: see `mail.rules` in
[The project file](project-file.md). A person can still hold a peer's messages; nothing else is decided
per task.

The daemon moves every mailbox along every 60 seconds (`PORELO_MESSAGE_SECONDS`), and at once when a message lands in
a directory it watches. A directory the kernel will not watch - when the machine's inotify watches run out, or one
it cannot read - is reached by the timed pass alone, and the daemon's journal says so, naming it and why
(`message watch: not watching ...`). `porelo talk pass TASK` moves one task's messages at once.

What a person writes in the conversation is taken only from somebody who joined it, and reaches every task of the
project here, a task's own message never back to it; a direct chat reaches only the one task. A person is a peer by
the name they joined with, watched as the room is (`mail.rules.room`). An agent resting at its prompt is woken when
a message names it, is said to it directly, or is a person's word to the room that names nobody: one fixed line is
typed into its terminal, never while it asks a person something. A room message that names only a helper account -
the room's administrator or its relay - reaches no task and wakes none; the relay answers the person once in the room
instead. Only an agent that declares both what its screen
shows at rest and what it shows while it asks a person something is typed into: one whose screen cannot tell the two
apart is never woken, since the line could answer a question nobody sees is open, and finds its messages when it
looks, as its guide tells it. Unattended work that has ended has no terminal to wake; its messages wait for its next run.
The task's `inboxWaiting` field counts those waiting messages, including while it is stopped.

## Where the conversation lives

A project gets its conversation from a transport named under `mail.transports` in its
[project file](project-file.md), or by its host's file where the project file names no homeserver or room; for
Matrix, the project's conversation is one room.

```yaml
mail:
  transports:
    matrix:                                    # handed to the transport as written
      homeserver: https://matrix.example.org   # absent: your own homeserver on this machine
      ca_file: certs/intranet-ca.pem           # a CA the server's certificate is signed by
      tls_verify: on                           # off only for development
```

- **Without `homeserver`**, the conversation is on a homeserver of your own account, run as a user unit on a
  port Porelo picks and keeps. It needs no root, and no other user on the machine shares it.
- **An `offline` project** messages through a homeserver on this machine's loopback only; a declared
  homeserver anywhere else refuses its tasks before they start.
- **On a shared homeserver every account is a person's**, named by their login, whatever machine they sit at: the
  transport is given this account's login and suffixes each account it makes with it, so two people's tasks of the
  same name never meet on one name.
- **Each task gets an account of its own** when it starts, and the account is deactivated when the task is
  removed. Its token stays in your vault; the task never holds it and has no route to the homeserver.
- **The room is the boundary.** Every task of the project and every person in it reads everything said there.
  Two tasks that must not read each other's messages belong in two projects.
- **A person joins** with `porelo talk join PROJECT PERSON`, which makes them an account, invites it into the
  room and prints the login once for their client; `--into-vault ENTRY` keeps the person's access token in the vault
  instead and prints no password but the room's id, which is how an agent joins; `--display NAME` is the name the room
  shows for them, which is how others address them. `porelo talk members PROJECT` lists who joined and their
  accounts. `porelo talk remove PROJECT PERSON` removes them through the transport and, only after it
  succeeds, from the members list; it says why if their account stays. Their room messages no longer reach
  tasks, and tasks can no longer address them by their joined name.
- **Being in the room is not enough to be delivered.** A message reaches a task only if it is signed by a task's
  key that one of the project's certifiers certified, valid when the message arrives, and names one of that
  project's tasks; anything else is held and you are told, with the key named.

A transport is a package on the host; [writing a transport](transports.md) says what one answers.

## What the host guarantees

- **Every message is signed on the host by its task's own key**, never in the task: a detached OpenSSH
  signature in the `porelo-message` namespace over its exact bytes, made with the task's plain Ed25519 key, which
  Porelo holds outside the container. It is not your login key. The signature names only the key; the receiver
  looks up the key's certificate by its fingerprint, as `porelo task certificate <fingerprint>` answers it - what
  this machine kept when the task started, else what the host the task runs on keeps - and accepts the message
  only when one of the receiving project's certifiers issued that certificate and it is valid at the moment the
  message arrives, by the receiver's clock, whatever time the message claims. The certificate must name exactly one
  task, `task:<project>:<task>`, with names a project and a task can have. A task without a project accepts the
  person who certified its own key when that key is certified for this very task, and while the vault holding that
  is locked, what arrives for it waits; a task whose project file cannot be read accepts nobody. A task's project
  is the one its container is labelled with, else, once the container is gone, the one its start recorded, else
  its name when it fits one project alone; so a removed task keeps its own project's conversation and certifiers.
  A task with no certified key sends nothing: its messages are held and it is told; while only the vault is locked
  they wait. Anyone can check a signature with OpenSSH, given the task's public key as the certificate names it:

  ```
  echo "task <task key>" > allowed_signers
  ssh-keygen -Y verify -f allowed_signers -I task -n porelo-message -s <message>.sig < <message>
  ```

- **Trust is a property of the peer.** A message from a `vouched` peer is delivered once its key traces to a
  certificate as above; the peer is the task's name for a task of the same project, and its whole principal,
  `task:<project>:<task>`, for one of another project, which is also how `mail.peers` names it. A message's
  `metadata.from` may name its sender either way: by that peer name, or by the whole principal, which passes in
  every project; one that is absent or `null` names nobody. A message whose `metadata.from` names anyone else is
  held. No task is named `people` or `person`, the names the room's people and a person's own note go by. One
  from an `external` peer, or one the project does not list, is also put through the filter on the way in.
- **No order is promised, and nothing is delivered twice**: a message whose id already reached a mailbox is
  dropped and logged.
- **The mailbox lives exactly as long as the task**: a stop, a start or a reboot leave it as it was, and the
  task reads its own sent messages back from `sent/`. `porelo task remove` refuses while a message is unsent
  or undelivered; `--rescue` hands it over first, `--force` removes regardless.
- **What happened to each message is in a hash-chained record on this machine**, never its text in the log;
  `porelo talk verify` names the first entry that does not check out. Refused originals are readable only by
  your account and go when the task is removed.
- **A person's message is taken from outside what the task can see or write**, which is what lets it count as a
  person's; a task that writes a message claiming to be a person's has it held.

## What the agent is told

The text is the same for every task of one Porelo version, word for word:

## Your mailbox

This task has a mailbox at `/run/porelo/mail`. Through it you take part in your project's conversation:
with the other tasks of your project and with the people in it. Nothing else in this task can
reach them.

### Who reads what

- **Everything said in the project's conversation reaches every task of the project**, you
  included: what people write there and what other tasks send to a task. You read all of it.
  A task's message to `people` is for the persons alone: it reaches no task.
- **A message meant for you names you** in `metadata.to`: a person writes `@` and your task's
  name, or answers a message of yours. Act on what names you.
- **A person's message in the room that names nobody is meant for every agent in the room:
  answer it in the room.** It has `"via": "room"` and an empty `metadata.to`.
- A message that names another is not yours to answer, and another task's message that names
  nobody is the conversation around you, there to know.
- **A person can also write to you directly**, in a chat with you alone. Such a message has
  `"via": "direct"` in its `metadata` and is always meant for you.
- **Answer where you were asked.** A person who types in your terminal is answered in your
  terminal, never with a message. A message is answered with a message, by copying `via`
  from the message you answer into yours.
  A person's message from the room is answered to them by name with `"via": "room"`: it goes
  into the room and mentions them. A direct message is answered to them by name with
  `"via": "direct"`: it goes into your direct chat with them. A task's message is answered to
  that task.
- When a message for you arrives while you wait at your prompt, a line may appear there saying
  so; not every agent is given one.

### Whom you can write to

`/run/porelo/mail/agent-card.json` lists whom you can reach right now, by name. **Read it before you write**: it changes
while you work, as other tasks start and stop.
- `people` is the persons in your project's room. No other task reads what you send there.
- A person's own name (as `metadata.from` shows it on their messages) reaches them: in the
  room, mentioned, or with `"via": "direct"` in your direct chat with them. Write to them
  directly only to answer a direct message, or when you are asked to.
- Another task's name reaches that task, and the conversation sees it too.

### Receiving

- A message appears in `/run/porelo/mail/inbox/new/`, one JSON file each.
- Look there when you start, between steps of your work, before you finish, and whenever you
  come to rest.
- Once you have read a message, move it to `/run/porelo/mail/inbox/cur/` (`mv`), so you do not read it
  twice.
- A message of yours that was refused or could not be delivered comes back into
  `inbox/new/`, with the reason in its text.

### Sending

Write the message to `/run/porelo/mail/outbox/tmp/<name>.json`, then move it into
`/run/porelo/mail/outbox/new/` with `mv`. Never write into `outbox/new/` directly: a half-written file
there would be taken as it is. Keep the file readable (the default); the host picks it up,
signs it and delivers it.

A message is one JSON object (A2A 1.0):

```json
{
  "messageId": "a-unique-id-of-yours",
  "role": "ROLE_AGENT",
  "parts": [ { "text": "What you have to say." } ],
  "metadata": { "to": "alice", "via": "room" }
}
```

- `messageId`: required, unique among your messages.
- `role`: always `"ROLE_AGENT"`, spelled exactly so.
- `metadata.to`: exactly one name from `/run/porelo/mail/agent-card.json`. A message to nobody, or to more than one, is
  held.
- `metadata.via`: `"room"` or `"direct"`, as in the message you answer; `"room"` when you
  write first.
- `contextId` is optional: keep the one of the message you answer, to keep a conversation
  together.
- Nothing else is needed: no `extensions`, no `kind` at the top level.

### What does not travel

Every message you send is checked before it leaves, and only plain English prose passes.
Encoded data is refused, also when cut into pieces by spaces or line breaks: base64, hex, a full
commit id and the like. So are `data`, `raw` and `url` parts. Long code, identifiers and command
lines may be refused. **Never put a secret or a credential into a message:** do not rely on the
check to catch one. **Work travels by commit**, through the task's gate, never inside a message.
Name the commit by its short form (7 to 12 characters), never the full id. Depending on the
project, a person may read a message before it goes.
