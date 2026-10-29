# [B293](README.md#b293) — Source Levels And Risk Classes Enforced

**Status:** Idea.

**What must be true.** The daemon assigns a source level to each pull request, commit and comment from authenticated
evidence, and a code risk class from deterministic rules over the complete change. The more restrictive permissions
of source, consumed inputs, risk and policy decide delivery, execution, secrets, forge replies and human approvals.

## The levels and their defaults

How each level is recognized, from authenticated data only:

| Level | Who | Recognized from |
|---|---|---|
| `E0` external, unknown | any account, a first contribution, a fork | not on the project's list; the forge's association with the repository (on GitHub `author_association` `NONE` or `FIRST_TIME_CONTRIBUTOR`) |
| `E1` external, known | returning contributors, partners | named in the project file, or merged contributions before |
| `I1` internal | people of the organisation | membership of the forge's organisation or team, or accounts of the organisation's own forge; signed commits where the project asks for them |
| `A` agent | a Porelo task | started by the daemon, with its role (builder, reviewer); never higher than the level of what it read |

The level is set per item: a pull request's author, each commenter, each commit. A pull request of `I1` with an `E0`
commit pushed into it is `E0`. A class `C3` change is treated at least as `E0` for building, secrets and approval,
whoever wrote it.

The defaults by profile, which a project file and then a machine's policy may tighten, never loosen:

| Operation | `E0` | `E1` | `I1` | `A` |
|---|---|---|---|---|
| A comment delivered to the building agent | after a person releases it | yes, marked untrusted | yes | yes, marked untrusted |
| A suggestion applied by the agent | no | after release | after structural checks, never automatically | after release |
| A reviewer task started by itself | no | yes | yes | - |
| The code built or tested | only in a throw-away container without secrets or egress | throw-away container | as usual | - |
| Secrets or tokens in that run | never | never | by the project's rule | never |
| A reply written back to the forge | after release | after release | after release; a project may let it go without | after release |
| A thread resolved on the forge | shown, the finding stays | shown, the finding stays | clears the finding at the gate | shown, the finding stays |
| Approval at the gate | two people | one person | one person | never |

## Proposed

- Blocked by: [B136](README.md#b136), [B141](README.md#b141), [B283](README.md#b283), [B291](README.md#b291).
- Blocks: [B166](README.md#b166), [B171](README.md#b171), [B172](README.md#b172), [B145](README.md#b145),
  [B288](README.md#b288), [B289](README.md#b289), [B95](README.md#b95), [B163](README.md#b163); interface and IDE
  consumers.
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
  cannot lower restrictions. A model may raise a class, never lower it or compute a score.
- `E0` comments wait for individual human release. `E1`, `I1` and `A` comments are delivered at once, all marked
  untrusted and kept apart from instructions. `E0` suggestions are not applicable; `E1` and `A` suggestions wait for
  release; an I1 suggestion still passes structural checks and is never automatically applied.
- Every forge reply requires a person's release. Only an I1 reply may bypass it under an explicit project rule,
  and machine policy may forbid that exception. An authenticated I1 forge resolution clears its mapped gate finding;
  every other resolver is shown without clearing it. None of these observations approves a commit.
- E0/E1 code is read and commented but never built until [B95](README.md#b95) provides the throw-away path. E0 and
  C3 execution has no secrets, tokens or egress; E1 has no secrets or tokens. Agent-origin execution gains none.
  I1 access follows project rules, bounded by risk and machine policy. Reviewer model access is host-brokered and
  does not authorize executing reviewed code or exposing credentials to it.
- E0 or C3 requires two independent people under [B145](README.md#b145); an agent never approves. Agent check
  results on E0/C3 are informational and cannot replace a person. Gate enforcement refuses the operation until its
  required approval mechanism exists. Automatic reviewer starts exclude E0 and preserve A's input restrictions.
- Add the source/risk policy in `core/.../project/ProjectSchema`, `ProjectReader` and the project model; add
  provenance to `TaskProfile` with the next schema version; expose daemon-owned evidence and refusal reasons in
  `35-gate.varlink` and the relevant task contract. Connect enforcement in `GitGate` and task launch without an
  area-dependency cycle. Project and machine policy may tighten defaults; loosening is refused by setting name,
  except for the explicit I1 reply exception. The change cannot classify itself using its proposed policy.
- [B141](README.md#b141) supplies dependency evidence, [B283](README.md#b283) supplies link changes. Emit a
  daemon-generated `porelo.git.symlink-changed` SARIF diagnostic naming the entry without following it or offering a
  fix. No dependency on [B145](README.md#b145)'s signature implementation is needed to define required approvals.

## Open questions

- Whether `E1` is a list kept by hand in the project file, or follows from merged contributions.
- Whether `I1` requires signed commits by default.

## To be checked

- Verify GitHub and Forgejo identity, membership, signer and resolver evidence against their deployed authenticated
  APIs, including unavailable membership, renamed accounts, missing commits and forks. Keep forge conversion in the
  adapters and accept typed authenticated evidence without waiting for forge-review synchronization.
- Determine the machine-policy integration point with [B198](README.md#b198); no machine-policy reader exists in the
  project schema inspected here. Measure completeness of the Git inventory and test conservative refusal on failure.
- Confirm how executable documentation/tests are distinguished within C0 and how rule-based detection of weakened
  tests and security dependencies works; do not classify from filenames alone or run repository code to find out.
