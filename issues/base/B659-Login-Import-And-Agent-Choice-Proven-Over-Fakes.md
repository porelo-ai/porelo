# B659 — Login, Import And Agent Choice Proven Over Fakes

```json
{
  "$schema": "issue-1.json",
  "id": "B659",
  "title": "Login, Import And Agent Choice Proven Over Fakes",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 68,
  "blockedBy": [
    "B665"
  ],
  "covers": "Login/import/choice/build query: AgentLogin, AgentSignInRuns, AgentChoice, CredentialImport, BuildStatus, BuildTarget",
  "openQuestions": 0
}
```

**Size.** M, as the test pyramid audit measured it (2026-11-09), split out by the operator on 2026-11-12 (ST296).

**What must be true.** The flows below are proven first as plain logic behind small interfaces, by unit tests over fakes that run in seconds; only the boundary named under *Retained boundary* stays an integration test, and no higher test that proves a harmful path is removed before its lower proof is seen red, then green.

## Plan

AgentLogin, AgentSignInRuns, AgentChoice, CredentialImport, BuildStatus, BuildTarget; catalog, extractor/store, session registry, reader/scheduler. Unit red: credential stored before process end, locked distinct, import preserves other provider; query refusals precede adapters.

**Retained boundary and priority.** Real agent API extraction, one live watch. AgentLoginTest/AgentChoiceTest/CredentialImportTest repeat processes; queries open encrypted vaults.

### Flows it proves lower

| Area | Flow | Proven now (test classes) | Logic holder | Fix |
|---|---|---|---|---|
| Agents and credentials | A1: Choose the signed-in agent and provider, tolerate old protocols and bound a discovery failure | `AgentChoiceTest`; lower: Pure wanted/source selection already has tests; discovery and refusal still start an adapter. | `AgentChoice`, `InstalledAgents` | [B659](README.md#b659) |
| Agents and credentials | A2: Observe and store a login while its process runs, wait on one login and end a detached login | `AgentLoginTest`, `AgentSignInRunsTest`; lower: Directory extraction tests cover reading a fixture, not concurrent login completion and vault-lock transitions. | `AgentLogin`, `AgentSignInRuns` | [B659](README.md#b659) |
| Agents and credentials | A3: Import the requested provider, refuse ambiguous or incompatible answers and protect another provider grant | `CredentialImportTest`; lower: The test launches AgentServer instead of handing the importer an extractor fake. | `CredentialImport` | [B659](README.md#b659) |
| Vault and identity | V3: Initialize, unlock, authorize, change a passphrase and clear only when no running task retains a credential | `VaultInitCommandTest`, `VaultAuthorizeCommandTest`, `VaultClearingTest`, `KeyslotsTest`, `VaultPassphraseCommandTest`, `VaultLockCommandTest`, `VaultStatusCommandTest`, `VaultPassphraseStdinTest`, `VaultShareStdinTest`, `VaultLoginCommandTest`, `VaultCredentialAtATerminalTest`, `VaultRenewCommandTest`; lower: VaultFile tests prove encryption; they do not isolate the command/session state machine. | `VaultInitCommand`, `VaultAuthorizeCommand`, `VaultClearing`, `Keyslots` | [B657](README.md#b657); [B659](README.md#b659) |
| Agents and credentials | A9: Resolve a build query, select its repository and credential and refuse before starting an adapter | `BuildStatusCommandTest`, `BuildTargetTest`; lower: BuildWatchTest proves background verdict processing, not the foreground query and its credential/readiness refusals. | `BuildStatus`, `BuildTarget` | [B659](README.md#b659) |

### Classes it redesigns

| Class group | What makes it hard | Redesign | Fix |
|---|---|---|---|
| `AgentChoice` | Selection reaches installed discovery and vault. | AgentCatalog and CredentialView; preserve pure wanted/source rules. | [B659](README.md#b659) |
| `AgentLogin` | 1,452 lines; process/container, OAuth, vault observation and two-second sleep. | Session aggregate; process, extracted credential, vault, deadline ports. | [B659](README.md#b659) |
| `AgentSignInRuns` | Static registry, nanoTime, Future waits; sleeping loops at 388–440. | Registry instance, completion events, monotonic scheduler. | [B659](README.md#b659) |
| `CredentialImport` | Concrete installed extractor and encrypted store through context. | CredentialExtractor and vault transaction. | [B659](README.md#b659) |

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
