# B158 — A Refusal That Knows The Next Command Offers It

**Status:** Review. Green in porelo's Build 39083121366 on `842ff109`, on both rented legs, 2026-10-18.
**Size:** M - several command modules over one helper; what is left is one test, under an hour.

**What must be true.** Where a command refuses only to tell the person which command to run next, and Porelo already
knows that command's answer, it asks at a terminal and does it, instead of making the person type it. Without a
terminal it refuses as before, naming the commands.

## Why

Found in the joint test, 2026-10-10:

    $ porelo vault login claude
    porelo: 'claude' is already signed in on this machine. Copy what it has with 'porelo vault import claude',
    which logs in nowhere - or pass --force to authorize again, which may or may not invalidate the
    credential already here

The person had to read the sentence, then type one of two commands that Porelo had just named. [B133](README.md#b133) does the same for a
shut vault, and `task start` already does it for a grant that is missing, offering `vault authorize`.

## The shape

- `vault login <agent>` on an agent already signed in asks at a terminal: import what it has (the default, as it logs
  in nowhere), log in again (`--force`'s meaning, said with its risk), or cancel; then does it.
- Without a terminal, or with `--force`, nothing is asked.
- The same rule for the other refusals of this kind, each where the next step is unambiguous and not destructive:
  - a task's start refused for a missing grant, sign-in or key: `Grant it first with 'porelo vault authorize …'`,
    `Sign in first with 'porelo vault login …'`, `Store its key first with 'porelo vault put …'` (`TaskLaunch`);
  - `task resume` of a task whose vault is shut, `Unlock it with 'porelo vault unlock'` (`TaskResumeCommand`), where
    [B133](README.md#b133) has not reached yet.
- Not of this kind, and left as refusals: `task remove` of a running task (stopping is a decision), and a start whose
  name has earlier work waiting (approve or reject is a review, not a command to run).

## Plan

Built and on main:

- The rule is a decision now, "Porelo offers what it would tell a person to type" in `doc/decisions.md`, through one
  helper, `Offer` (`apps/app-base/src/main/java/org/fuin/porelo/app/Offer.java`).
- `vault login` on an agent already signed in: `VaultLoginCommand` offers the import, a login again or nothing
  (`VaultLoginOfferTest`, seen red first).
- A start refused for a missing grant, sign-in or key: `TaskLaunch.credentialRemedy` offers the very command the
  refusal names - `vault authorize`, `vault login` or `vault put` - and the start goes on once the credential is
  there (`CredentialOfferTest`, seen red first). Without a terminal the refusal is word for word as before.
- `task resume` of a task whose vault is shut: `TaskResumeCommand.resume` asks for the passphrase through
  `PoreloContext.openIfShut` and decides again once it is open, as [B133](README.md#b133) built it.

Left: the test the acceptance asks for the resume. In `apps/app`, a task whose resume answers `NEEDS_VAULT`, a
`VaultPrompt` stood in that gives the passphrase: the task resumes; with `VaultPrompt.NOBODY` the refusal still says
`Unlock it with 'porelo vault unlock'`. It may be green at once, as the code is there; then it is kept as the guard.

## Acceptance

- `vault login`: seen red first with a terminal stood in - the import is offered and runs, "again" logs in with
  `--force`'s meaning, "cancel" does nothing; without a terminal the refusal names both commands as before.
- Each further refusal above gets the same test when it is built.
