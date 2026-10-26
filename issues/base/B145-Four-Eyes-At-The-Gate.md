# [B145](README.md#b145) — Four Eyes At The Gate

**Status:** Analyzed.
**Blocked by:** [B136](README.md#b136), [B293](README.md#b293).
**Blocks:** [B163](README.md#b163).
**Size:** L - independent signing principals, task provenance, project rules, gate verification and an additive daemon contract.

**What must be true.** Two independent people approve the exact waiting commit whenever the project requires four
eyes or the work is E0 or C3. The person who started or authored the work cannot authorize it alone, and an agent's
check or forge verdict cannot count as a person's approval.

## Plan

`GitGate.approveSigned` signs a merge through the person's Git configuration; it does not verify a second person's
approval. `TaskProfile` is version 5 and records no `startedBy`. `ProjectPaths.configurationSigners` supplies the
configuration signer file, but `ProjectSchema` has no approval settings. Reuse signing machinery while making the
review-approval principals and trust scope explicit.

1. Red first: in `apps/app`, a fixture with two allowed signing principals and E0/C3 work refuses the first person's
   approval alone, even without `four_eyes`. A valid second signature permits only its bound commit.
2. Add `approval: {four_eyes: true}` through `Project`, `ProjectReader`, `ProjectSchema` and the documented project
   fixture, default false for work that does not otherwise require two people. Apply [B293](README.md#b293)'s required
   approval count; project or machine policy may tighten it but cannot remove E0/C3 requirements.
3. Record `startedBy` and authenticated input provenance in the next `TaskProfile` schema, not a reused version 5.
   External work without a local starter still requires two independently authenticated human principals. Never
   infer a human from a task role, a forge bot label or text in a report.
4. Add `porelo gate co-sign`: on the second person's machine, sign the exact reviewed commit in a dedicated signing
   namespace. Carry the commit, project/repository and policy context in the signed payload. `gate approve` and
   `Approve` in `35-gate.varlink` take the signature as an additive parameter.
5. `GitGate` verifies the second signature with `ssh-keygen -Y verify` against the configured allowed principals,
   alongside the authenticated approving person's principal. Require two distinct people; a second key belonging to
   the same person is insufficient. Check before either signed or ordinary forwarding. A new push invalidates both
   commit-bound approvals under [B136](README.md#b136); no prior commit's signature can be reused.
6. Keep authenticated approval evidence in the journal, separate from SARIF presentation. Observed forge approvals,
   suppressions, resolutions and an agent's clean check are never a co-signature. Explain required and missing
   approvals in CLI and daemon refusals. Update `doc/project-file.md`, `doc/how-it-works.md`, `doc/corporate-security.md`
   and their relevant fixtures.

## Acceptance

- Seen red first: missing, duplicate-person, wrong-commit, wrong-repository, untrusted-key and wrong-namespace
  signatures forward nothing. A valid independent signature for the exact commit permits approval.
- E0/C3 always needs two people, including a project with the flag absent or false; E1/I1 lower-risk work remains a
  one-person approval unless policy tightens it. Policy cannot weaken the mandatory cases.
- The starter or author alone cannot pass the control. A copied SARIF verdict or forge approval never satisfies it.
  CLI and daemon enforce the same rule and journal the same principals.

## Open questions

- Are the project's configuration signers also the authorized code approvers, or should approval use a separate
  signer list and human-principal mapping?
- Which transport brings an independently made co-signature to the node: a file supplied to approve, an interface
  upload, or a signed message? The signature's verification and commit binding hold for each.

## To be checked

- Verify the signed-payload encoding, key/principal mapping and replay scope against the existing enrollment and
  allowed-signers handling. Measure refusal paths for missing local identity and unavailable verification tools.
