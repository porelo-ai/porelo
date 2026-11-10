# B396 — Mutation Testing With PIT

```json
{
  "$schema": "issue-1.json",
  "id": "B396",
  "title": "Mutation Testing With PIT",
  "state": "Accepted",
  "size": "-",
  "priority": "Medium",
  "rank": 4,
  "blockedBy": [],
  "covers": "A `pit` profile run by hand, not in CI and with no threshold, and a report sorting survivors into real gaps and harmless ones."
}
```

**What must be true.** A person can measure, by hand, which of this repository's tests would notice a change in the code
they cover, and read which gaps are real.

- A `pit` Maven profile, run by hand, not in CI, with no threshold that fails a build.
- PIT and its JUnit 5 plugin pinned, as every other dependency is.
- Run on a VM wherever tests call real tools.
- A report that sorts the survivors into real gaps and harmless ones.

## Model

A try in porelo-message-matrix, its report kept outside the repository, and its
branch `pit-try`, later its pushed profile.

## Question for later

Whether the profile moves to porelo-parent, so each repository has it from there.
