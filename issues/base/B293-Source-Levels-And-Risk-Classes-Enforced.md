# [B293](README.md#b293) — Source Levels And Risk Classes Enforced

**Status:** Idea. Part 2 of the operator's split on 2026-10-29: the levels and classes themselves are derived and
shown by [B323](README.md#b323).

**What must be true.** The more restrictive permissions of source level, consumed inputs, risk class and policy decide
delivery, execution, secrets, forge replies and human approvals, from the level and class [B323](README.md#b323)
derives.

## The defaults

A class `C3` change is treated at least as `E0` for building, secrets and approval, whoever wrote it.

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

- Blocked by: [B323](README.md#b323), [B136](README.md#b136), [B141](README.md#b141), [B283](README.md#b283).
- Blocks: [B171](README.md#b171), [B172](README.md#b172), [B145](README.md#b145), [B288](README.md#b288),
  [B289](README.md#b289), [B95](README.md#b95); interface and IDE consumers that hold, release or refuse by level
  and class.
- Unknown or incomplete classification cannot lower restrictions.
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
- Add the source/risk policy in `core/.../project/ProjectSchema`, `ProjectReader` and the project model; expose
  refusal reasons in `35-gate.varlink` and the relevant task contract. Connect enforcement in `GitGate` and task launch
  without an area-dependency cycle. Project and machine policy may tighten defaults; loosening is refused by setting
  name, except for the explicit I1 reply exception. The change cannot loosen its own rules with its proposed policy.
- A pull request from outside (`E0`, `E1`) is only read and commented, never built, and the refusal names its source
  level, until [B95](README.md#b95) provides the throw-away path.
- No dependency on [B145](README.md#b145)'s signature implementation is needed to define required approvals.

## To be checked

- Verify GitHub and Forgejo resolver evidence against their deployed authenticated APIs, for the I1 resolution that
  clears a gate finding.
- Determine the machine-policy integration point with [B198](README.md#b198); no machine-policy reader exists in the
  project schema inspected here. Test conservative refusal when the level or class is unknown.
