# B654 — Membership And Credential Lending Proven Over Fakes

```json
{
  "$schema": "issue-1.json",
  "id": "B654",
  "title": "Membership And Credential Lending Proven Over Fakes",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 63,
  "blockedBy": [
    "B665"
  ],
  "covers": "Membership/credential lending: TransportLifecycle, TransportConversations, Conversations, TaskConversations",
  "openQuestions": 0
}
```

**Size.** M, as the test pyramid audit measured it (2026-11-09), split out by the operator on 2026-11-12 (ST296).

**What must be true.** The flows below are proven first as plain logic behind small interfaces, by unit tests over fakes that run in seconds; only the boundary named under *Retained boundary* stays an integration test, and no higher test that proves a harmful path is removed before its lower proof is seen red, then green.

## Plan

TransportLifecycle, TransportConversations, Conversations, TaskConversations; transport, membership, lease fakes. Unit red: refused remote part preserves membership; concurrent enrolls reserve distinct names; revoked sender never delivered.

**Retained boundary and priority.** One stdin/environment handoff/rename; replace encrypted-vault/organization fixtures. TransportConversationsTest + TransportLentTest: 142.426 class s; disclosure/revocation.

### Flows it proves lower

| Area | Flow | Proven now (test classes) | Logic holder | Fix |
|---|---|---|---|---|
| Messaging | M1: Enroll, name, admit, join and remove conversation members without losing or falsely recording remote state | `TransportConversationsTest`, `MachineTransportsTest`, `MessagingMethodsTest`; lower: Existing message dispatch/delivery units do not prove remote membership commit/rollback or name reservation. | `TaskConversations`, `Conversations`, `TransportConversations`, `TransportLifecycle` | [B654](README.md#b654) |
| Messaging | M2: Lend transport credentials to one operation and keep them from every other argument, environment and output | `TransportLentTest`, `TransportInputTest`; lower: TransportSendTest covers dispatch using a fake runner; many lending paths still encrypt/decrypt real vaults. | `TransportLifecycle`, `TransportInput`, `TransportSend` | [B654](README.md#b654) |

### Classes it redesigns

| Class group | What makes it hard | Redesign | Fix |
|---|---|---|---|
| `TransportLifecycle`, `TransportConversations` | 1,692/1,022 lines; vault, file name reservations and remote verbs mix with membership. | Conversation aggregate; MembershipStore, CredentialLease, Transport fakes. | [B654](README.md#b654) |

## Acceptance

- Each harmful behaviour above has a unit regression seen red first, then the seam or core that makes it green.
- The flows above map their named higher paths to the new unit proof, or keep an explicit irreducible adapter contract.
- The report gives the exact revision, per-level cases and classes, testcase sums and the module's unit run time, measured as [B665](README.md#b665) defines; a duration not measured says so.

## Decided by the operator (2026-11-12)

- Each of the audit's fourteen fixes is its own issue; [B665](README.md#b665) (reproducible levels and times) is built first.
- "Under 60 seconds per module unit run" is a direction that is reported, not a gate.
- The agent drops repeated CLI end-to-end cases itself, once their lower proof is red then green, and lists them in the review.
- The redesigns of `TaskLaunch`, `TreeWalk` and `VaultProxy` run between feature rounds, with no feature issue open in those classes.
