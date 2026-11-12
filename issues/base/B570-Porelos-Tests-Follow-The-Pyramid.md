# B570 — Porelo's Tests Follow The Pyramid

```json
{
  "$schema": "issue-1.json",
  "id": "B570",
  "title": "Porelo's Tests Follow The Pyramid",
  "state": "Analyzed",
  "size": "XL",
  "priority": "Medium",
  "rank": 40,
  "blockedBy": [],
  "covers": "Prove domain flows with unit tests over fakes, redesign classes hard to test alone, and keep narrow integration tests before the CLI's final end-to-end suite.",
  "openQuestions": 5
}
```

**Why this size.** **3 L + 9 M + 2 S**, split into fourteen independently reviewable fixes 1–14 follow-ups, each L or smaller. P-numbers are plan identifiers, not allocated issues; this remains one audit.

Accepted since 2026-11-09; filed 2026-11-09.

**What must be true.** Each domain flow is designed and proven first as plain logic behind small interfaces, with unit tests over fakes that run in seconds. Every module's unit suite runs in under a minute; integration proves real adapters narrowly, and the CLI's deliberately larger end-to-end suite runs last as its own step.

## Why

AGENTS.md, “Testing and machines,” requires many units, fewer integration tests, fewest end-to-end runs: states, choices, limits and failures belong behind small interfaces. Write tests first; reproduce defects at the lowest sufficient level; redesign classes that cannot be tested alone. The supplied `target-pyramid.md` defines **unit about 70–80%, integration 15–20%, end-to-end 5–10% by count**, with no time budgets and an explicit larger CLI exception. Find faults earlier; do not trim CLI coverage to an arbitrary share.

### 1. Suites, counts and measured time

Scope: checkout `6181e7f371b54d1f8621285b80c8ba2cd95e0c84`, **47** reactor entries, **601** reported JUnit classes, the machine probe, **48** features, **176** Scenario/Outline definitions and **265** expanded cases.
Sources: **the newest surefire reports of the developer's worktrees**, selected by modification time per surviving module/class, captured **2026-11-09 16:22:55 UTC**; **the VM chain logs of 2026-11-09**, including final/testhost runs. Removed classes/other repositories are excluded. Historical revisions are combined; no audit tests ran, no exact-checkout pass is claimed, and fresh whole-run wall time is **not measured**.

Levels follow exercised fixtures: unit = fake/value inputs and temporary files, without sockets/processes/external files/clock waits; integration = real composition through commands, encrypted-store consumers, sockets/Git/process/kernel adapters, repository checks or timed polling; end-to-end = VM Cucumber only. A FakeCommandRunner cannot hide another real collaborator; shared fixtures charge all cases. GitGateTest is integration despite its Javadoc; MachineIT is narrow integration. Counts include skips and parameterized invocations; mixed classes appear in both levels, so class columns are not additive.

| Level | Classes represented | Cases | Count share | Reference count share | Measured testcase sum | Measured class-duration contribution and share |
|---|---:|---:|---:|---:|---|---|
| Unit | 385 | 3586 | 48.29% | about 70–80% | 122.017 s | 71.507 s in wholly unit classes; mixed allocation not measured; bounded at 1.44–4.91% |
| Integration | 302 | 3575 | 48.14% | 15–20% | 3532.675 s, plus MachineIT testcase time **not measured** | 1396.728 s in wholly integration classes including the probe; mixed allocation not measured; bounded at 28.08–31.55% |
| End-to-end | 1 suite class / 48 features | 265 source cases | 3.57% | 5–10%; intentional CLI exception | **not measured** per case in the selected VM log | 3,333 s in AcceptanceIT; 67.01% of the comparable class-duration sum |

Local XML totals: **1640.792 class s / 3654.692 testcase s**. **85 mixed classes / 172.586 class s** cannot be allocated exactly; unit/integration bounds assign that duration to either extreme, not a fourth level. The comparable denominator is **4973.821 s**, including probe **0.029 s** and AcceptanceIT **3,333 s**. Cross-run class sums are not build wall time. Concurrent GitTreeWritesContractTest reports **119.686 class s / 1,411.536 testcase s**; local testcase effort is **3.34% unit / 96.66% integration**. Three-level testcase-time share is **not measured** without VM case times. The count pyramid fails; CLI is **3.57%** of cases yet dominates time. Missing isolated flow proof is the fault, not proven CLI excess.

Per module with tests: cells are **classes / cases / testcase s**; final column is XML class s. Zero means no cases, not missing time. Area tests stay in `apps/app`; all omitted reactor entries have zero reported tests.

| Module | Unit | Integration | End-to-end | Class s |
|---|---:|---:|---:|---:|
| `wire` | 10 / 66 / 0.189 | 9 / 57 / 4.711 | 0 / 0 / 0.000 | 5.119 |
| `core` | 33 / 467 / 5.457 | 10 / 58 / 13.274 | 0 / 0 / 0.000 | 22.103 |
| `runtime` | 7 / 201 / 0.476 | 1 / 3 / 0.014 | 0 / 0 / 0.000 | 0.626 |
| `shield` | 9 / 85 / 0.558 | 1 / 2 / 0.077 | 0 / 0 / 0.000 | 0.923 |
| `clearance` | 5 / 42 / 0.141 | 1 / 1 / 0.059 | 0 / 0 / 0.000 | 0.248 |
| `vault` | 22 / 234 / 36.808 | 10 / 71 / 8.827 | 0 / 0 / 0.000 | 46.931 |
| `organization/contract` | 5 / 193 / 0.502 | 0 / 0 / 0.000 | 0 / 0 / 0.000 | 0.682 |
| `organization/git-tree` | 2 / 16 / 0.534 | 9 / 610 / 2372.255 | 0 / 0 / 0.000 | 342.276 |
| `gate` | 2 / 11 / 0.011 | 9 / 119 / 8.404 | 0 / 0 / 0.000 | 8.637 |
| `supervisor` | 11 / 109 / 0.439 | 11 / 158 / 16.980 | 0 / 0 / 0.000 | 17.610 |
| `agents/api` | 16 / 191 / 0.375 | 3 / 36 / 11.177 | 0 / 0 / 0.000 | 11.673 |
| `agents/stub` | 0 / 0 / 0.000 | 1 / 18 / 11.616 | 0 / 0 / 0.000 | 11.695 |
| `builds/api` | 1 / 14 / 0.082 | 2 / 14 / 3.803 | 0 / 0 / 0.000 | 3.910 |
| `builds/stub` | 1 / 6 / 0.097 | 0 / 0 / 0.000 | 0 / 0 / 0.000 | 0.114 |
| `ecosystems/api` | 5 / 51 / 0.069 | 4 / 45 / 37.301 | 0 / 0 / 0.000 | 37.464 |
| `ecosystems/stub` | 1 / 6 / 0.085 | 1 / 1 / 0.435 | 0 / 0 / 0.000 | 0.536 |
| `apps/app` | 204 / 1536 / 64.972 | 194 / 2096 / 860.460 | 0 / 0 / 0.000 | 931.483 |
| `daemon` | 16 / 82 / 6.570 | 13 / 239 / 152.847 | 0 / 0 / 0.000 | 162.726 |
| `hooks` | 3 / 23 / 0.167 | 2 / 2 / 2.540 | 0 / 0 / 0.000 | 2.812 |
| `acceptance/kit` | 14 / 114 / 0.425 | 8 / 14 / 20.555 + not measured (probe) | 0 / 0 / 0.000 | 21.322 |
| `acceptance/suite` | 1 / 2 / 0.108 | 1 / 1 / 0.011 | 1 / 265 / not measured per case (class: 3,333) | 3333.147 |
| `acceptance/legs` | 7 / 56 / 0.413 | 6 / 9 / 0.478 | 0 / 0 / 0.000 | 1.034 |
| `dist/dist-check` | 1 / 1 / 0.005 | 2 / 3 / 0.573 | 0 / 0 / 0.000 | 0.691 |
| `dist/package-check` | 9 / 80 / 3.534 | 4 / 18 / 6.278 | 0 / 0 / 0.000 | 10.059 |

