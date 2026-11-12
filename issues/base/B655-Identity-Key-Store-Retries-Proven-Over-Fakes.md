# B655 — Identity Key Store Retries Proven Over Fakes

```json
{
  "$schema": "issue-1.json",
  "id": "B655",
  "title": "Identity Key Store Retries Proven Over Fakes",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 64,
  "blockedBy": [
    "B665"
  ],
  "covers": "Identity/key-store retries: IdentityChange, identity commands, KeePassXcKey",
  "openQuestions": 0
}
```

**Size.** M, as the test pyramid audit measured it (2026-11-09), split out by the operator on 2026-11-12 (ST296).

**What must be true.** The flows below are proven first as plain logic behind small interfaces, by unit tests over fakes that run in seconds; only the boundary named under *Retained boundary* stays an integration test, and no higher test that proves a harmful path is removed before its lower proof is seen red, then green.

## Plan

IdentityChange, identity commands, KeePassXcKey; key store, agent-key view, retry journal. Unit red: unfinished first attempt made a key; retry reuses it and creates no second identity.

**Retained boundary and priority.** One OpenSSH/external-store path; replace signed organizations/polling. IdentityRotateCommandTest/IdentityKeePassXcTest large fixtures; duplication/private-material harm.

### Flows it proves lower

| Area | Flow | Proven now (test classes) | Logic holder | Fix |
|---|---|---|---|---|
| Vault and identity | V2: Create, propose, renew and rotate an identity once, validate cards and retry an external key store without exposing private material | `IdentityCreateCommandTest`, `IdentityRotateCommandTest`, `IdentityKeePassXcTest`, `IdentityBackupKeyCommandTest`, `IdentityKeysCommandTest`, `IdentityOrganizationTest`, `IdentityProposeCommandTest`, `IdentityRenewCommandTest`, `IdentityShowCommandTest`, `CardHostCommandTest`, `CardVerifyCommandTest`; lower: IdentityChangeTest covers data rules; command paths still build signed repositories and run helpers. | `IdentityCreateCommand`, `IdentityRotateCommand`, `KeePassXcKey` | [B655](README.md#b655) |

### Classes it redesigns

| Class group | What makes it hard | Redesign | Fix |
|---|---|---|---|
| `KeePassXcKey` | 728 lines; external store, agent observation and partial retries; sleeps at 578–590. | KeyStore, AgentKeyView, retry journal, scheduler; no core key generation. | [B655](README.md#b655) |

### Waits it replaces

| Kind | Methods | Most numerous classes (methods) | Replacement / fix |
|---|---:|---|---|
| Polling/deadline | 154 | IdentityKeePassXcTest 19; AgentSignInRunsTest 11; TaskRunCommandTest 8; PoreloDaemonTest 7; MessageWatchNoticeTest 6 | KeyStore/AgentKeyView/journal ([B655](README.md#b655)); registry/completion ([B659](README.md#b659)); helper/task plans ([B653](README.md#b653)); event source/scheduler/sink/cancellation ([B660](README.md#b660), [B662](README.md#b662)). RepeatedTest retry and ClearanceWiringListeningTest readiness spend 200 ms ([B664](README.md#b664)/[B653](README.md#b653)). KernelKeyringTest's two methods retain kernel TTL/second boundary and 1,200 ms survival integration ([B661](README.md#b661)). |

## Acceptance

- Each harmful behaviour above has a unit regression seen red first, then the seam or core that makes it green.
- The flows above map their named higher paths to the new unit proof, or keep an explicit irreducible adapter contract.
- The report gives the exact revision, per-level cases and classes, testcase sums and the module's unit run time, measured as [B665](README.md#b665) defines; a duration not measured says so.

## Decided by the operator (2026-11-12)

- Each of the audit's fourteen fixes is its own issue; [B665](README.md#b665) (reproducible levels and times) is built first.
- "Under 60 seconds per module unit run" is a direction that is reported, not a gate.
- The agent drops repeated CLI end-to-end cases itself, once their lower proof is red then green, and lists them in the review.
- The redesigns of `TaskLaunch`, `TreeWalk` and `VaultProxy` run between feature rounds, with no feature issue open in those classes.
