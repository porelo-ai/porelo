# B511 — A Fraction Where A Whole Number Is Wanted Is Refused

```json
{
  "$schema": "issue-1.json",
  "id": "B511",
  "title": "A Fraction Where A Whole Number Is Wanted Is Refused",
  "state": "Idea",
  "size": "S",
  "priority": "Medium",
  "rank": 5,
  "blockedBy": [],
  "covers": "A fraction or an out-of-range number sent where a varlink contract wants a whole number is refused by the method, never truncated, at each reader [B509](#b509)'s reading named."
}
```

**Why this size.** one reader for whole numbers beside `VarlinkNumbers.intOf`, used at each reader below, with a refusal each
and a test.

**What must be true.** A varlink call or reply that sends a fraction, such as `1.5`, where the contract wants a whole
number is refused with the method's own refusal, never truncated to `1`. The same holds for a whole number past the
field's range.

## Why

[B509](README.md#b509)'s reading (2026-11-08) found that the readers truncate. It was so before [B509](README.md#b509), which kept it.
The issue's own plan had named refusing a fraction, and that part was left out.

## Analysis

Read in the code on 2026-11-08. Every reader takes `instanceof Number` and then `intOf`, `intValue` or
`longValue`, which truncate a fraction:

- `PoreloDaemon.request`: `Start`'s `minutes`, `maxTurns` and `tokenHours`;
- `TaskMethods`: `Tail`'s and `Screen`'s `last`, and `HandIn`'s `bytes` and `offset`;
- `VaultMethods`: `UnlockWithShare`'s `minutes`;
- the agent plugins' readers in `agents/api` (`AgentServer`, `AgentEnd`, `Finding`, `InstalledAgent`);
- the build readers in `builds/api` (`InstalledBuildReader`);
- `ClearanceService`'s `port`.

`Wire.whole` (ecosystems) and `SubagentLimit.wholeNumber` already refuse a fraction, but only by `doubleValue`, so
`1.00000000000000001` passes as `1`. Since [B509](README.md#b509), a whole number arrives as a `Long` and anything else as a `Double`,
so testing for a `Long` alone is exact.

## Plan

- `VarlinkNumbers.whole(Object) -> OptionalLong`: present only for a `Long`, or an `Integer`, `Short` or `Byte`.
- Each reader above refuses an empty one, or one past its range, with the method's existing refusal for a wrong
  parameter: `InvalidParameter`, or the field's own error where the contract names one.
- **Red first:** for `HandIn` with `bytes: 10.9`, `Start` with `minutes: 1.5`, and a plugin's `line: 1.5`.