`apps/app` unit testcase sum is **64.972 s**, already over a minute before class/setup allocation; separate unit-only wall time is **not measured**. Default `porelo.tests.excluded=slow` is cleared by `-Pwhole`, adding GitTreeStoreTest **204**, GitTreeWritesContractTest **79**, GitTreeWritesTest **108** cases: **391 cases / 322.239 class s / 2,275.110 testcase s**. It runs no Cucumber/packages/provisioning; Failsafe runs at verify with a host, otherwise skips; MachineIT is separate.
Local skips: SocketContextTest.leavesNothingSetBehind and SocketContextTest.keepsTheProcessSelinuxUserAndReplacesOnlyTheType. VM suite says **7** skips, final Maven aggregate **5**; **two** host-service Examples abort without their environment variable. Other skip identities are **not measured**, not normalized.

VM evidence (UTC, 2026-11-09; STEP→next STEP includes kit/probe/build overhead, never added to class sums):

- `chain-cd63e68d5.log` / `final-cd63e68d/suite.log`: 08:58:05→09:49:48, **3,103 step s / 3,056 class s**, success; `chain-39aa622d5.log` / `final-39aa622d/suite.log`: 10:30:03→11:16:55, **2,812 / 2,771**, newest completed success.
- `chain-9a8d54e4e.log` / `final-9a8d54e4/suite.log`: 11:51:43→12:54:55, **3,792 / 3,732**, failure; `chain-68f59841.log` / `final-68f59841/suite.log`: 13:33:56→14:31:56, **3,480 / 3,333**, newest completed Cucumber, failure.
- `chain-testhost-1fd0367c.log` / `testhost-1fd0367c/suite.log`: 16:00:08→16:00:55, **47 step s**, probe server-identification parse failure, no Cucumber/class measurement; `chain-testhost-1fd0367c-2.log`: no suite STEP, reactor only at capture, times **not measured**.
- Other AcceptanceIT class s: `final-f7fe8ac2` **2,800** success; `final-e1870167` **2,803** failure; `final-9410f162` **1,816** success; `final-d2588b03` **2,111** success. Paired STEP durations **not measured**; no matching `targeted-*` directory or per-scenario timing found. Older filtered local Failsafe is not substituted.
- MachineIT.probesWithTheKeyItAuthenticatedWith: latest **0.029 class s**, one error, testhost log file time **2026-11-09 16:00:55.388689 UTC**; completed `final-68f59841` probe **4.074 class s**. Fake machine/handshake proof must precede its real adapter check (fix 12).

Slowest ten classes by class duration (shared real fixtures count as integration):

| Class | Level | Seconds |
|---|---|---:|
| `AcceptanceIT` | end-to-end | 3333.000 |
| `TaskRunCommandTest` | integration | 196.653 |
| `GitTreeWritesContractTest` | integration | 119.686 |
| `GitTreeWritesTest` | integration | 114.602 |
| `TransportConversationsTest` | integration | 97.486 |
| `GitTreeStoreTest` | integration | 87.951 |
| `MessagingMethodsTest` | integration | 51.274 |
| `TransportLentTest` | integration | 44.940 |
| `ProjectTakingTest` | integration | 38.776 |
| `PoreloDaemonTest` | integration | 36.281 |

### 2. Flows whose complete proof is only higher up

These **35** orchestration families have **2042 distinct named higher-level test methods**, deduplicating parameterizations and cross-family reuse. Shared parsing/data tests and contract stand-ins do not prove production orchestration; not every adapter assertion needs a duplicate unit. Each row retains its current proof classes, logic holders and fix.

