# [B323](README.md#b323) — Source Levels And Risk Classes Derived And Shown

**Status:** Idea, split from [B293](README.md#b293) by the operator's decision on 2026-10-29.

**What must be true.** The daemon assigns a source level to each pull request, commit and comment from authenticated
evidence, and a code risk class from deterministic rules over the complete change. Both are carried with waiting work,
a comment and a finding, so the interface, the IDE plugin and the review show them as the daemon reports them. Nothing
is refused by them yet: delivery, builds, secrets, replies and approvals follow them with [B293](README.md#b293).

## The levels

How each level is recognized, from authenticated data only:

| Level | Who | Recognized from |
|---|---|---|
| `E0` external, unknown | any account, a first contribution, a fork | not on the project's list; the forge's association with the repository (on GitHub `author_association` `NONE` or `FIRST_TIME_CONTRIBUTOR`) |
| `E1` external, known | returning contributors, partners | named in the project file, or merged contributions before |
| `I1` internal | people of the organisation | membership of the forge's organisation or team, or accounts of the organisation's own forge; signed commits where the project asks for them |
| `A` agent | a Porelo task | started by the daemon, with its role (builder, reviewer); never higher than the level of what it read |

The level is set per item: a pull request's author, each commenter, each commit. A pull request of `I1` with an `E0`
commit pushed into it is `E0`.

## Proposed

- Blocked by: [B291](README.md#b291), whose profile carries the level and class on a comment and a finding.
- Blocks: [B293](README.md#b293), [B166](README.md#b166), [B163](README.md#b163); interface and IDE consumers that
  show the level and class.
- `E0` is external unknown, `E1` external known, `I1` internal and `A` a daemon-recorded agent producer. Key account
  policy by forge instance and stable account ID. Classify PR authors, every commit and every commenter separately.
  Authenticated API transport does not authenticate Git author strings. Keep claimed author/committer, verified
  signer, account binding and observed push actor separate; missing evidence stays unknown and grants no promotion.
  A bot label, login, email, SARIF field or comment body grants no authority. Releasing content preserves its origin.
- Compute the maximum applicable `C0`-`C3` from trusted rules and complete pinned Git objects, independently of
  `ReviewRanking`'s display ranks. C0 covers documentation, text and tests without dependency changes under the
  profile's rules; C1 application code; C2 build, dependency, package and container inputs; C3 workflows, security
  code, keys, trust policy, symbolic links, binaries, deleted or weakened tests, schemas and migrations. Account for
  both modes, deletions, rename destinations and security-relevant consumers; unknown or incomplete classification
  cannot lower a class. A model may raise a class, never lower it or compute a score.
- The class is read from the Git objects themselves: a changed mode `120000` is a symbolic link, a changed build or
  dependency file is C2. [B141](README.md#b141)'s dependency evidence and [B283](README.md#b283)'s link evidence
  arrive later and may only raise a class. Emit a daemon-generated `porelo.git.symlink-changed` SARIF diagnostic
  naming the entry without following it or offering a fix.
- Add provenance to `TaskProfile` with the next schema version; expose the daemon-owned level, class and their
  evidence in `35-gate.varlink` on waiting work, and stamp them on comments and findings. The change cannot classify
  itself using its proposed rules.

## Open questions

- Whether `E1` is a list kept by hand in the project file, or follows from merged contributions.
- Whether `I1` requires signed commits by default.

## To be checked

- Verify GitHub and Forgejo identity, membership and signer evidence against their deployed authenticated APIs,
  including unavailable membership, renamed accounts, missing commits and forks. Keep forge conversion in the adapters
  and accept typed authenticated evidence without waiting for forge-review synchronization.
- Measure completeness of the Git inventory; an incomplete one leaves the class at `C3`.
- Confirm how executable documentation/tests are distinguished within C0 and how rule-based detection of weakened
  tests and security dependencies works; do not classify from filenames alone or run repository code to find out.
