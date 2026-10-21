# B242 — An Agent's Records Read By A Nested Key

**Status:** Idea.

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