| Area | Flow | Proven now (test classes) | Logic holder | Fix |
|---|---|---|---|---|
| Tasks | T1: Create, refuse and unwind a task before and after its container starts | `TaskRunCommandTest`, `StartCheckTest`, `StartProgressReviewTest`; lower: Parameters and branch parsing have unit coverage; they do not prove that refusal creates nothing or that a partial launch unwinds. | `TaskLaunch`, `TaskRunner` | fix 2 |
| Tasks | T2: Resume from durable state, retain tokens and session, order helpers and preserve held work | `TaskLifecycleCommandsTest`, `TaskStateTest`, `TaskRefreshTest`, `TaskUsageTest`, `TaskTargetTest`; lower: TaskSessionTest proves session storage; resume effects and rollback still use helpers and the encrypted store. | `TaskControl`, `TaskState`, `TaskSecrets` | fix 2 |
| Tasks | T3: Stop and remove only the recorded container, account for every surviving helper and keep failed work | `TaskStopSurvivorsTest`; lower: TeardownTest proves the lock; survivor decisions still start real processes. | `TaskControl`, `TaskLifecycle`, `Teardown` | fix 2 |
| Tasks | T4: Checkout, resume without data loss and scope Git credentials and push destinations | `TaskWorkspaceTest`; lower: Keep a narrow real Git push/config test; the choice of branch and credential scope can be isolated. | `TaskWorkspace`, `WorkspaceSetup` | fix 7 |
| Tasks | T5: Follow a run, drain its last lines, handle a slow reader and stop after disconnect | `RunFollowerTest`; lower: RunFollowerPartsTest and RunResultTest cover parsing, not the complete send/drain/close sequence. | `RunFollower`, `RunResult` | fix 9 |
| Agents and credentials | A1: Choose the signed-in agent and provider, tolerate old protocols and bound a discovery failure | `AgentChoiceTest`; lower: Pure wanted/source selection already has tests; discovery and refusal still start an adapter. | `AgentChoice`, `InstalledAgents` | fix 8 |
| Agents and credentials | A2: Observe and store a login while its process runs, wait on one login and end a detached login | `AgentLoginTest`, `AgentSignInRunsTest`; lower: Directory extraction tests cover reading a fixture, not concurrent login completion and vault-lock transitions. | `AgentLogin`, `AgentSignInRuns` | fix 8 |
| Agents and credentials | A3: Import the requested provider, refuse ambiguous or incompatible answers and protect another provider grant | `CredentialImportTest`; lower: The test launches AgentServer instead of handing the importer an extractor fake. | `CredentialImport` | fix 8 |
| Agents and credentials | A4: Renew once under the vault lock, take another broker renewal and retain rotated refresh tokens | `LoginRenewalTest`, `VaultRenewCommandTest`; lower: An injected Clock is already present; every test still starts an HTTP server. | `LoginRenewal` | fix 6 |
| Agents and credentials | A5: Purchase tokens once, renew at the margin, cache a refusal and retire an ended grant | `TokenPurchaseTest`; lower: The suite already advances a Clock, but the exchange is still a real delayed HTTP request. | `TokenPurchase` | fix 6 |
| Agents and credentials | A6: Route and redeem phantom credentials, block minting grants and withhold secrets while streaming | `VaultProxyTest`, `VaultProxyUnixTest`, `VaultProxyKeyPlacementTest`, `VaultProxyRoutesTest`, `VaultProxyUsageTest`; lower: Header, framing and usage parsers have units; stream inspection and route lifecycle stay in a bound proxy. | `VaultProxy` | fix 6 |
| Agents and credentials | A7: Discover OAuth endpoints, exchange a redirect code and distinguish device pending, slowdown and refusal | `ClientRegistrationTest`, `ServiceMetadataTest`, `CodeGrantTest`, `DeviceGrantTest`, `ClientPickTest`, `ServiceRegistrationTest`; lower: DeviceGrant already fakes its sleeper; it still needs a real HTTP server for its state transitions. | `ClientRegistration`, `ServiceMetadata`, `CodeGrant`, `DeviceGrant` | fix 6 |
| Agents and credentials | A8: Start a plugin, validate ready/describe and clean up after timeout, half reply or protocol refusal | `InstalledAgentTest`, `InstalledBuildReaderTest`, `InstalledEcosystemTest`, `InstalledEcosystemsTest`; lower: Keep executable discovery and one wire exchange as integration; classify all timeout variants as unit policy first. | `InstalledAgent`, `InstalledBuildReader`, `InstalledEcosystem`, `InstalledEcosystems` | fix 5 |
| Organization | O1: Walk signed history: founding, roles, revocation, replacement keys, replay, rollback and fork refusal | `GitTreeStoreTest`, `OrgReadingCommandsTest`, `OrgFetchCommandTest`, `OrgCreateCheckTest`; lower: The contract stand-ins test the contract, not TreeWalk; the production walk takes a concrete TreeRepository. | `TreeWalk`, `GitTreeStore`, `TreeRepository` | fix 1 |
| Organization | O2: Authorize and publish writes, preserve unrelated fields, retry a push and avoid duplicate changes | `GitTreeWritesTest`, `OrgWritingCommandsTest`, `OrgSignersCommandTest`, `OrgAddReadKeyCommandTest`, `OrgRemoveReadKeyCommandTest`; lower: The stand-in write contract does not prove the real write planner. | `GitTreeWrites`, `TreeRepository` | fix 1 |
| Organization | O3: Accept only current signed project and repository settings and judge a checkout without trusting its config | `ProjectFileTest`, `ProjectFileCheckoutTest`, `RepositoryFileJudgeTest`, `ProjectSignRepositoryFileCheckoutTest`, `ProjectSignRepositoryFileTest`, `RepositorySettingsSignatureTest`, `CommitVerifyCommandTest`; lower: Repository settings parsing has units; signed history and freshness decisions still use real Git. | `ProjectFile`, `RepositoryFileJudge` | fix 1 |
| Organization | O4: Resolve forge identity, ownership and signing keys with incomplete, refused, oversized or stalled replies | `HttpForgeDirectoryTest`; lower: HttpForgeProfileTest covers one response reader, not the full ownership/key decision paths. | `HttpForgeDirectory` | fix 1 |
| Organization | O5: Take a project using current authority, lend its token, publish its file and refuse before creating side effects | `ProjectTakingTest`; lower: The fixture constructs and signs an entire organization for each policy branch. | `ProjectTaking`, `OrganizationProjects` | fix 1 |
| Gate | G1: Review, approve, reject, restore and refresh exactly the reviewed commit and preserve pending work | `GitGateTest`, `SeveralRepositoriesTest`, `UpstreamDistanceTest`, `GateHostApprovalTest`, `GateReviewCommandTest`, `GateRejectCommandTest`, `GateServeHostPushTest`; lower: ReviewRankingTest proves ranking alone; it does not prove approval races, refusals or branch retention. | `GitGate` | fix 7 |
| Gate | G2: Receive only the task ref, forward atomically, restore a refused ref and bound request resources | `GateForwardedTest`, `GateLimitsTest`, `GateSigningTest`; lower: A GitProcess port exists, but constructing the policy also binds a real HTTP listener. | `GitHttpServer` | fix 7 |
| Gate | G3: Judge certified arrivals by arrival time and principal and reject unsigned or mismatched commits | `SignedArrivalsTest`, `TaskCertificationTest`, `TaskCheckCertificateCommandTest`, `TaskCertificateCommandTest`, `TaskSignerTest`, `TaskSigningTest`, `TaskSigningSecretsTest`; lower: Certificate parsing has units; the end-to-end judgment still invokes Git and ssh-keygen. | `SignedArrivals`, `TaskCertification` | fix 7 |
| Messaging | M1: Enroll, name, admit, join and remove conversation members without losing or falsely recording remote state | `TransportConversationsTest`, `MachineTransportsTest`, `MessagingMethodsTest`; lower: Existing message dispatch/delivery units do not prove remote membership commit/rollback or name reservation. | `TaskConversations`, `Conversations`, `TransportConversations`, `TransportLifecycle` | fix 3 |
| Messaging | M2: Lend transport credentials to one operation and keep them from every other argument, environment and output | `TransportLentTest`, `TransportInputTest`; lower: TransportSendTest covers dispatch using a fake runner; many lending paths still encrypt/decrypt real vaults. | `TransportLifecycle`, `TransportInput`, `TransportSend` | fix 3 |
| Messaging | M3: Notice arrivals while a transport waits, merge moves and close every loop when a task or watcher ends | `MessageWatchLoopsTest`, `MessageWatchNoticeTest`, `ShieldWatchEndsWithItsContainerTest`; lower: MessageWaitPaceTest already proves pause calculation; running watcher lifecycle still sleeps and polls. | `MessageWatch` | fix 9 |
| Daemon and MCP | D1: Limit child starts atomically, enforce project/task scope and strip forbidden run output | `McpTaskSocketsTest`; lower: The tests exercise many branches through a real endpoint; a unit aggregate should own admission and release. | `McpTaskSockets` | fix 11 |
| Daemon and MCP | D2: Reopen task endpoints, reject stale helpers and close or replace sockets in lifecycle order | `McpWiringTest`, `McpSocketsReopenTest`; lower: McpDaemonSocketsTest proves registration with a fake opener; readiness/reopen cleanup still uses processes. | `McpWiring`, `McpTaskSockets` | fix 2; fix 11 |
| Daemon and MCP | D3: Stream only changed task state, tail bounded chunks, replay no old talk and end on client loss | `PoreloDaemonTest`; lower: WatchChangeTest proves equality and StartRepliesTest proves reply order; time-driven delivery still sleeps. | `TaskMethods`, `MessagingMethods`, `PoreloDaemon` | fix 9; fix 11 |
| Vault and identity | V1: Keep and forget a host share in one scope and cancel an unanswered owner exchange | `HostVaultConnectionTest`, `HostVaultSocketTest`; lower: HostVaultKeepTest and HostVaultScopeTest cover selected local rules, not the whole socket cancellation path. | `HostVaultConnection`, `HostVaultOwner`, `VaultMethods` | fix 11 |
| Vault and identity | V2: Create, propose, renew and rotate an identity once, validate cards and retry an external key store without exposing private material | `IdentityCreateCommandTest`, `IdentityRotateCommandTest`, `IdentityKeePassXcTest`, `IdentityBackupKeyCommandTest`, `IdentityKeysCommandTest`, `IdentityOrganizationTest`, `IdentityProposeCommandTest`, `IdentityRenewCommandTest`, `IdentityShowCommandTest`, `CardHostCommandTest`, `CardVerifyCommandTest`; lower: IdentityChangeTest covers data rules; command paths still build signed repositories and run helpers. | `IdentityCreateCommand`, `IdentityRotateCommand`, `KeePassXcKey` | fix 4 |
| Vault and identity | V3: Initialize, unlock, authorize, change a passphrase and clear only when no running task retains a credential | `VaultInitCommandTest`, `VaultAuthorizeCommandTest`, `VaultClearingTest`, `KeyslotsTest`, `VaultPassphraseCommandTest`, `VaultLockCommandTest`, `VaultStatusCommandTest`, `VaultPassphraseStdinTest`, `VaultShareStdinTest`, `VaultLoginCommandTest`, `VaultCredentialAtATerminalTest`, `VaultRenewCommandTest`; lower: VaultFile tests prove encryption; they do not isolate the command/session state machine. | `VaultInitCommand`, `VaultAuthorizeCommand`, `VaultClearing`, `Keyslots` | fix 6; fix 8 |
| Machine and security | S1: Install only into the intended account, verify the effective hooks and refuse or repair stale registration | `SetupCommandTest`, `SetupScriptChannelTest`; lower: HookInstallerTest covers descriptor contents; packaged account/setup flow is still exercised higher up. | `SetupCommand`, `HookInstaller`, `DoctorCommand` | fix 12 |
| Machine and security | S2: Fail closed when nft load fails or hangs and keep the hook failure record | `NftHookTest`; lower: NftRulesetTest proves rule generation, not process timeout and fail-closed hook behavior. | `NftHook`, `Hook` | fix 12 |
| Machine and security | S3: Pin a remote host, relay a start and certify only the named request within its validity window | `RemoteStartTest`, `FollowGitTrustTest`, `TokenFetchOnItsOwnPortTest`, `RemoteStartConnectionTest`, `TaskCertifyingTest`, `TaskCertificateCommandTest`, `TaskCertificateRecordsTest`; lower: Quoting and certificate-word units exist; remote exchange still drives child shells, Git or an agent socket. | `RemoteStart`, `GitCredentials`, `TaskCertifying` | fix 12 |
| Machine and security | S4: Check the pre-push approval guard against unreviewed work and protect only the intended repository | `GateCheckCommandTest`, `GateProtectCommandTest`; lower: The current proof initializes real Git repositories for each decision. | `GateCheckCommand`, `GateProtectCommand` | fix 7 |
| Agents and credentials | A9: Resolve a build query, select its repository and credential and refuse before starting an adapter | `BuildStatusCommandTest`, `BuildTargetTest`; lower: BuildWatchTest proves background verdict processing, not the foreground query and its credential/readiness refusals. | `BuildStatus`, `BuildTarget` | fix 8 |

