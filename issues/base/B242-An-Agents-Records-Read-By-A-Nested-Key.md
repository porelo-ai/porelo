# B242 — An Agent's Records Read By A Nested Key

**Status:** Implementing.
**Size:** M - the agent API, a published artifact agent repositories build against, grows in three records and its reader and JSON form; `TaskSession` and a doc section follow.

**What must be true.** An agent definition can say where its session id and its last message are when the agent
writes them below the top level of a record, and that a session file's own record differs from a run's, so an agent
like Codex CLI resumes and reports its last message without code of its own in Porelo.

## Why

Measured by porelo-codex on Codex CLI 0.163.0:
- a session file `.codex/sessions/YYYY/MM/DD/rollout-<timestamp>-<uuid>.jsonl` begins with
  `{"type":"session_meta","payload":{"id":"<uuid>",...}}`, while an unattended run emits
  `{"type":"thread.started","thread_id":"<uuid>"}`; resuming takes the `payload.id`, not the file name;
- a run's last message is `{"type":"item.completed","item":{"type":"agent_message","text":"..."}}`.

`SessionIds` names one record and one top-level key for both places, and `Waiting.LastMessage` matches and reads
top-level fields only.

## The shape proposed

- A key may be a dotted path of at most four segments of `[A-Za-z0-9_]`, each through a mapping, never an array:
  `SessionIds.key`, `LastMessage.text` and the fields a record is matched on (`item.type: agent_message`).
- `session_id` may add `file_record` and `file_key` for the session files' own record; without them the files are read
  with `record` and `key`, as today.
- Only grows: a definition written today means the same. Whether any published definition has a key holding a dot is
  checked first.

## Plan

Read in `SessionIds`, `Waiting.LastMessage`, `AgentDefinitionReader`, `AgentDefinitionJson`, `TaskSession` and
`agents/README.md`; no published definition (claude, pi, omp, codex, stub) has a key or field name holding a dot,
only values such as `thread.started`.

1. **A dotted path** - at most four segments of `[A-Za-z0-9_]`, each through a mapping - is one small helper of the
   agent API, used for a record's field names, `SessionIds.key` and `LastMessage.text`; a key without a dot reads
   as today.
2. **`SessionIds`** gains `fileRecord` and `fileKey`, both or neither, the earlier constructor kept; `ofFile` reads a
   session file's records with them, else with `record` and `key`. `TaskSession.fromInside` calls `ofFile`.
3. **`AgentDefinitionReader`** takes `file_record` and `file_key` under `session.session_id`, and checks every path;
   **`AgentDefinitionJson`** writes and reads them, so `porelo agents --verbose` shows them.
4. **Docs:** the definition's section in `agents/README.md`.
5. **Red first:** `SessionIdsTest.aSessionIdBelowTheTopLevelOfARecordIsFound`, then
   `aSessionFileReadByItsOwnRecordGivesTheIdInside`; `WaitingTest.aLastMessageBelowTheTopLevelIsFound`;
   `AgentDefinitionReaderTest.aPathOfFiveSegmentsIsRefused`.

## Acceptance

- A definition naming `payload.id` in `file_record: {type: session_meta}` and `item.text` with `item.type:
  agent_message` resumes and reports its last message from records shaped as Codex CLI writes them. Seen to fail
  first on today's code.
- Every definition published today reads and behaves as before.
