# B458 — A Refused Key Gets Help

```json
{
  "$schema": "issue-1.json",
  "id": "B458",
  "title": "A Refused Key Gets Help",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 9,
  "blockedBy": [],
  "covers": "Backend built: selected SSH identities, public key inventory, trial and named refusal; focused guards pass, complete unit verification blocked, window remains."
}
```

**Why this size.** five parts, below; the window is Agent Frontend's `F389`.

**What must be true.** A person whose push is refused for its key is led through it: told what happened and which key was used, shown
their keys, able to try one alone without writing anything, and to keep it for that address. A person who reads only an error has nothing to
go on.

The operator's choice, 2026-11-05. Nothing is built before the test.

## Found

The operator's test of `utils4j` on `0.5.1~snapshot.408`, forwarding approved work. The window showed git's raw line:

    'git --git-dir ... push --end-of-options git@github.com:fuinorg/utils4j.git ...' failed with exit code 128:
    ERROR: Permission to fuinorg/utils4j.git denied to deploy key

`porelo credentials list` named only `ssh://github.com/porelo-ai-test/`, so none was declared for that address, ssh chose a key by itself, and
it offered one GitHub knows as a deploy key without that right.

## What The Window Does

1. It says in plain words what happened and which key was used, by fingerprint and what the forge calls it, or that no credential is declared
   for the address.
2. It lists the person's keys: from the ssh-agent, so also from an open KeePassXC, and from files, reading no secret. Beside them: a key not
   listed? Open KeePassXC, unlock it, refresh.
3. "Try" tests one key alone, by a `git push --dry-run` with only that key, and writes nothing.
4. One click keeps it for that address and forwards again.
5. For what only a terminal can do, the window opens a terminal with the whole command ready; the person starts it. A terminal's own refusal
   gives the same words and that command.

## The Parts Here

- **The refusal's words, S.** For the window and the terminal: the key used, its fingerprint, the forge's word for it, or that none is declared
  and ssh chose by itself; and the command to run. To read first: where the raw line comes from, and whether a forward with no credential
  declared for the address should go on at all.
- **Naming one key of the agent, S.** Today `porelo credentials declare --agent` says "the agent", so the wrong key may still win. It names one by
  fingerprint or public key. The way to read first: the public half kept in a file of its own, named to ssh by `IdentityFile` with
  `IdentitiesOnly=yes`, which picks that key from the agent. Nothing secret is written. Not yet run here, and it must hold wherever a push is made,
  in a task too.
- **The list of keys, XS.** `SshKeys` already lists files by fingerprint and comment, with its daemon call. It gains the agent's, public halves only,
  and which of the two each is.
- **The dry run of one key, S.** A daemon call and a command: `git push --dry-run` with only that key, writing nothing, its answer in the same words.
- **Keeping it and forwarding again, XS.** `CredentialDeclare` with the key as above; the forward is then the call it already is.

The fields are agreed with Agent Frontend in the room after the test. Each warning names only what has been seen to work, by the operator's rule.

The rule beyond this case is [B459](README.md#b459).

## Plan

- `FollowCredential` selects an agent key by fingerprint or public half, places only the public half for SSH,
  and isolates SSH from configured identities. `CredentialDeclarations` keeps that selection in its readable record.
- `SshKeys` adds public identities from the account's SSH agent through the context's command runner; the CLI and
  daemon answer the same inventory, including the source and public half for an agent key.
- `GitCredentials.forPush` separates forwarding from fetching. `VaultGitCredentials` refuses an undeclared or
  unavailable SSH identity before a remote command; `GitGate` uses it for ordinary, signed and automatic forwarding.
- `PushCredentialHelp` runs the trial with `git push --dry-run`, one identity, and hooks disabled. The CLI's
  `credentials try-push` and the daemon's `CredentialTryPush` share it; neither saves a declaration nor publishes work.
- `PushRefused` carries public credential metadata, the forge's known classification and the next command as
  arguments. The terminal renders the same explanation the daemon returns. The window implements its chooser,
  refresh, terminal launch and declare-then-approve flow in its own repository.
- Unit guards first reproduce implicit or wrong identity selection, a missing key falling back, a trial publishing
  or saving a key, lost public inventory and saved selection, and a diagnostic reporting a different identity.

## Decisions

- An SSH forward with no declared identity stops before contacting the forge. Fetches retain their fallback.
- A temporary public `IdentityFile`, `IdentitiesOnly=yes` and `-F /dev/null` isolate a selected agent key; no private
  key is copied. A vault lease also excludes default identity files. The host's gate applies this to a task's forward.
- The forge is called a deploy key only when its diagnostic says so. Other causes are reported as unknown rather
  than inferred from an unsuccessful push. Raw remote output is not returned in the SSH refusal.
- The daemon trial resolves a project's existing mirror, without initializing or fetching it. Its source is AGENT or FILE.

## Acceptance

- The SSH-origin scenario in `acceptance/suite/src/test/resources/org/fuin/porelo/acceptance/project/project-default.feature`
  covers a refused undeclared approval, a trial that leaves the remote branch absent, then keeping one key and forwarding.
  It also checks that the task holds neither the key nor the SSH agent socket. Not run in this checkout.
- Window work: consume `PushRefused`, refresh `SshKeys`, offer `CredentialTryPush`, save the chosen public identity
  with `CredentialDeclare` and call `Approve` again; launch a terminal with the returned command arguments when needed.

## Verification

- The required read-only skill review found four defects. Each was reproduced in a failing unit test and fixed:
  registry edits swapping the selected credential, security-key identities breaking inventory, missing fingerprints
  bypassing the named refusal, and a signed approval suggesting a deleted scratch checkout. The forwarding lease
  now validates and lends one snapshot; signed refusals offer the durable key-list command.
- The focused application guards pass. The gate module's unit tests pass, including its 70 gate cases.
- The full clean unit run stops in the wire module: the socket ancestor guard refuses directories owned by uid 65534
  in this sandbox. The broader application run also finds a fixture writing into read-only `/run/user/<uid>` and
  failing agent/plugin fixture assertions, root-owner checks and a missing generated BOM. Complete unit verification
  is not green; this stays Implementing.
- No acceptance scenario, VM, installed-machine test, window change, commit or push was performed.
