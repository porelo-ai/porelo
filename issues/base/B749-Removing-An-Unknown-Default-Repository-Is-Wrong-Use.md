# B749 — Removing An Unknown Default Repository Is Wrong Use

```json
{
  "$schema": "issue-1.json",
  "id": "B749",
  "title": "Removing An Unknown Default Repository Is Wrong Use",
  "state": "Backlog",
  "size": "XS",
  "priority": "Low",
  "rank": 74,
  "blockedBy": [],
  "covers": "`porelo project default remove NAME` for a name 'default' does not hold answers 64, wrong use, and names what 'default' holds; never 70, which says Porelo failed.",
  "openQuestions": 0
}
```

**Why this size.** XS: one exit code and one line in one command, and their test. Estimate: under 45 minutes (below
Core's median for S).

**What must be true.** A person or a script that names a repository `default` does not hold is told it was wrong use,
not that Porelo failed.

Found on a test VM while playing [B682](README.md#b682) (2026-11-13). A checkout `chk-mark` whose origin is `o.git`
went into `default` as `o`, and then:

```
$ porelo project default remove chk-mark
porelo: 'default' has no repository called 'chk-mark'
$ echo $?
70
```

70 is documented as "another unexpected failure occurred; the trace is in `failures.log`". A name that is not there
is wrong use, which is 64.

## Plan

- `ProjectDefaultCommand` (`apps/app-project`, the `DefaultNamed.Removal.NotThere` case at about line 160) yields 70.
  It should yield 64 and add a line naming the repositories `default` holds, or `porelo project default list`. The
  `InUse` case keeps 70, which mirrors `project remove`.
- First red, in `apps/app`: `ProjectDefaultRemoveTest` removes an unknown name and expects 64.

## Acceptance

- `porelo project default remove` of an unknown name answers 64 and names how to see what `default` holds.
- Removing a name in use still answers 70, and a name that is there is removed as before.
