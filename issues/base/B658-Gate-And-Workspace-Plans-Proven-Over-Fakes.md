# B658 — Gate And Workspace Plans Proven Over Fakes

```json
{
  "$schema": "issue-1.json",
  "id": "B658",
  "title": "Gate And Workspace Plans Proven Over Fakes",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 67,
  "blockedBy": [
    "B665"
  ],
  "covers": "Gate/workspace plans: GitGate, GitHttpServer, SignedArrivals, TaskWorkspace",
  "openQuestions": 0
}
```

**Size.** M, as the test pyramid audit measured it (2026-11-09), split out by the operator on 2026-11-12 (ST296).

**What must be true.** The flows below are proven first as plain logic behind small interfaces, by unit tests over fakes that run in seconds; only the boundary named under *Retained boundary* stays an integration test, and no higher test that proves a harmful path is removed before its lower proof is seen red, then green.

## Plan

GitGate, GitHttpServer, SignedArrivals, TaskWorkspace; ref/signature/upstream ports, review/ref/checkout plans; GateCheckCommand/GateProtectCommand use ancestry/approval fakes. Unit red: reviewed commit changes or upstream refuses, no unreviewed forwarding, ref restored.

**Retained boundary and priority.** Real receive/push, bundle restore, signing; VM review. Move refusal/branch/restore matrices below Git; approval race and credential scope harm.

### Flows it proves lower

| Area | Flow | Proven now (test classes) | Logic holder | Fix |
|---|---|---|---|---|
| Tasks | T4: Checkout, resume without data loss and scope Git credentials and push destinations | `TaskWorkspaceTest`; lower: Keep a narrow real Git push/config test; the choice of branch and credential scope can be isolated. | `TaskWorkspace`, `WorkspaceSetup` | [B658](README.md#b658) |
| Gate | G1: Review, approve, reject, restore and refresh exactly the reviewed commit and preserve pending work | `GitGateTest`, `SeveralRepositoriesTest`, `UpstreamDistanceTest`, `GateHostApprovalTest`, `GateReviewCommandTest`, `GateRejectCommandTest`, `GateServeHostPushTest`; lower: ReviewRankingTest proves ranking alone; it does not prove approval races, refusals or branch retention. | `GitGate` | [B658](README.md#b658) |
| Gate | G2: Receive only the task ref, forward atomically, restore a refused ref and bound request resources | `GateForwardedTest`, `GateLimitsTest`, `GateSigningTest`; lower: A GitProcess port exists, but constructing the policy also binds a real HTTP listener. | `GitHttpServer` | [B658](README.md#b658) |
| Gate | G3: Judge certified arrivals by arrival time and principal and reject unsigned or mismatched commits | `SignedArrivalsTest`, `TaskCertificationTest`, `TaskCheckCertificateCommandTest`, `TaskCertificateCommandTest`, `TaskSignerTest`, `TaskSigningTest`, `TaskSigningSecretsTest`; lower: Certificate parsing has units; the end-to-end judgment still invokes Git and ssh-keygen. | `SignedArrivals`, `TaskCertification` | [B658](README.md#b658) |
| Machine and security | S4: Check the pre-push approval guard against unreviewed work and protect only the intended repository | `GateCheckCommandTest`, `GateProtectCommandTest`; lower: The current proof initializes real Git repositories for each decision. | `GateCheckCommand`, `GateProtectCommand` | [B658](README.md#b658) |

### Classes it redesigns

| Class group | What makes it hard | Redesign | Fix |
|---|---|---|---|
| `TaskWorkspace` | Branch policy, Git commands and secret configuration require real mirrors. | Workspace Git port; checkout/configuration plan. | [B658](README.md#b658) |
| `GitGate` | Git calls interleave review, approval, ref comparison, restore/refresh decisions. | Repository snapshot, pending store, upstream/operations ports; pure plans. | [B658](README.md#b658) |
| `GitHttpServer` | HttpServer binds at 231 before authorization, rollback or pacing can be tested. | GateRequestPolicy/RefTransaction; GitProcess and Clock; byte adapter. | [B658](README.md#b658) |
| `SignedArrivals` | ProcessBuilder invokes ssh-keygen at 139 inside arrival judgment. | CertificateVerifier, commit facts, fixed Clock. | [B658](README.md#b658) |

### Waits it replaces

| Kind | Methods | Most numerous classes (methods) | Replacement / fix |
|---|---:|---|---|
| Real clock | 29 | RemoteStartTest 8; UpstreamRecordsTest 5; SignedArrivalsTest 4; EventsCommandTest 2; MessageSignersTest 2 | Clock.fixed/explicit Instant for events, expiry, file/certificate judgments ([B661](README.md#b661)); remote certificate Clock ([B663](README.md#b663)), arrival verifier/facts ([B658](README.md#b658)). |

## Acceptance

- Each harmful behaviour above has a unit regression seen red first, then the seam or core that makes it green.
- The flows above map their named higher paths to the new unit proof, or keep an explicit irreducible adapter contract.
- The report gives the exact revision, per-level cases and classes, testcase sums and the module's unit run time, measured as [B665](README.md#b665) defines; a duration not measured says so.

## Decided by the operator (2026-11-12)

- Each of the audit's fourteen fixes is its own issue; [B665](README.md#b665) (reproducible levels and times) is built first.
- "Under 60 seconds per module unit run" is a direction that is reported, not a gate.
- The agent drops repeated CLI end-to-end cases itself, once their lower proof is red then green, and lists them in the review.
- The redesigns of `TaskLaunch`, `TreeWalk` and `VaultProxy` run between feature rounds, with no feature issue open in those classes.
