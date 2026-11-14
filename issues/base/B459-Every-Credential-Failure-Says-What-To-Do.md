# B459 — Every Credential's Failure Says What To Do

```json
{
  "$schema": "issue-1.json",
  "id": "B459",
  "title": "Every Credential's Failure Says What To Do",
  "state": "Backlog",
  "size": "L",
  "priority": "Medium",
  "rank": 8,
  "blockedBy": [],
  "covers": "Credential failure inventory, upstream advice, agent readiness and source-aware unlock/storage actions; push/provider paths, frontend agreement, window controls and live remedies still pending."
}
```

**Why this size.** credential checks, upstream reads and pushes, provider grants, host access, and window controls cross modules and repositories.

**What must be true.** Every failure of a credential says what happened and what to do now, with a control for it in a window, and the command at a
terminal. Nothing shows a tool's raw line alone.

The operator's word, 2026-11-05: a rule beyond [B458](README.md#b458). Nothing is built before the test.

## Plan

- **How far it got (back to Backlog, 2026-11-14).** On main: the failure inventory, source-aware unlock and storage
  actions, agent readiness (`b55da5d89`) and the https push slice (`0ed9c2680`). Left: provider failures in a running
  task, the frontend's agreement on `fix` and `storeCommand`, the window's controls, each remedy seen to work on a real
  machine, and the acceptance scenarios. No started work lies on a branch.

1. List the ways a credential fails today, by kind and place: a key refused or locked, a token expired or without the right, a vault locked, an agent
   with no keys, a host key unknown. Each with what a person now reads.
2. Say the size again from that list, and agree the field with Agent Frontend.
3. Each gets its words and its remedy, only where the remedy has been seen to work; where there is none, it says so plainly.

## Read first: failure inventory

This inventory is from reading code; no live credential or remedy was exercised.

| Kind and place | What a person reads today | Existing result and next action |
|---|---|---|
| Unknown or changed SSH host key, `app-model/HostKeys` and `UpstreamProbe` | Never met the host, or its key changed; a changed key is not automatically trusted. | `UNKNOWN_HOST_KEY`, `detail`, `fix`; unknown keys name `porelo credentials trust-host <host>`. A changed key requires investigating why first. |
| Refused SSH key, `app-model/UpstreamProbe` | Which declared source was used, or that SSH chose defaults; daemon limitations. | `KEY_REFUSED`, `detail`, `fix`; vault keys have a store command. File and agent sources have no store command. |
| Refused HTTPS token or password, `app-model/UpstreamProbe` | Explains possible expiration, revocation or missing access; never SSH public-half advice. | Keep `KEY_REFUSED` for compatibility; explain expiration, revocation or missing access without claiming to distinguish them. |
| Failed credential lease or unclassified git failure, `app-model/UpstreamProbe` | Owned prose with an inspection command; untrusted helper and tool output is withheld. | Return owned prose and `porelo credentials list`; never relay untrusted output. |
| Missing value, `app-model/CredentialDeclarations` | Missing file, unset variable, or missing vault entry. | `MISSING_VALUE`; vault entries name `vault put`, other sources need restoring at their source. |
| Expired declaration, `app-model/CredentialDeclarations` | Expiration, inability to renew without the person, and replacement at the declared source. | `EXPIRED` also requires updating the declaration's expiration; a readable file cannot make an expired proposal ready. |
| Locked or absent vault, `app-model/CredentialDeclarations` | Vault shut or unreadable, or no vault yet. | Passphrase vaults name `porelo vault unlock`; host vaults give the client-computer `host unlock` action and its required options; other share-only vaults require an enrolled device. `NO_VAULT` names `porelo vault init`. |
| No keys in the account's SSH agent, `app-model/CredentialDeclarations` and `FollowCredential` | Readiness checks the process's socket and `ssh-add -l`, withholding tool output. | An absent socket needs reconnecting; no identities may mean empty or locked, so the advice gives both possibilities. A failed identity check cannot promise readiness. No private key is copied into the vault. |
| Missing task credential, `app-task/TaskLaunch` and `CredentialWiring` | Missing grant, sign-in or key; an agent start refuses before creating the task. | Existing `Offer` remedy and daemon start needs name the sign-in or authorization command. |
| Provider sign-in expired or revoked, `app-vault/LoginRenewal` | Ended sign-in and a remedy; other purchase failures retain `TokenPurchase.Refused`. | Existing authorization stream raises `ended`; imported sign-ins must not be renewed. |
| Provider denies a running task, `app-task` activity handling | Provider refusal and task activity. | Window must connect this to the credential's authorization or sign-in action; a task's last exit code alone is not a credential diagnosis. |
| Host vault or signing certificate unavailable, `app-base/HostVaultConnection` and task start | Owner unavailable, vault kind mismatch, or missing certificate with start advice. | Keep these distinct from a forge key refusal; a remote certificate needs the client computer's start path. |
| Forge token lacks rights, task build status and organization needs | Existing named missing/locked/forbidden outcomes and required rights. | Reuse those outcomes and their actions; a 403 alone cannot prove expiration. |

