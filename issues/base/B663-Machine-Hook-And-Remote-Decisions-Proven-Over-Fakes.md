# B663 — Machine, Hook And Remote Decisions Proven Over Fakes

```json
{
  "$schema": "issue-1.json",
  "id": "B663",
  "title": "Machine, Hook And Remote Decisions Proven Over Fakes",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 72,
  "blockedBy": [
    "B665"
  ],
  "covers": "Machine/hook/remote decisions: NftHook, SetupCommand, HookInstaller, RemoteStart, TaskCertifying, machine probe",
  "openQuestions": 0
}
```

**Size.** M, as the test pyramid audit measured it (2026-11-09), split out by the operator on 2026-11-12 (ST296).

**What must be true.** The flows below are proven first as plain logic behind small interfaces, by unit tests over fakes that run in seconds; only the boundary named under *Retained boundary* stays an integration test, and no higher test that proves a harmful path is removed before its lower proof is seen red, then green.

## Plan

NftHook, SetupCommand, HookInstaller, RemoteStart, TaskCertifying, machine probe; process-result/deadline, account snapshot, remote bytes, Clock. Unit red: nft timeout refuses/logs; mismatched login PATH refuses install; expired/mismatched request certifies nothing.

**Retained boundary and priority.** Process kill/socket exchange; VM nft/SELinux/account registration. Fail-closed and account-isolation harm.

### Flows it proves lower

| Area | Flow | Proven now (test classes) | Logic holder | Fix |
|---|---|---|---|---|
| Machine and security | S1: Install only into the intended account, verify the effective hooks and refuse or repair stale registration | `SetupCommandTest`, `SetupScriptChannelTest`; lower: HookInstallerTest covers descriptor contents; packaged account/setup flow is still exercised higher up. | `SetupCommand`, `HookInstaller`, `DoctorCommand` | [B663](README.md#b663) |
| Machine and security | S2: Fail closed when nft load fails or hangs and keep the hook failure record | `NftHookTest`; lower: NftRulesetTest proves rule generation, not process timeout and fail-closed hook behavior. | `NftHook`, `Hook` | [B663](README.md#b663) |
| Machine and security | S3: Pin a remote host, relay a start and certify only the named request within its validity window | `RemoteStartTest`, `FollowGitTrustTest`, `TokenFetchOnItsOwnPortTest`, `RemoteStartConnectionTest`, `TaskCertifyingTest`, `TaskCertificateCommandTest`, `TaskCertificateRecordsTest`; lower: Quoting and certificate-word units exist; remote exchange still drives child shells, Git or an agent socket. | `RemoteStart`, `GitCredentials`, `TaskCertifying` | [B663](README.md#b663) |

### Classes it redesigns

| Class group | What makes it hard | Redesign | Fix |
|---|---|---|---|
| `NftHook`, `SetupCommand`, `RemoteStart` | Direct nft process, account actions/observations, remote child and request time. | Command-result/deadline, account snapshot, remote byte transport, Clock. | [B663](README.md#b663) |

### Waits it replaces

| Kind | Methods | Most numerous classes (methods) | Replacement / fix |
|---|---:|---|---|
| Real clock | 29 | RemoteStartTest 8; UpstreamRecordsTest 5; SignedArrivalsTest 4; EventsCommandTest 2; MessageSignersTest 2 | Clock.fixed/explicit Instant for events, expiry, file/certificate judgments ([B661](README.md#b661)); remote certificate Clock ([B663](README.md#b663)), arrival verifier/facts ([B658](README.md#b658)). |
| Child process/lock | 11 | ProcessCommandRunnerTest 8; OwnerOnlyFilesTest 1; VaultFileTest 1; HelperPidStopTest 1 | Fake Process/lock/deadline decisions ([B661](README.md#b661), [B663](README.md#b663); helper supervision [B653](README.md#b653)); keep narrow real pipe/exit/lock/umask/child-death contracts. |

## Acceptance

- Each harmful behaviour above has a unit regression seen red first, then the seam or core that makes it green.
- The flows above map their named higher paths to the new unit proof, or keep an explicit irreducible adapter contract.
- The report gives the exact revision, per-level cases and classes, testcase sums and the module's unit run time, measured as [B665](README.md#b665) defines; a duration not measured says so.

## Decided by the operator (2026-11-12)

- Each of the audit's fourteen fixes is its own issue; [B665](README.md#b665) (reproducible levels and times) is built first.
- "Under 60 seconds per module unit run" is a direction that is reported, not a gate.
- The agent drops repeated CLI end-to-end cases itself, once their lower proof is red then green, and lists them in the review.
- The redesigns of `TaskLaunch`, `TreeWalk` and `VaultProxy` run between feature rounds, with no feature issue open in those classes.
