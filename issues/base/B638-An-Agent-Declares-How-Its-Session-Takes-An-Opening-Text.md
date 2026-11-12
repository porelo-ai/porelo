# B638 — An Agent Declares How Its Session Takes An Opening Text

```json
{
  "$schema": "issue-1.json",
  "id": "B638",
  "title": "An Agent Declares How Its Session Takes An Opening Text",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 4,
  "blockedBy": [],
  "covers": "An agent definition's session.opening_flag says how an attended session takes an opening text - \"\" positionally, or after a flag such as agy's -i - so a discussion can open with any agent; absent, the text is positional for an agent whose headless prompt is, and refused otherwise, as today."
}
```

**Why this size.** One optional field read beside `resume_flag`, one record component, `attendedCommand`, the
definition reference and tests.

Filed 2026-11-12 under ST314, agreed with Agent Smith (room, 11:04Z). [B632](README.md#b632) hands an attended task's
prompt to its agent as its session's first turn, positionally, and refuses an agent whose headless prompt takes a
flag. claude and agy take `-p` for print mode, so neither could open a discussion. The operator gave Agent Smith the
issue of declaring the form for every agent (claude positional, agy `-i`); this is the field it declares.

**What must be true.**

- `session.opening_flag: ""` puts the opening text last, positionally. Any other value is a flag the text follows,
  last: `[..., "-i", "<text>"]`. A value that is not one argument beginning with `-` refuses the definition when it is
  read.
- Absent, nothing changes: the text is positional when `headless.prompt_flag` is `""`, and refused with 64 before
  anything is made otherwise.
- The rule on the text holds for both forms: blank, a NUL, or a leading `-` or `@` is refused.
- `agents/README.md`'s definition reference names the field.

## Plan

1. `AgentDefinition` gains `@Nullable String openingFlag` as its last component, with a constructor of today's
   components that leaves it `null`; `AgentDefinitionReader` reads `session.opening_flag` and refuses a bad value.
2. `attendedCommand(model, opening)` takes the declared form, and falls back as above when none is declared.
3. Red first: `HeadlessCommandTest` cases for `""` with a flagged headless prompt, `-i`, the absent fallback both
   ways, and a text beginning with `-` after `-i`; `AgentDefinitionReaderTest` for the field and a refused value.

## As built

- `AgentDefinition` has `openingFlag` as its last component. The constructor with the old components leaves it `null`,
  so every caller from before keeps its meaning.
- `AgentDefinitionReader` reads `session.opening_flag` and refuses anything other than `""` or one flag that begins
  with `-` (no whitespace, no NUL, not a bare `-`, no non-string). `AgentDefinitionJson` carries the field in an agent's
  own description and reads it back with the same check, because Porelo takes an installed agent's definition from
  that description.
- `attendedCommand(model, opening)` appends the declared flag, if any, and then the text. When no flag is declared it
  falls back as before. The rule on the text holds for both forms.
- `agents/README.md` names the field, and a changelog entry says it.

## After the code reading

Read by agy (gemini-4-argon-high).

- Taken (red): `AgentDefinitionJson` neither wrote nor read `openingFlag`, so an installed adapter's declaration never
  reached Porelo, and claude and agy would still be refused. `anOpeningFlagSurvivesTheWayToPorelo` failed for `""` and
  for `-i` without the write.
- Taken (yellow): a bad value in an agent's description is refused as one in a file is
  (`refusesAnOpeningFlagFromAnAgentsDescriptionThatIsNoOneFlag`).
- Taken (yellow): the refusal test wrote every value quoted, so it never tried a non-string or a NUL. It now also tries
  `7`, `true`, `[]`, `null` and `-a\0`.