Feature crosswalk: **U** = unit-backed policy, **P** = partial lower subflow with complete route still higher, **B** = retained boundary/presentation, **H** = no lower proof. U/P do not claim VM effects are unit-testable; B is not automatically a unit gap. Lower proof counts U + P explicitly (104 definitions); B remains separate. Counts are Scenario definitions; cases expand Examples.

| Feature | Scenarios | Cases | Lower proof (U + P) | B | H | Evidence / remaining flow |
|---|---:|---:|---:|---:|---:|---|
| `agents/agent-discovery.feature` | 2 | 2 | 0 + 2 | 0 | 0 | AgentDirectoryTest; gaps A8; logic AgentDirectory |
| `agents/agent-login.feature` | 3 | 3 | 0 + 0 | 0 | 3 | —; gaps A2 |
| `agents/agent-ready.feature` | 5 | 5 | 0 + 0 | 5 | 0 | HeadlessCommandTest, ReadyStepsTest; gaps A8; logic AgentDefinition |
| `agents/agent-session.feature` | 2 | 2 | 0 + 2 | 0 | 0 | TaskSessionTest; gaps T2 ; logic TaskSession |
| `agents/agent-waiting.feature` | 3 | 3 | 3 + 0 | 0 | 0 | AgentWaitingTest, AgentEndingTest; logic AgentEnding |
| `credentials/credential-several.feature` | 3 | 3 | 0 + 3 | 0 | 0 | CredentialWiringTest; gaps A5, T1 |
| `credentials/credentials-commands.feature` | 11 | 17 | 10 + 0 | 1 | 0 | CredentialDeclarationsTest, CredentialReadinessTest, GitCredentialNamesTest; logic CredentialDeclarations, SshKeys, GitCredentialNames |
| `gate/gate-commands.feature` | 5 | 19 | 0 + 0 | 1 | 4 | command parsing/options; gaps S4 |
| `gate/gate-review.feature` | 2 | 2 | 0 + 2 | 0 | 0 | ReviewRankingTest; gaps G1 ; logic ReviewRanking |
| `machine/machine.feature` | 14 | 27 | 1 + 5 | 8 | 0 | DoctorReadinessTest, DoctorCommandTest, DaemonHardeningTest; gaps S1, T3; logic TaskPanic |
| `project/project-commands.feature` | 6 | 7 | 4 + 0 | 2 | 0 | ProjectInventoryTest, ProjectDeletionTest; logic ProjectInventory, ProjectDeletion |
| `project/project-default.feature` | 4 | 4 | 0 + 0 | 0 | 4 | —; gaps T4, G1, S3; logic DefaultProject, SshKeys |
| `project/project-file-check.feature` | 1 | 1 | 1 + 0 | 0 | 0 | ProjectFileCheckTest ; logic ProjectFileCheck |
| `project/project-first-start.feature` | 2 | 2 | 2 + 0 | 0 | 0 | FirstStartTest, FirstStartEcosystemsTest; logic FirstStartAtTerminal |
| `project/project-offline-bundle.feature` | 1 | 1 | 0 + 0 | 0 | 1 | —; gaps G1, T1 |
| `project/project-prune.feature` | 1 | 1 | 1 + 0 | 0 | 0 | PruneTest |
| `project/project-sign-from-checkouts.feature` | 2 | 2 | 0 + 0 | 0 | 2 | —; gaps O3; logic ProjectSignRepositoryFileCommand |
| `project/project-solo.feature` | 1 | 1 | 0 + 0 | 0 | 1 | —; gaps G1 |
| `shield/host-services.feature` | 1 | 2 | 0 + 0 | 1 | 0 | NftRulesetTest, HostServiceWiringTest; logic LoopbackMapping |
| `shield/shield-commands.feature` | 5 | 10 | 4 + 0 | 1 | 0 | DnsPolicyTest, EgressPolicyTest; logic DnsPolicy, EgressSetDirectory, ShieldWatchCommand |
| `talk/talk-commands.feature` | 3 | 17 | 0 + 2 | 1 | 0 | MessageDispatchTest, MessageVerifyTest, MessageBudgetTest; gaps M1; logic MessageDispatch, MessageVerify |
| `task/build-status.feature` | 7 | 9 | 0 + 0 | 0 | 7 | —; gaps A9 |
| `task/task-branch-scheme.feature` | 2 | 2 | 0 + 2 | 0 | 0 | ProjectBranchSchemeTest; gaps T1; logic BranchScheme |
| `task/task-builds.feature` | 1 | 1 | 1 + 0 | 0 | 0 | BuildWatchTest, TaskBuildsTest, BuildWaitTest; logic TaskBuilds ; logic BuildWatch |
| `task/task-certified-by-the-person.feature` | 2 | 2 | 0 + 2 | 0 | 0 | TaskCertificatesTest; gaps T1, G3 |
| `task/task-commands.feature` | 9 | 28 | 0 + 8 | 1 | 0 | RecognizedTasksTest, PreparationTest; gaps T2; logic Preparation |
| `task/task-forged.feature` | 1 | 1 | 1 + 0 | 0 | 0 | RecognizedTasksTest |
| `task/task-guarded.feature` | 10 | 10 | 0 + 4 | 6 | 0 | ContainerSpecTest, NftRulesetTest, DnsPolicyTest, RunningClearanceTest, RunningEgressTest, EgressPolicyTest; gaps T1, T2, A6; logic RunningEgress ; logic NftRuleset |
| `task/task-hand-in.feature` | 1 | 1 | 1 + 0 | 0 | 0 | HandInsTest; logic HandIns |
| `task/task-image-settings.feature` | 1 | 1 | 1 + 0 | 0 | 0 | PreparationTest, ImageSettingsTest; logic Preparation, ImageSettings |
| `task/task-inside.feature` | 5 | 5 | 2 + 3 | 0 | 0 | PodmanTest, LimitsTest; gaps T4, T3; logic Podman ; logic Limits |
| `task/task-keys.feature` | 1 | 1 | 0 + 0 | 1 | 0 | TerminalAttachTest, PodmanTest; logic TerminalAttach, Podman |
| `task/task-left-running.feature` | 2 | 2 | 1 + 1 | 0 | 0 | AgentEndingTest; gaps A6; logic AgentEnding |
| `task/task-outlives-daemon.feature` | 1 | 1 | 0 + 0 | 1 | 0 | —; gaps T1, T2 |
| `task/task-parameters.feature` | 3 | 3 | 0 + 3 | 0 | 0 | ProjectParametersTest; gaps T1 |
| `task/task-reboot-reproduced.feature` | 1 | 1 | 0 + 0 | 0 | 1 | —; gaps T2 |
| `task/task-refused-names.feature` | 2 | 2 | 2 + 0 | 0 | 0 | DnsPolicyTest, RunningEgressTest; logic DnsPolicy, RunningEgress |
| `task/task-refused.feature` | 3 | 3 | 0 + 0 | 0 | 3 | —; gaps T1 |
| `task/task-repository-agent.feature` | 2 | 2 | 0 + 2 | 0 | 0 | ProjectRepositoryAgentTest; gaps T1, A1, T4 |
| `task/task-repository-image.feature` | 5 | 5 | 0 + 5 | 0 | 0 | RepositoryImageReaderTest, SettingsMergeTest, PreparationTest; gaps T1; logic RepositoryOwnSettings, SettingsMerge |
| `task/task-restart-conversation.feature` | 1 | 1 | 0 + 0 | 0 | 1 | —; gaps T2, M1 |
| `task/task-restart.feature` | 4 | 4 | 0 + 4 | 0 | 0 | UnhandedWorkTest; gaps T2; logic UnhandedWork |
| `task/task-session.feature` | 4 | 4 | 0 + 0 | 4 | 0 | TerminalAttachTest; gaps T2, T3; logic TerminalAttach |
| `task/task-shut-vault.feature` | 1 | 1 | 0 + 0 | 0 | 1 | —; gaps T1, V3; logic PoreloContext |
| `task/task-trust-store.feature` | 1 | 1 | 0 + 0 | 1 | 0 | —; logic TrustedCertificates, GitCertificates |
| `task/task-unattended.feature` | 4 | 4 | 1 + 3 | 0 | 0 | TaskLogArchiveTest, RunResultTest; gaps T1, T5; logic TaskLogArchive |
| `terminal.feature` | 7 | 7 | 0 + 0 | 7 | 0 | TerminalAttachTest, RecognizedTasksTest; logic PoreloCli, TerminalAttach |
| `vault/vault-commands.feature` | 18 | 32 | 0 + 15 | 3 | 0 | KeyslotTest, VaultPutCommandTest, VaultUnlockCommandTest; gaps V3, A3; logic CredentialChange |

