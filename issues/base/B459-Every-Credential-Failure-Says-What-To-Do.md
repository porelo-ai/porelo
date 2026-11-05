# B459 — Every Credential's Failure Says What To Do

**Status:** Implementing.
**Size:** L - credential checks, upstream reads and pushes, provider grants, host access, and window controls cross modules and repositories.

**What must be true.** Every failure of a credential says what happened and what to do now, with a control for it in a window, and the command at a
terminal. Nothing shows a tool's raw line alone.

The operator's word, 2026-11-05: a rule beyond [B458](README.md#b458). Nothing is built before the test.

## Plan

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
| Expired declaration, `app-model/CredentialDeclarations` | Expiration and inability to renew without the person. | `EXPIRED`; a vault source names `vault put`; file, variable and agent sources have no command. |
| Locked or absent vault, `app-model/CredentialDeclarations` | Vault shut or no vault yet. | `VAULT_LOCKED` currently has no command; `NO_VAULT` names `porelo vault init`. Reads also fold an unreadable vault into locked. |
| No keys in the account's SSH agent, `app-model/CredentialDeclarations` and `FollowCredential` | An agent declaration is assumed ready; the remote refusal then supplies advice. | Agent readiness does not query identities. Distinguish an absent socket, locked agent and empty agent before promising a key. |
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

## Remaining work and window handoff

- The frontend repository must agree whether existing `fix` and `storeCommand` fields suffice across all entry points; this remains a proposal.
  Show `detail` with a control for the supplied action, allow retry, and open a terminal for interactive commands on the machine named by the result.
  An empty command must show the manual action in `detail`; never invent a remedy or auto-trust a changed host key.
- Complete locked-vault and expired-source remedies, SSH-agent identity readiness, forward/push failures, and running-provider failures after reproducing each behavior.
  Do not send a locked-vault check into the CLI's missing-value `vault put` offer: unlock and recheck first.
- Check each proposed remedy on a real machine before claiming that it works. No VM or acceptance run is authorized in this worktree.

## Acceptance

- Unit tests reproduce each changed behavior before its fix. No helper exception or unclassified tool output may expose a credential in an upstream-probe result.
- Extend `acceptance/suite/src/test/resources/org/fuin/porelo/acceptance/credentials/credentials-commands.feature` with refused HTTPS and SSH reads,
  asserting the explanation and next action. Existing unknown-host/fingerprint scenarios cover host trust; `task/task-shut-vault.feature` covers an interactive unlock.
- The full issue remains Implementing until the remaining backend paths, frontend agreement, window controls and observed remedies are complete.

## Local verification

- The disclosure and HTTPS-advice tests failed before the fixes. A separate run exposed the connection-failure branch's raw output before that fix.
- Read-only review found an unsupported claim that nothing was read after a cleanup failure. A test reproduced it before the wording was corrected.
- A clean reactor run of `UpstreamProbeTest` and `CredentialDeclarationsTest` passes with the required JDK; no test in that run is skipped.
- The unrestricted unit build stops in unchanged `wire` socket tests: directory ownership checks see the sandbox's root as uid 65534.
  No ownership check was bypassed. The full unit suite, acceptance scenarios, window controls and real-machine remedies remain unverified.
