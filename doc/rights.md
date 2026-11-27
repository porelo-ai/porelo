# What a task may do

A task's start fixes what it sees and what it may do, and Porelo keeps its own rules whatever a start asks.

## The four parts of a start

A start records four parts, and a task brought back keeps the parts it recorded: a start that names a part other than
the recorded one is refused as `RightsChanged`. The workspace, the landed work it also reads and the project's services
shape the container at the start. An act whose right the task lacks is refused with the rule
`RightMissing: <right> (<act>)`, which the task reads in the refusal of what it handed to its mailbox and which stands
in its gate's log for a push; a method of its own endpoint that its rights lack is not there for it. The options are in
[Commands](commands.md), the place and its errors in [Running](running.md#the-parts-and-place-of-a-tasks-run).

| Workspace | What the task sees |
|---|---|
| `own` | Its own branch and a gate that takes its pushes. |
| `foreign` | Another task's push or pull request, frozen at its head and read only. |
| `readOnly` | One repository, read only, no gate. |
| `allRepositories` | Every repository of the project, read only, no gate. |
| `none` | An empty directory, no gate. |

A task also reads the landed work of the tasks `readLanded` names, read only. The equipment is the project's services
(its credentials, package repositories, host services and egress) as one switch, `projectServices`, and the account,
`writeAs`, taken only at a pull request: `author` (the account that opened it, the default) or `review` (the forge
instance's own).

| Right | What it allows |
|---|---|
| `push` | Pushes work to its gate. |
| `reply` | Answers in a thread at its place. |
| `openThreads` | Opens a thread at its place. |
| `resolveThreads` | Marks a thread at its place resolved. |
| `start` | Starts tasks of its project through its own endpoint. |
| `stop` | Stops the tasks it started. |
| `done` | Declares how its own work ended. |
| `report` | Reports back to whoever started it. |
| `reject` | The one judgment: discards the waiting push it reads. |

`push`, `reply`, `openThreads` and `resolveThreads` are the actions at its place. `start`, `stop`, `done` and `report`
are what it does through its own endpoint, which carries each of those methods only where the project's `mcp` section
names it and the task holds its right ([Project file](project-file.md#a-tasks-mcp-endpoint)).

## The rules Porelo keeps itself

Each is checked before anything is made and refused as `StartRuleBroken` with the rule and the part that breaks it.
Every start is held to them, by hand as through a task's own endpoint, except `exceedsParent`, which holds only for a
start through a task's own endpoint.

| Rule | Named by |
|---|---|
| Own project only: a task's place is a push or pull request of a repository of its own project, and it starts tasks of its own project. | `PlaceUnreadable` (`NotInProject`, or `NoSuchPush` for a name nothing of its project waits under) and `ProjectNotItsOwn`, not `StartRuleBroken` |
| A task reading another task's work gets nothing that leads out: no project services, no `push`, no added credentials, no clearance of `allow` or `off`. | `foreignWorkLeadsOut` |
| Another task's work has no default rights: a start there names them. | `foreignWorkNamesNoRights` |
| A judgment only at another task's work: never over its own, nor in a workspace with no waiting push to discard (`readOnly`, `allRepositories`, `none`). | `rejectOverOwnWork` |
| No judgment beside `push`. | `rejectWithPush` |
| `push` only from its own work. | `pushWithoutOwnWork` |
| A task started through a task's endpoint holds at most what the task that starts it holds: its workspace, services, account, rights and landed work. | `exceedsParent` |

A task that reads another task's work sends nothing but to its own review: a message it addresses to anyone else is
refused when it is dispatched or released, by the rule `readsAnothersWork`, which is no start rule.

A start naming no part gets its own work with `push` and `reply`, the project's services and the account `author`.
Without a gate (`readOnly`, `allRepositories`, `none`) it gets the services and no right. An empty list of rights is a
start holding none, never the default.

## The operator

The operator is the human who owns the account. No right binds him: a right is a task's, and no caller of the daemon's
interface can lack one. A task's own endpoint carries none of the methods of his judgment
([Project file](project-file.md#a-tasks-mcp-endpoint)): he approves a waiting push and releases or refuses a held
item. He rejects any waiting push, and a task holding `reject` only the one it reads. Who has which account on a host
is in [Hosts](hosts.md#a-persons-login-on-a-host).

| What bounds him | Where |
|---|---|
| The project's security class: an `offline` project refuses `approve`. | [Security classes](security.md) |
| The organization's roles, for what is changed in the organization. | [Roles](roles.md) |
| His own MCP client's list of methods it may call. | [Running](running.md#porelo-in-your-own-mcp-client) |

## When comments wait

What a task hands to its review waits by its place, its project's class and whether a certified key signed it, never
by anything the task says.

| Place | Signed | Unsigned |
|---|---|---|
| A waiting push at the gate | Goes into the push's threads at once. | The same: nothing is held there. |
| A pull request of a `guarded` project | Held until the owner account releases or refuses it. | Held, marked unsigned. |
| A pull request of an `online` project | Goes to the forge at once. | Held, marked unsigned. |
| A pull request of an `offline` project | Refused: an offline project has no pull request. | Refused alike. |

Whatever the place, two more things hold a message addressed to the review until a person releases it: a finding of
the message filter, and an unsigned reply that is no submission. How a forge's comments are read before a run sees
them is set per forge by `porelo forge comments`, in [Commands](commands.md); held messages are in
[Messages](messages.md).

## The two borders outward

Where a task's `push` goes beyond its gate is set by its project's class, in
[Security classes](security.md#the-three-security-classes). An `online` project's task branch goes on to the upstream
at once, under the name [Running](running.md#being-told-what-the-build-of-a-push-did) gives it. On a host the gate
passes the task's branch to the host's forge as `porelo/<task>`, whatever the project's branch scheme says; how work
goes on from there to the central forge is in [Hosts](hosts.md#a-push-judged-when-it-arrives).