Totals remain **28 H / 68 P / 36 U / 44 B**, within the three levels. Only H scenarios are named below (feature and Scenario declaration line); all need the indicated flow fix:

- `agents/agent-login.feature:13`: work started before anybody signed in is not started, and is told to sign in first (A2).
- `agents/agent-login.feature:33`: a login is stored the moment it is written, while the agent still runs (A2).
- `agents/agent-login.feature:62`: a login asked for while the vault is shut asks for the passphrase first, then logs in and stores (A2).
- `gate/gate-commands.feature:23`: acting on a push needs to say which one (S4).
- `gate/gate-commands.feature:36`: the pre-push guard stops a push of agent work nobody approved, and nothing else (S4).
- `gate/gate-commands.feature:65`: a push that carries no agent's commit passes the check, quietly when asked to (S4).
- `gate/gate-commands.feature:76`: a gate command about a project this machine does not have names what it has (S4).
- `project/project-default.feature:7`: a task starts in a checkout with no project named, and 'default' cannot be given more (T4; G1; S3).
- `project/project-default.feature:40`: approved work in 'default' reaches an ssh origin with the developer's own key, which the task never holds (T4; G1; S3).
- `project/project-default.feature:125`: a host whose ssh is on its own port is trusted there and named with its port (T4; G1; S3).
- `project/project-default.feature:168`: an upstream that refuses the key is said before anything is made, and reads once the key is stored (T4; G1; S3).
- `project/project-offline-bundle.feature:8`: a local offline project seeded from a bundle starts a task, and its work is backed up (G1; T1).
- `project/project-sign-from-checkouts.feature:7`: the checkouts are named once and kept, and a directory that is no checkout is refused (O3).
- `project/project-sign-from-checkouts.feature:35`: signing from a checkout says what is missing before it reads anything (O3).
- `project/project-solo.feature:8`: approved work lands in the project's own repository on this machine, on a branch of its own (G1).
- `task/build-status.feature:24`: a branch names its commit, verdict, jobs and requested log (A9).
- `task/build-status.feature:50`: a full commit returns the same build as JSON with a failed job's log by default (A9).
- `task/build-status.feature:73`: no logs still returns the job results (A9).
- `task/build-status.feature:90`: a project without builds names the configuration it needs (A9).
- `task/build-status.feature:111`: a missing credential entry names the entry to store (A9).
- `task/build-status.feature:130`: a locked vault names unlock before asking the forge (A9).
- `task/build-status.feature:144`: a refusal from the forge names the retry time (A9).
- `task/task-reboot-reproduced.feature:17`: a restarted task comes back with the tokens its container holds, and says why it was down (T2).
- `task/task-refused.feature:9`: a start names the repository it is for, and a dry run needs none (T1).
- `task/task-refused.feature:20`: a task whose image cannot be built fails, and leaves no helper and no pid behind (T1).
- `task/task-refused.feature:43`: an unattended run with a locked vault is refused, and creates nothing (T1).
- `task/task-restart-conversation.feature:23`: each task continues its conversation after a reboot, and the run nobody watches waits for its next start (T2; M1).
- `task/task-shut-vault.feature:11`: a start on a shut vault asks for the passphrase, starts the task and leaves the vault open (T1; V3).
Reboot, effective firewall, process lifetime, PTY encoding, socket uid and packaged trust stores retain real proof. Other integration contracts cover varlink framing/peers, process replacement/pipes/locks/umask, OpenSSH/certificate/TLS, nft/netlink, package/native execution and source/document architecture. Choices and refusals must be proven below the VM.

