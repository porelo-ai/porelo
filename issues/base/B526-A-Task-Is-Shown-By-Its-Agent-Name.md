# [B526](README.md#b526) — A Task Is Shown By Its Agent Name

**Status:** Review. Filed 2026-11-08 from move 3's findings 10 and 19 (the display part).
**Size:** S - `enroll` passes `--display` where the transport announces it.

**What must be true.** A task enrolled into its project's room is shown there as "Agent" and its task's name, each
word capitalized: `porelo-porelo-pi` is "Agent Pi", the name the persons write to and porelo-message-matrix reads as a head.
Porelo passes it only to a transport that announces `"display": true`, as porelo-message-matrix does.

## Why

The room showed each task by its account, so a person read `porelo-porelo-pi` where the room's rule says
"Agent Pi".

## As built

- `TransportDescription` reads `display` as an option the transport takes. `TransportLifecycle.enroll` adds
  `--display <name>` when it does. The name comes from `TransportLifecycle.agentName(project, container)`: the
  container less `porelo-<project>-`, each hyphen word capitalized, after "Agent ". There is none for a container of
  another form or a name over 64 characters. The task is then enrolled as before, under the transport's own name.
- Enroll runs at every start, so a task from before gets the name at its next start, if the transport sets it on a
  confirming enroll too (Agent Matrix: please confirm).
- Tests: `TransportConversationsTest.aTaskIsEnrolledUnderItsAgentNameWhereTheTransportShowsOne` (seen red) and
  `aTransportThatShowsNoNameIsGivenNone`.
- Not here: the doubled project in the container name (`porelo-porelo-pi`). Containers are recognized by
  `porelo-<project>-<task>` throughout, so that change needs its own plan reading.
