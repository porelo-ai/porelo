# B640 — An Answer Read From A Message Of Parts

```json
{
  "$schema": "issue-1.json",
  "id": "B640",
  "title": "An Answer Read From A Message Of Parts",
  "state": "Implementing",
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