### Classes hard to test alone

Size alone is not the finding: effects, absent seams and coupled decisions prevent isolation. Existing injected clocks/runners remain.

| Class group | What makes it hard | Redesign | Fix |
|---|---|---|---|
| `TaskLaunch` | 4,668 lines; creates purchases, wiring, mailboxes/stores; system time; coupled rollback. | Launch journal; AgentCatalog, CredentialStore, Runtime, HelperSupervisor, Clock. | fix 2 |
| `TaskControl` | 1,199 lines; files, ProcessHandle, ProcessBuilder; stop polling at 624–627. | Resume/stop/remove plans over snapshots; state store and supervisor. | fix 2 |
| `TaskRunner` | Direct helper start at 756; hostResolvers reads host state outside FakeCommandRunner. | HostNetworkSnapshot and HelperSupervisor beside CommandRunner. | fix 2 |
| `TaskInventory` | 1,825 lines; AgentWaiting/quiet-log system time, identity/store reads, screen concurrency. | Facts/status readers, Clock, executor; pure inventory projection. | fix 2 |
| `TaskWorkspace` | Branch policy, Git commands and secret configuration require real mirrors. | Workspace Git port; checkout/configuration plan. | fix 7 |
| `RunFollower` | Thread.sleep/System.nanoTime couple polling, final drain, backpressure and close. | LogSource, reply sink, scheduler; next/drain/close transitions. | fix 9 |
| `AgentChoice` | Selection reaches installed discovery and vault. | AgentCatalog and CredentialView; preserve pure wanted/source rules. | fix 8 |
| `AgentLogin` | 1,452 lines; process/container, OAuth, vault observation and two-second sleep. | Session aggregate; process, extracted credential, vault, deadline ports. | fix 8 |
| `AgentSignInRuns` | Static registry, nanoTime, Future waits; sleeping loops at 388–440. | Registry instance, completion events, monotonic scheduler. | fix 8 |
| `CredentialImport` | Concrete installed extractor and encrypted store through context. | CredentialExtractor and vault transaction. | fix 8 |
| `LoginRenewal` | Injectable Clock still accompanies HttpClient and VaultFile. | GrantExchange and transaction ports; keep Clock. | fix 6 |
| `TokenPurchase` | Clock exists; HTTP, synchronized cache/refusals; tests delay handlers 200 ms. | TokenEndpoint; controlled in-flight exchange; keep Clock. | fix 6 |
| `VaultProxy` | 1,506 lines; constructor creates client, checks/chmods paths, binds socket at 487. | Routing/authorization core, incremental inspector, upstream port, server factory. | fix 6 |
| `TreeWalk` | 1,373 lines; concrete TreeRepository requires rebuilt signed history. | HistoryReader, SignatureVerifier; pure TreeModel transitions. | fix 1 |
| `GitTreeStore` | Static Files trust records; walk coupled to fetch/repository. | TrustRecordStore/history adapter; in-memory replay/fork/unavailable facts. | fix 1 |
| `GitTreeWrites` | 1,328 lines; authority, editing, signing, publish and retry rereads coupled. | Authorized planner and compare-and-swap publisher; conflict/uncertain-push fakes. | fix 1 |
| `TreeRepository` | 1,099 lines; temporary Git trees/subprocesses; concrete dependency. | Narrow history/read/publish ports; real Git adapter contracts. | fix 1 |
| `ProjectFile`, `RepositoryFileJudge` | Signatures/freshness coupled to fetch, snippets, Git, remembered disk revisions. | SignedRevision, bounded snippet reader, revision store; pure verdict. | fix 1 |
| `HttpForgeDirectory` | HttpClient/private Answer hide ownership/key decisions; stalled server fixtures. | Bounded HTTP response/deadline port and Clock; profile/key/ownership decoders. | fix 1 |
| `GitGate` | Git calls interleave review, approval, ref comparison, restore/refresh decisions. | Repository snapshot, pending store, upstream/operations ports; pure plans. | fix 7 |
| `GitHttpServer` | HttpServer binds at 231 before authorization, rollback or pacing can be tested. | GateRequestPolicy/RefTransaction; GitProcess and Clock; byte adapter. | fix 7 |
| `SignedArrivals` | ProcessBuilder invokes ssh-keygen at 139 inside arrival judgment. | CertificateVerifier, commit facts, fixed Clock. | fix 7 |
| `TransportLifecycle`, `TransportConversations` | 1,692/1,022 lines; vault, file name reservations and remote verbs mix with membership. | Conversation aggregate; MembershipStore, CredentialLease, Transport fakes. | fix 3 |
| `MessageWatch`, `AgentWake` | 1,215/593 lines; clocks, sleep, directory watches and task loops mix decisions. | Event source, scheduler, inbox store, wake adapter; tick/pass/close. | fix 9 |
| `MessageBudget`, `MessageRecord`, `MessageSay` | Instant.now prevents deterministic midnight/quota/order tests. | Clock at composition; in-memory records and fixed timestamps. | fix 10 |
| `InstalledAgent`, `InstalledBuildReader`, `InstalledEcosystem`, `InstalledEcosystems` | ProcessBuilder discovery/constructors; InstalledAgent starts at 91 and polls readiness. | PluginProcess, framed Connection, scheduler; explicit adapter start. | fix 5 |
| `CredentialWiring`, `GateWiring`, `McpWiring`, `ClearanceWiring` | Construct helpers; sleep while polling sockets/process liveness. | HelperSupervisor/ReadySignal; ready/failed/exited/timed-out values. | fix 2 |
| `McpTaskSockets`, `TaskMethods`, `MessagingMethods` | Daemon state, sockets, scope/capacity and streams share wall-clock waits. | Authorization/capacity core, fake registry/subscriber/scheduler; adapter binding. | fix 11 |
| `HostVaultConnection` | Socket worker, peer verification, exchange and cancellation coupled. | OwnerExchange and deadline/cancellation; retain peer/disconnect contract. | fix 11 |
| `KeePassXcKey` | 728 lines; external store, agent observation and partial retries; sleeps at 578–590. | KeyStore, AgentKeyView, retry journal, scheduler; no core key generation. | fix 4 |
| `BackupRecords`, `EventLog`, `AuthorizationsNeeded`, `TokenBroker` | Instant.now; order/expiry tests sleep or sample unrelated wall time. | Clock/explicit instants; advancing order/expiry/authorization fakes. | fix 10 |
| `NftHook`, `SetupCommand`, `RemoteStart` | Direct nft process, account actions/observations, remote child and request time. | Command-result/deadline, account snapshot, remote byte transport, Clock. | fix 12 |

