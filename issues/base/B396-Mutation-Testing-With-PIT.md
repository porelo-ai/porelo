# B396 — Mutation Testing With PIT

**Status:** Accepted.

**What must be true.** A person can measure, by hand, which of this repository's tests would notice a change in the code
they cover, and read which gaps are real.

- A `pit` Maven profile, run by hand, not in CI, with no threshold that fails a build.
- PIT and its JUnit 5 plugin pinned, as every other dependency is.
- Run on a VM wherever tests call real tools.
- A report that sorts the survivors into real gaps and harmless ones.

## Model

Agent Matrix's try in porelo-message-matrix: `~/.porelo/handover/porelo-message-matrix/pit-014d86a/report.md` and its
branch `pit-try`, later its pushed profile.

## Question for later

Whether the profile moves to porelo-parent, so each repository has it from there.
