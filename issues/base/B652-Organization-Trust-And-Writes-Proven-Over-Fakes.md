# B652 — Organization Trust And Writes Proven Over Fakes

```json
{
  "$schema": "issue-1.json",
  "id": "B652",
  "title": "Organization Trust And Writes Proven Over Fakes",
  "state": "Estimated",
  "size": "L",
  "priority": "Medium",
  "rank": 61,
  "blockedBy": [
    "B665"
  ],
  "covers": "Organization trust/write cores: TreeWalk, TreeRepository, GitTreeStore, GitTreeWrites, ProjectFile, RepositoryFileJudge, HttpForgeDirectory",
  "openQuestions": 0
}
```

**Size.** L, as the test pyramid audit measured it (2026-11-09), split out by the operator on 2026-11-12 (ST296).

**What must be true.** The flows below are proven first as plain logic behind small interfaces, by unit tests over fakes that run in seconds; only the boundary named under *Retained boundary* stays an integration test, and no higher test that proves a harmful path is removed before its lower proof is seen red, then green.

## Plan

TreeWalk, TreeRepository, GitTreeStore, GitTreeWrites, ProjectFile, RepositoryFileJudge, HttpForgeDirectory; history/signature/trust/revision/HTTP ports and change planner. Unit red: stale/forked history refuses; conflicted retry publishes once. ProjectTaking/OrganizationProjects use snapshot, reader, token lender, placement ports.

**Retained boundary and priority.** Signature, Git compare-and-swap, adapter contracts; replace repeated history after parity. Three slow classes: 322.239 class s / 2,275.110 testcase s; authority/replay/duplicate-write harm.

### Flows it proves lower

| Area | Flow | Proven now (test classes) | Logic holder | Fix |
|---|---|---|---|---|
| Organization | O1: Walk signed history: founding, roles, revocation, replacement keys, replay, rollback and fork refusal | `GitTreeStoreTest`, `OrgReadingCommandsTest`, `OrgFetchCommandTest`, `OrgCreateCheckTest`; lower: The contract stand-ins test the contract, not TreeWalk; the production walk takes a concrete TreeRepository. | `TreeWalk`, `GitTreeStore`, `TreeRepository` | [B652](README.md#b652) |
| Organization | O2: Authorize and publish writes, preserve unrelated fields, retry a push and avoid duplicate changes | `GitTreeWritesTest`, `OrgWritingCommandsTest`, `OrgSignersCommandTest`, `OrgAddReadKeyCommandTest`, `OrgRemoveReadKeyCommandTest`; lower: The stand-in write contract does not prove the real write planner. | `GitTreeWrites`, `TreeRepository` | [B652](README.md#b652) |
| Organization | O3: Accept only current signed project and repository settings and judge a checkout without trusting its config | `ProjectFileTest`, `ProjectFileCheckoutTest`, `RepositoryFileJudgeTest`, `ProjectSignRepositoryFileCheckoutTest`, `ProjectSignRepositoryFileTest`, `RepositorySettingsSignatureTest`, `CommitVerifyCommandTest`; lower: Repository settings parsing has units; signed history and freshness decisions still use real Git. | `ProjectFile`, `RepositoryFileJudge` | [B652](README.md#b652) |
| Organization | O4: Resolve forge identity, ownership and signing keys with incomplete, refused, oversized or stalled replies | `HttpForgeDirectoryTest`; lower: HttpForgeProfileTest covers one response reader, not the full ownership/key decision paths. | `HttpForgeDirectory` | [B652](README.md#b652) |
| Organization | O5: Take a project using current authority, lend its token, publish its file and refuse before creating side effects | `ProjectTakingTest`; lower: The fixture constructs and signs an entire organization for each policy branch. | `ProjectTaking`, `OrganizationProjects` | [B652](README.md#b652) |

### Classes it redesigns

| Class group | What makes it hard | Redesign | Fix |
|---|---|---|---|
| `TreeWalk` | 1,373 lines; concrete TreeRepository requires rebuilt signed history. | HistoryReader, SignatureVerifier; pure TreeModel transitions. | [B652](README.md#b652) |
| `GitTreeStore` | Static Files trust records; walk coupled to fetch/repository. | TrustRecordStore/history adapter; in-memory replay/fork/unavailable facts. | [B652](README.md#b652) |
| `GitTreeWrites` | 1,328 lines; authority, editing, signing, publish and retry rereads coupled. | Authorized planner and compare-and-swap publisher; conflict/uncertain-push fakes. | [B652](README.md#b652) |
| `TreeRepository` | 1,099 lines; temporary Git trees/subprocesses; concrete dependency. | Narrow history/read/publish ports; real Git adapter contracts. | [B652](README.md#b652) |
| `ProjectFile`, `RepositoryFileJudge` | Signatures/freshness coupled to fetch, snippets, Git, remembered disk revisions. | SignedRevision, bounded snippet reader, revision store; pure verdict. | [B652](README.md#b652) |
| `HttpForgeDirectory` | HttpClient/private Answer hide ownership/key decisions; stalled server fixtures. | Bounded HTTP response/deadline port and Clock; profile/key/ownership decoders. | [B652](README.md#b652) |

### Waits it replaces

| Kind | Methods | Most numerous classes (methods) | Replacement / fix |
|---|---:|---|---|
| Real endpoint/network | 40 | HttpForgeDirectoryTest 33; CodeGrantTest 5; GrantsTest 2 | Bounded HTTP/ownership/key answers ([B652](README.md#b652)); exchange/redirect-listener fakes ([B657](README.md#b657)). Forge fixture has configurable stall; only timeout needs delay. GrantsTest resolves auth.invalid and tries a closed loopback port: fake resolver/transport first. |

## Acceptance

- Each harmful behaviour above has a unit regression seen red first, then the seam or core that makes it green.
- The flows above map their named higher paths to the new unit proof, or keep an explicit irreducible adapter contract.
- The report gives the exact revision, per-level cases and classes, testcase sums and the module's unit run time, measured as [B665](README.md#b665) defines; a duration not measured says so.

## Decided by the operator (2026-11-12)

- Each of the audit's fourteen fixes is its own issue; [B665](README.md#b665) (reproducible levels and times) is built first.
- "Under 60 seconds per module unit run" is a direction that is reported, not a gate.
- The agent drops repeated CLI end-to-end cases itself, once their lower proof is red then green, and lists them in the review.
- The redesigns of `TaskLaunch`, `TreeWalk` and `VaultProxy` run between feature rounds, with no feature issue open in those classes.