### 3. Waits, deadlines, clocks and real endpoints

Scan: all Java tests and main logic for sleep, Awaitility/until/poll, bounded await/join/waitFor, Duration.ofSeconds, Instant.now/currentTimeMillis/nanoTime, HTTP/forge/SSH/DNS/loopback and ProcessBuilder. **319 named methods = 268 wait/deadline/network paths + 29 real-clock samples + 22 data/already-faked paths**. Awaitility is not needed for polling; duration/URL input alone proves no wait/network. Configurable zero-delay fixtures are potential waits; delayed invocation counts and aggregate sleep seconds are **not measured**.
The 268 paths below are grouped once by primary evidence: explicit sleep fixture first, endpoint/HTTP next, explicit child/lock next, remaining readiness/completion/deadline loops last. Real-clock samples are additional, not part of 268. Class counts are named methods in that group, not invocations or elapsed time.

| Kind | Methods | Most numerous classes (methods) | Replacement / fix |
|---|---:|---|---|
| Sleep | 63 | VaultProxyTest 45; TokenPurchaseTest 12; EventLogTest 2; AgentWaitingTest 1; TaskListingScreensTest 1 | Controlled upstream reads/exchange (fix 6); Clock for timestamp order, futures/latches/executor for concurrency (fix 10). Proxy delay 300 ms plus SSE 3,000 ms; token handler 200 ms; screen read 200 ms; timestamp sleeps 5 ms. BackupRecordsTest and AuthorizationsNeededTest also sleep 5 ms. |
| Polling/deadline | 154 | IdentityKeePassXcTest 19; AgentSignInRunsTest 11; TaskRunCommandTest 8; PoreloDaemonTest 7; MessageWatchNoticeTest 6 | KeyStore/AgentKeyView/journal (fix 4); registry/completion (fix 8); helper/task plans (fix 2); event source/scheduler/sink/cancellation (fix 9, fix 11). RepeatedTest retry and ClearanceWiringListeningTest readiness spend 200 ms (fix 13/fix 2). KernelKeyringTest's two methods retain kernel TTL/second boundary and 1,200 ms survival integration (fix 10). |
| Real clock | 29 | RemoteStartTest 8; UpstreamRecordsTest 5; SignedArrivalsTest 4; EventsCommandTest 2; MessageSignersTest 2 | Clock.fixed/explicit Instant for events, expiry, file/certificate judgments (fix 10); remote certificate Clock (fix 12), arrival verifier/facts (fix 7). |
| Real endpoint/network | 40 | HttpForgeDirectoryTest 33; CodeGrantTest 5; GrantsTest 2 | Bounded HTTP/ownership/key answers (fix 1); exchange/redirect-listener fakes (fix 6). Forge fixture has configurable stall; only timeout needs delay. GrantsTest resolves auth.invalid and tries a closed loopback port: fake resolver/transport first. |
| Child process/lock | 11 | ProcessCommandRunnerTest 8; OwnerOnlyFilesTest 1; VaultFileTest 1; HelperPidStopTest 1 | Fake Process/lock/deadline decisions (fix 10, fix 12; helper supervision fix 2); keep narrow real pipe/exit/lock/umask/child-death contracts. |

Real network also appears in LoginRenewalTest, OAuth/proxy/gate tests, ProjectTakingTest and identity proposals; varlink/plugin/daemon/SSH-agent suites bind Unix sockets. Git/ssh-keygen/keytool/mkfifo/shell remain integration even in temporary directories. Fake ports cannot prove peer credentials, early HTTP flush, OpenSSH, actual locks or child death; keep one narrow boundary contract.
Helper sites (not extra tests): TestSshAgent startup poll, WatcherLeakGuard.afterEach **50 ms** polling, FakeEcosystem hung branch and nested plugin/agent mains sleeping forever. Production sites: InstalledAgent readiness; AgentSignInRuns/AgentLogin completion; ClearanceWiring/CredentialWiring/McpWiring readiness; TaskControl stop/resume; MessageWatch/AgentWake; RunFollower poll/drain; KeePassXcKey.awaitAgent; Terminal/Repeated/ReadySteps; daemon task/talk/grant streams. Inject time at decisions, never shorten production timeouts for tests.
Keep good seams: DeviceGrant fake sleeper, ReadySteps.Steady explicit Instant, BuildWatchTest clock/reader, BuildWaitTest collected sleeps, MessageWaitPaceTest pause calculation, ClearanceHubTest fake decisions, FirstStartTest detector, SelfReplacementTest supplied observations. Clock injection alone cannot isolate a flow still carried by real HTTP/store.

## Plan

**XL: 3 L + 9 M + 2 S**; fourteen follow-ups, no new issue numbers. S = hours in one area/tests; M = about a day across modules/adapters; L = several days/core design first; none is a single-file XS edit. Order weighs measured cost and harm per effort: trust/data loss, fixtures/secrets, scheduling/selection. **Seconds saved per hour are not measured**; costs are investigative ceilings, not promised savings, and overlapping rows are never added.

