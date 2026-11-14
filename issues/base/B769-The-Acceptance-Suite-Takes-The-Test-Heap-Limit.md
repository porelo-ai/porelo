# B769 — The Acceptance Suite Takes the Test Heap Limit

```json
{
  "$schema": "issue-1.json",
  "id": "B769",
  "title": "The Acceptance Suite Takes the Test Heap Limit",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 78,
  "blockedBy": [],
  "covers": "`acceptance/suite`'s failsafe fork gets `porelo.test.jvm.args` as its `argLine`, as every surefire fork does from the parent",
  "openQuestions": 0
}
```

**Why this size.** XS: one line in one plugin's configuration. Estimate: 9 minutes (Core's median for XS).

**What must be true.** A run of the acceptance suite on a shared host forks its JVM with the heap limit the host's
`.mvn/maven.config` sets, as every unit test fork does.

Filed 2026-11-14 on the coordinator's order, after a breach of the host's limit at 13:47Z.

## Why

The parent's surefire configuration puts `${porelo.test.jvm.args}` into its `argLine`, and the host's
`.mvn/maven.config` sets it to `-Xmx1g`. `acceptance/suite` runs `AcceptanceIT` through maven-failsafe, whose
configuration had no `argLine`, so that fork ran without the limit in every VM run.

## Plan

- `acceptance/suite/pom.xml`: failsafe's configuration gets `<argLine>${porelo.test.jvm.args}</argLine>`. The parent
  declares the property empty, so a build without it forks as before.

## Built

- As planned. The effective POM of `acceptance/suite` names `-Xmx1g` in failsafe's `argLine` with the host's
  `maven.config`, and nothing before the line (seen first without it). The suite's VM run for
  [B140](README.md#b140) forked with it.

## Red-first test

The effective POM, as said under Built: a build file's line has no unit test of its own.

## Risks

None: an empty property is an empty `argLine`.

## Blockers

None.

## Acceptance

- The suite's fork runs with the limit, seen in the effective POM and in the fork's command line.

## Open questions

None.
