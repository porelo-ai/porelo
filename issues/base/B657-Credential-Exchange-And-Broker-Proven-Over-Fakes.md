# B657 — Credential Exchange And Broker Proven Over Fakes

```json
{
  "$schema": "issue-1.json",
  "id": "B657",
  "title": "Credential Exchange And Broker Proven Over Fakes",
  "state": "Estimated",
  "size": "L",
  "priority": "Medium",
  "rank": 66,
  "blockedBy": [
    "B665"
  ],
  "covers": "Credential exchange/broker: TokenPurchase, LoginRenewal, Grants, ClientRegistration, ServiceMetadata, DeviceGrant, CodeGrant, VaultProxy",
  "openQuestions": 0
}
```

**Size.** L, as the test pyramid audit measured it (2026-11-09), split out by the operator on 2026-11-12 (ST296).

**What must be true.** The flows below are proven first as plain logic behind small interfaces, by unit tests over fakes that run in seconds; only the boundary named under *Retained boundary* stays an integration test, and no higher test that proves a harmful path is removed before its lower proof is seen red, then green.

## Plan

TokenPurchase, LoginRenewal, Grants, ClientRegistration, ServiceMetadata, DeviceGrant, CodeGrant, VaultProxy; exchange/listener/transaction/inspection ports. Unit red: minting refused before upstream, split secret withheld, 20 callers cause one exchange. Preserve grant-form/framing units; replace 200/300/3,000 ms waits.

**Retained boundary and priority.** HTTP framing/flush/cancellation/Unix permissions; VM credential isolation. Isolate V3 vault session/store/prompt/inventory states; secrecy outranks unmeasured savings.

### Flows it proves lower

| Area | Flow | Proven now (test classes) | Logic holder | Fix |
|---|---|---|---|---|
| Agents and credentials | A4: Renew once under the vault lock, take another broker renewal and retain rotated refresh tokens | `LoginRenewalTest`, `VaultRenewCommandTest`; lower: An injected Clock is already present; every test still starts an HTTP server. | `LoginRenewal` | [B657](README.md#b657) |
| Agents and credentials | A5: Purchase tokens once, renew at the margin, cache a refusal and retire an ended grant | `TokenPurchaseTest`; lower: The suite already advances a Clock, but the exchange is still a real delayed HTTP request. | `TokenPurchase` | [B657](README.md#b657) |
| Agents and credentials | A6: Route and redeem phantom credentials, block minting grants and withhold secrets while streaming | `VaultProxyTest`, `VaultProxyUnixTest`, `VaultProxyKeyPlacementTest`, `VaultProxyRoutesTest`, `VaultProxyUsageTest`; lower: Header, framing and usage parsers have units; stream inspection and route lifecycle stay in a bound proxy. | `VaultProxy` | [B657](README.md#b657) |
| Agents and credentials | A7: Discover OAuth endpoints, exchange a redirect code and distinguish device pending, slowdown and refusal | `ClientRegistrationTest`, `ServiceMetadataTest`, `CodeGrantTest`, `DeviceGrantTest`, `ClientPickTest`, `ServiceRegistrationTest`; lower: DeviceGrant already fakes its sleeper; it still needs a real HTTP server for its state transitions. | `ClientRegistration`, `ServiceMetadata`, `CodeGrant`, `DeviceGrant` | [B657](README.md#b657) |
| Vault and identity | V3: Initialize, unlock, authorize, change a passphrase and clear only when no running task retains a credential | `VaultInitCommandTest`, `VaultAuthorizeCommandTest`, `VaultClearingTest`, `KeyslotsTest`, `VaultPassphraseCommandTest`, `VaultLockCommandTest`, `VaultStatusCommandTest`, `VaultPassphraseStdinTest`, `VaultShareStdinTest`, `VaultLoginCommandTest`, `VaultCredentialAtATerminalTest`, `VaultRenewCommandTest`; lower: VaultFile tests prove encryption; they do not isolate the command/session state machine. | `VaultInitCommand`, `VaultAuthorizeCommand`, `VaultClearing`, `Keyslots` | [B657](README.md#b657); [B659](README.md#b659) |

### Classes it redesigns

| Class group | What makes it hard | Redesign | Fix |
|---|---|---|---|
| `LoginRenewal` | Injectable Clock still accompanies HttpClient and VaultFile. | GrantExchange and transaction ports; keep Clock. | [B657](README.md#b657) |
| `TokenPurchase` | Clock exists; HTTP, synchronized cache/refusals; tests delay handlers 200 ms. | TokenEndpoint; controlled in-flight exchange; keep Clock. | [B657](README.md#b657) |
| `VaultProxy` | 1,506 lines; constructor creates client, checks/chmods paths, binds socket at 487. | Routing/authorization core, incremental inspector, upstream port, server factory. | [B657](README.md#b657) |

### Waits it replaces

| Kind | Methods | Most numerous classes (methods) | Replacement / fix |
|---|---:|---|---|
| Sleep | 63 | VaultProxyTest 45; TokenPurchaseTest 12; EventLogTest 2; AgentWaitingTest 1; TaskListingScreensTest 1 | Controlled upstream reads/exchange ([B657](README.md#b657)); Clock for timestamp order, futures/latches/executor for concurrency ([B661](README.md#b661)). Proxy delay 300 ms plus SSE 3,000 ms; token handler 200 ms; screen read 200 ms; timestamp sleeps 5 ms. BackupRecordsTest and AuthorizationsNeededTest also sleep 5 ms. |
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