## Decisions and local implementation

- Reuse `UpstreamProbe.Answer(cause, detail, fix)` and `CredentialDeclarations.Check(outcome, credential, storeCommand, detail)`.
  No new field or cross-repository contract is agreed here. The daemon already sends these results; terminal renderers already print the remedy.
- Start with the shared upstream probe: tests must reproduce exception/tool-output disclosure and HTTPS advice that sends a token owner to install a public key.
  Preserve refusal classification and do not change which credentials git offers.
- Listing declarations is an inspection step, not a promise that it repairs access. The person must restore or replace a file/environment credential at its source,
  or grant the existing credential the required access. Never tell a token owner to install an SSH public key.
- Proposed and recorded declarations now share the check of the credential actually supplied, rather than letting a record already on disk decide a proposal's readiness.
  Agent listings and checks query identities through the context's command runner; empty and locked agents cannot reliably be distinguished by that query.
- Locked results carry an unlock action, never a storage offer. CLI declarations, upstream refusals and daemon declarations preserve that action, including an empty
  command for a manual client-computer unlock. Task start never offers to copy a declared file or agent key into the vault.
- Keep the existing outcomes and fields. A failed local agent inspection uses `KEY_REFUSED` with owned prose that states what could not be checked; it makes no claim
  that the remote refused a particular key. No credential selection or git lending behavior changes.

## Remaining work and window handoff

- The frontend repository must agree whether existing `fix` and `storeCommand` fields suffice across all entry points; this remains a proposal.
  Show `detail` with a control for the supplied action, allow retry, and open a terminal for interactive commands on the machine named by the result.
  An empty command must show the manual action in `detail`; never invent a remedy or auto-trust a changed host key.
- Complete forward/push failures and running-provider failures after reproducing each behavior. A provider's refusal must identify the affected authorization or sign-in
  action without claiming that every 403 means expiration.
- Check each proposed remedy on a real machine before claiming that it works. No VM or acceptance run is authorized in this worktree.
- The frontend still needs to render unlock, inspection and storage actions according to the returned outcome, preserve manual actions when the command is empty,
  retry the check after the action, and run host-unlock controls on the client computer. Existing `storeCommand` can contain an inspection command such as `ssh-add -l`;
  its presence alone never means a value is missing or should be stored.

## Acceptance

- Unit tests reproduce each changed behavior before its fix. No helper exception or unclassified tool output may expose a credential in an upstream-probe result.
- Extend `acceptance/suite/src/test/resources/org/fuin/porelo/acceptance/credentials/credentials-commands.feature` with refused HTTPS and SSH reads,
  asserting the explanation and next action. Existing unknown-host/fingerprint scenarios cover host trust; `task/task-shut-vault.feature` covers an interactive unlock.
- The full issue remains Implementing until the remaining backend paths, frontend agreement, window controls and observed remedies are complete.

## Local verification

- The disclosure and HTTPS-advice tests failed before the fixes. A separate run exposed the connection-failure branch's raw output before that fix.
- Read-only review found an unsupported claim that nothing was read after a cleanup failure. A test reproduced it before the wording was corrected.
- Unavailable-agent, expired-proposal, locked-vault, incorrect storage-offer and source-repair tests failed before their fixes. CLI declaration and task-start tests
  also reproduced advice that offered vault storage in the wrong state.
- The independent read-only review found that the daemon's changed action selection lost a supplied `fromFile` for a missing value. Both SSH-key and token cases
  reproduced it before the fix preserved the supplied file while retaining unlock/init actions.
- A clean reactor run passes 54 focused tests with the required JDK: `CredentialReadinessTest`, `CredentialVaultReadinessTest`, `CredentialRemedyTest`,
  `CredentialDeclarationsTest`, `CredentialDryRunTest`, `UpstreamProbeTest`, `StoreOfferTest` and the daemon's `CredentialActionTest`. None is skipped.
- The open-empty-vault test fixture now actually opens its isolated vault before checking, and forgets the typed passphrase in `finally`.
- The document-tagged reactor tests pass. Root validation passes the action-pin, shared-rules, citation, README and documentation-site checks.
- The unrestricted unit build stops in unchanged `wire` socket tests: directory ownership checks see the sandbox's root as uid 65534.
  No ownership check was bypassed. The full unit suite, acceptance scenarios, window controls and real-machine remedies remain unverified.
- The https push slice (2026-11-13): a refused https forward came back as git's raw line, with the command, the address and
  what the server echoed. `PushCredentialHelp.refusedToken` now classifies the push's stderr through `UpstreamProbe.answer`
  and answers `PushRefused` (`PUSH_REFUSED`) in owned prose with the declared source and the inspection or store command;
  any other push failure keeps git's words. Red: two of three new `CredentialPushTest` tests on the box
  (buildbox-porelo-744157715f4b-20261113T182226Z.log); green: 95 tests, 0 skipped (buildbox-porelo-6d356a78988e-20261113T182805Z.log).