| Split | Size | Fix and first red proof | Retained boundary / priority |
|---|---|---|---|
| fix 1 — Organization trust/write cores | L | TreeWalk, TreeRepository, GitTreeStore, GitTreeWrites, ProjectFile, RepositoryFileJudge, HttpForgeDirectory; history/signature/trust/revision/HTTP ports and change planner. Unit red: stale/forked history refuses; conflicted retry publishes once. ProjectTaking/OrganizationProjects use snapshot, reader, token lender, placement ports. | Signature, Git compare-and-swap, adapter contracts; replace repeated history after parity. Three slow classes: 322.239 class s / 2,275.110 testcase s; authority/replay/duplicate-write harm. |
| fix 2 — Task launch/resume/stop | L | TaskLaunch, TaskControl, TaskRunner, TaskInventory, CredentialWiring/GateWiring/McpWiring/ClearanceWiring; plans, journal, helper supervisor, task/credential stores. Unit red: setup fails after creation, all helpers stop, foreign container untouched, held work kept; all T1–T3 refusals/compensations. | One real startup/order/cleanup; VM isolation/reboot. TaskRunCommandTest largest local class; orphan/data-loss harm. |
| fix 3 — Membership/credential lending | M | TransportLifecycle, TransportConversations, Conversations, TaskConversations; transport, membership, lease fakes. Unit red: refused remote part preserves membership; concurrent enrolls reserve distinct names; revoked sender never delivered. | One stdin/environment handoff/rename; replace encrypted-vault/organization fixtures. TransportConversationsTest + TransportLentTest: 142.426 class s; disclosure/revocation. |
| fix 4 — Identity/key-store retries | M | IdentityChange, identity commands, KeePassXcKey; key store, agent-key view, retry journal. Unit red: unfinished first attempt made a key; retry reuses it and creates no second identity. | One OpenSSH/external-store path; replace signed organizations/polling. IdentityRotateCommandTest/IdentityKeePassXcTest large fixtures; duplication/private-material harm. |
| fix 5 — Plugin lifecycle/readiness | M | InstalledAgent, InstalledBuildReader, InstalledEcosystem, InstalledEcosystems; process/connection/scheduler ports. Unit red: half ready line then silence times out, closes once, leaks no reader; fake timeout/never-describe/never-accept variants. | One executable/wire exchange and cancellation/unblock; InstalledEcosystemTest/InstalledEcosystemsTest/InstalledAgentTest cost tens of seconds. |
| fix 6 — Credential exchange/broker | L | TokenPurchase, LoginRenewal, Grants, ClientRegistration, ServiceMetadata, DeviceGrant, CodeGrant, VaultProxy; exchange/listener/transaction/inspection ports. Unit red: minting refused before upstream, split secret withheld, 20 callers cause one exchange. Preserve grant-form/framing units; replace 200/300/3,000 ms waits. | HTTP framing/flush/cancellation/Unix permissions; VM credential isolation. Isolate V3 vault session/store/prompt/inventory states; secrecy outranks unmeasured savings. |
| fix 7 — Gate/workspace plans | M | GitGate, GitHttpServer, SignedArrivals, TaskWorkspace; ref/signature/upstream ports, review/ref/checkout plans; GateCheckCommand/GateProtectCommand use ancestry/approval fakes. Unit red: reviewed commit changes or upstream refuses, no unreviewed forwarding, ref restored. | Real receive/push, bundle restore, signing; VM review. Move refusal/branch/restore matrices below Git; approval race and credential scope harm. |
| fix 8 — Login/import/choice/build query | M | AgentLogin, AgentSignInRuns, AgentChoice, CredentialImport, BuildStatus, BuildTarget; catalog, extractor/store, session registry, reader/scheduler. Unit red: credential stored before process end, locked distinct, import preserves other provider; query refusals precede adapters. | Real agent API extraction, one live watch. AgentLoginTest/AgentChoiceTest/CredentialImportTest repeat processes; queries open encrypted vaults. |
| fix 9 — Watchers/streams | M | MessageWatch, AgentWake, RunFollower, daemon pumps; event source/executor/scheduler. Unit red: arrival during blocked transport delivered once, second move merges, close waits for callback and starts nothing later; futures/latches/ticks replace sleeps. | One WatchService event/client disconnect; VM reconnect/terminal. MessageWatchLoopsTest/RunFollowerTest/notice tests infer order from time. |
| fix 10 — Clock/deterministic waits | M | BackupRecords, EventLog, AuthorizationsNeeded, TokenBroker, MessageBudget/MessageRecord/MessageSay, UpstreamProbe, FirstStart cache, Prune, TaskUsage, certificate requests; Clock/monotonic ticker. Unit red: same-day quota and exact expiry, no sleep; controlled concurrency replaces delay-speed tests. | Narrow tagged kernel expiry/process-lock checks; timestamp races; apps/app unit testcase sum exceeds a minute. |
| fix 11 — Daemon admission/owner/methods | M | McpTaskSockets, HostVaultConnection, VaultMethods, TaskMethods, MessagingMethods; in-memory registry/owner/scheduler and direct policies. Unit red: two starts race for last place; loser frees no winner reservation; forgetting owner cancels exchange without share disclosure. | Peer numeric uid, descriptor ownership, framing, cancellation. Move branch tables below endpoints; scope/capacity harm and stream cost. |
| fix 12 — Machine/hook/remote decisions | M | NftHook, SetupCommand, HookInstaller, RemoteStart, TaskCertifying, machine probe; process-result/deadline, account snapshot, remote bytes, Clock. Unit red: nft timeout refuses/logs; mismatched login PATH refuses install; expired/mismatched request certifies nothing. | Process kill/socket exchange; VM nft/SELinux/account registration. Fail-closed and account-isolation harm. |
| fix 13 — CLI end-to-end last | S | Features, acceptance/kit waits, pipeline: retain intentional CLI share and safety/terminal flows; after red lower proof, reduce repeated refusal/help/completion/wording/terminal cases. Harmful flow first red is unit; wording alone gets no replacement test. Terminal/Repeated use fake Clock/sleeper. | One stream/PTY integration; end-to-end last. Latest completed step 3,480 s / AcceptanceIT 3,333 s is cost, not promised saving. |
| fix 14 — Reproducible levels/times | S | Root/module selection/metadata: separate unit/integration invocations; cases/classes/skips/class/testcase/step times. First check: read-only integration over fixture XML with mixed/parallel cases, no formatter-mirroring test. Re-measure module units under 60 s, then integration/end-to-end. | Surefire currently mixes levels; GitGateTest's own end-to-end label conflicts with this classification. |

For every harmful behavior, see the unit regression fail first, implement the seam/core, retain only necessary real-adapter integration, then selected end-to-end features. Reproduce higher-level defects below; remove no harmful high-level path before lower proof; duplicate no wording-only test. Architecture, redaction and account-isolation guards remain.

## Acceptance

- All 35 families have isolated unit proof for states, choices, limits, refusals, retries and rollback; map named higher paths to it or retain explicit irreducible adapter contracts.
- Update all 176 feature definitions' lower-proof/disposition entries. Critical CLI/terminal/isolation/reboot end-to-end stays last, with its intentional reference-share exception.
- Tests shape proposed ports first; redesign hard classes. No policy/refusal/rollback flow needs a VM for proof.
- Every separately selected module unit run is **under 60 seconds including setup/teardown**. Fresh counts move toward **70–80% unit / 15–20% integration**, with CLI exception; report runtime separately and never call parallel testcase sums wall time.
- Use fake time/transport/store where sufficient; narrow tagged kernel/socket/process/PTY integration remains. No real endpoint proves domain decisions; quota/expiry/order tests never sleep.
- Follow-up reports exact revision, skip identities, per-level cases/classes, testcase sums, class durations and elapsed steps. Missing/filtered/failed-run durations say **not measured**.
- Each fix is red first at the lowest level named in its row, and it reports its module unit run time against fix 14's measure.

## Open questions

Named 2026-11-12 (ST296).
1. **One issue or fourteen.** AGENTS.md has an XL say how it splits. Should fixes 1–14 be filed as their own issues,
   each L or smaller, to be estimated and placed in the backlog one by one? Or should this issue stay as one audit, built in
   the order of its table?
2. **The order.** The table puts trust and data loss first (fix 1, fix 2). Fix 14 (reproducible levels and times) is
   S, and it makes the "under 60 s per module" check measurable. Should fix 14 go first, so that every later fix
   reports against it?
3. **The 60-second budget.** `target-pyramid.md` sets shares by count and no time budget, while this issue sets
   "under 60 s per module including setup". Is that a hard acceptance gate for each fix, or a direction reported
   only?
4. **Removing end-to-end cases (fix 13).** Once a lower proof is red-then-green, does the agent remove a repeated
   CLI case on its own judgment, or does the operator approve each removal from a list?
5. **Large redesigns beside feature work.** Fixes 1, 2 and 6 rework `TaskLaunch`, `TreeWalk` and `VaultProxy`,
   which most feature issues also touch. Should they run between feature rounds, with nothing else open in those
   classes, or interleave with them?
