# B133 — A Shut Vault Opened Where It Is Needed

**Status:** Implementing.
**Size:** S - left is one acceptance scenario with the kit's terminal steps, no code; the whole was M.

**What must be true.** At a terminal, a command that needs a shut vault asks for the passphrase itself and carries
on. Nobody is sent off to type `porelo vault unlock` and then the first command again. Without a terminal it still
refuses and names `porelo vault unlock`.

## Why

Asked on 2026-10-09: when Porelo knows the vault has to be opened, it should open it in the same command. Today
some commands ask and others refuse with "'porelo vault unlock'". `vault login` even runs the whole login first and
fails afterwards: "logged in, but the vault is locked". With `task start` offering `vault authorize`, a first start on a fresh
machine becomes one command: the passphrase, the grant, the task.

## Where it is today

**Already asking for this one command** (`PoreloContext.requirePassphrase()`: the keyring, then `Vault passphrase:`):
`vault put`, `vault authorize`, `vault import`, `vault list`, `vault remove`, `credentials deploy-key`.

**Refusing on a shut vault, at a terminal too** (in `porelo` at `b672aba4`):

    task start           StartCheck, CredentialWiring    "'porelo vault unlock' and start it again"
    task run             TaskRunCommand                  the tasks need their tokens, the vault is shut
    task resume          TaskResumeCommand               "Unlock it with 'porelo vault unlock' ..."
    task watch-builds    TaskWatchBuildsCommand          "the vault is shut, so Porelo can ..."
    vault login          AgentLogin                      after the login: "logged in, but the vault is locked"
    vault revoke         VaultRevokeCommand              "unlock it first: porelo vault unlock"
    projects follow,     Reconcile                       a private repository: "This account's vault is shut,
      projects refresh                                   so nothing here holds a credential"
    clear, prune         Clearing, VaultClearing, Prune  "'porelo vault unlock' and clear again"
    providers            ProvidersCommand                hides what the vault holds, names the unlock
    the messaging        TransportLifecycle,             a transport and its conversations wait; these run in
      transport          Conversations                   the daemon, without a terminal

`doctor` reports a shut vault and names the unlock; that is a report, and stays one. `vault passphrase` names
`vault unlock` with the new passphrase after a change; that stays too.

## The shape

1. **One place decides, `PoreloContext`:** a command that needs the vault asks for an opener. If the vault is shut
   and standard input and output are a terminal, it asks `Vault passphrase:` once, as `requirePassphrase()` does
   today. Without a terminal it refuses with the words of today, naming `porelo vault unlock`, so a script, a pipe,
   the daemon and the watchers behave as now.
2. **For this command only, or for the time `vault unlock` gives.** Commands that need the vault only while they run
   (`vault revoke`, `providers`, `clear`, `prune`, `projects follow`/`refresh`, `task watch-builds`) open it for
   that run. `task start`, `task run` and `task resume` need it afterwards, since the broker spends the tokens for
   the task's life. For them the answer unlocks the vault **as `porelo vault unlock` does, for the same time**. The
   prompt says so: `Vault passphrase (unlocks it for <time>, as 'porelo vault unlock'):`.
3. **`vault login` asks before the login.** It checks the vault first, asks if it is shut, and only then starts the
   login container. A wrong passphrase stops it before anything ran.
4. **A wrong passphrase** is asked again up to three times at a terminal, then refused as `vault unlock` refuses.
5. **The docs:** "Porelo asks for the passphrase when the vault is shut" instead of "has to be open"
   (`doc/credentials.md`, `doc/commands.md`). `porelo vault unlock` stays, for scripts and for opening it ahead of
   time.

## Acceptance

- At a terminal on a shut vault: `task start --provider <p>` asks once and starts the task, and the vault is still
  open afterwards for the time `vault unlock` gives. `vault login` asks before its container starts. `vault revoke`
  and `providers` ask and do their work, and the vault is shut again afterwards. Seen to fail first: today's
  refusals, in the command tests with a terminal stood in.
- Without a terminal (standard input not a TTY): the same commands refuse as today and name `porelo vault unlock`,
  and nothing waits for input.
- The acceptance kit's step that types `porelo vault unlock` before a start is kept for the no-terminal path, and one
  scenario starts a task on a shut vault by answering the prompt.

## As built, 2026-10-09

- **One place:** `PoreloContext.openerAsking(unlock, err)` and `openIfShut(needed, unlock, err)`. A `VaultPrompt` asks
  only when standard input and output are a terminal (`Console.isTerminal()`); `real()` has it, every context a
  test builds has `VaultPrompt.NOBODY`. A passphrase typed once serves the rest of the process; three wrong ones,
  and the command refuses as before. With `unlock`, the answer goes into the kernel keyring as `vault unlock`
  without `--for` puts it.
- **Asking now:** `task start` (only where the start reads the vault), `task run`, `task resume` (the decision is
  made again once it is open), `task watch-builds` started by hand, `vault login` (before its container; a vault
  that stays shut refuses before the login, never after), `vault revoke`, `clear`, `project clear`,
  `vault clear`, `prune`, `providers`, and the credential of `projects follow`/`refresh` for a private repository.
- **Unchanged:** the daemon, the watchers and a pipe have no terminal and refuse as before; `doctor` and
  `vault passphrase` report.
- **Tested:** `OpenerAskingTest` (no terminal, right, wrong twice, three wrong, unlock kept, nothing asked when open
  or absent, asked only where needed). **Open:** the suite scenario that starts a task on a shut vault by answering
  the prompt.

## Plan

Built and on main (`95ff9462`): `PoreloContext.openerAsking` and `openIfShut`, `VaultPrompt`, every command listed
above, `OpenerAskingTest`; the retry of a keyring that did not answer is on `main` too.
The no-terminal path is already a scenario (`task/task-refused.feature`, "the vault is locked", no mirror, no
container) and the login's is too (`agents/agent-login.feature`, asked before the login). Left: the start at a terminal.

1. A scenario in `acceptance/suite/.../task/task-refused.feature`, or a file `task-shut-vault.feature` beside it,
   with the kit's steps as they are (`TerminalSteps`): a vault of its own holding the fake credential as
   `anthropic`, a project, `a terminal on the machine`, `I run "porelo vault lock"`, `I run "porelo task start --project
   <p> --repository <p> --agent stub --attach shell --detach"`; then the terminal shows `Vault passphrase (unlocks it
   for`, `I type "acceptance"`, and the task is listed. Then a script, without a terminal, runs `porelo vault list` and
   it lists `anthropic`: the vault is still open, as `vault unlock` leaves it.
2. Run on the VM with `not @restart`; green there closes the issue.

Red first: the scenario run against the commit before `95ff9462` stops at "the vault is locked" instead of the prompt.
