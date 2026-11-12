# B640 — An Answer Read From A Message Of Parts

```json
{
  "$schema": "issue-1.json",
  "id": "B640",
  "title": "An Answer Read From A Message Of Parts",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "An agent's last_message can name a field holding a list of parts and the parts that are text, so a run whose last message is thinking and text answers its text in the ended record, List and RunResult."
}
```

**Why this size.** One optional field in `Waiting.LastMessage`, read from a file and carried on the agent's wire, and
its tests. The pi adapter's declaration is its own repository's change.

Filed 2026-11-12 under ST314 from porelo-frontend F492 run 24, step 9. The reader
`porelo-foo-project_foo-1-2` (pi, z-ai/glm-5.3-flash) ended with a well-formed `Findings:` block in its final
message, but the ended record and List answered `answer: ''`. The planner then held the story in `INVALID_FINDINGS`.

How it is today, read in the code: a run's answer is the text of the last record that matches the agent's
`session.waiting.last_message` (`Waiting.LastMessage.of`). With no declaration, the answer is `""`. pi declares none
(porelo-pi 28d3467). Of the adapters, only codex does. pi ends a message with
`{"type":"message_end","message":{"role":"assistant","content":[{"type":"thinking",...},{"type":"text","text":"..."}]}}`,
so its text lies in a list of parts. A field path (`message.content`) can name that list but cannot reach into it, so
pi could not have declared its answer even if it tried.

**What must be true.**
- `last_message` takes an optional `part: { record: {...}, text: <field> }`. When the text field holds a list, the
  answer is the text of each part that matches `record`, joined in order by a newline. Parts that do not match, such
  as thinking or a tool call, are left out. A list with no such part answers nothing from that record.
- Without `part`, nothing changes: the text field must hold a string.
- `part` is checked when read, as `record` and `text` are, and it survives an agent's own description to Porelo.
- `agents/README.md` names the field.

## Plan

1. `Waiting.LastMessage` gains `@Nullable Part part`, with a constructor of today's two components that leaves it
   `null`. `of(records)` joins the matching parts' text when the field holds a list.
2. `AgentDefinitionReader` reads `session.waiting.last_message.part`. `AgentDefinitionJson` writes and reads it.
3. Red first: a `WaitingTest` for pi's `message_end` shape (thinking, then text) answering the text, and a round trip
   through `AgentDefinitionJson`.
4. Tell the pi adapter's owner the declaration once this is pushed:
   `last_message: { record: { type: message_end, message.role: assistant }, text: message.content, part: { record: { type: text }, text: text } }`.

## As built

- `Waiting.LastMessage` has an optional `Part(record, text)`. With a part, the record's text field must hold a list.
  The answer is the text of each part that matches, joined in order by a newline. A record with no such part, such
  as a message that only calls a tool, is passed over, and the last record that said something stands. Without a
  part, the text field must hold a string, as before. The two-component constructor keeps every caller's meaning.
- `AgentDefinitionReader` reads `session.waiting.last_message.part` and refuses an unknown key, a missing `text` or an
  empty `record`. `AgentDefinitionJson` writes and reads it. An adapter older than the field sends none.
- `agents/README.md` names the field. The ended record, List and `RunResult` read it through `LastMessage.of`
  unchanged.
- `last_message` sits in `session.waiting`, so declaring it gives an agent a waiting declaration without screen rules.
  `AgentWake` now types into an agent only when its waiting declaration has screen rules, so pi, whose screen cannot
  tell a question it put from its rest, is still never woken. `AgentWaiting` already answered such an agent "cannot
  say" (`UNDECLARED`). `AgentWakeTest` has an agent with `at_rest` and only `last_message`, at rest under an open
  question. It was typed into without the guard.
- `WaitingTest` reads pi's `message_end` shape (thinking, then two text parts, then a message that only calls a tool)
  and expects the text. It failed with `""` when the text parts were not taken, and the round trip through the
  agent's description failed when `part` was not written.

## After the code reading

Read by agy (gemini-4-argon-high), with nothing to take. All five points were green: answers without `part` are
unchanged; parts are joined in order and passed over when no text part matches; `part` is checked and refused naming
the file, and it survives the agent's description both ways; answers stay within each caller's bound; the tests show
the contract. Agent Smith's reading of the wake (an agent with `at_rest` and only `last_message` would be woken) was
taken, with the guard above.

