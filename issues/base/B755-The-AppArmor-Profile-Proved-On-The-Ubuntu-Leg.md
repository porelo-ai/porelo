# B755 — The AppArmor Profile Proved On The Ubuntu Leg

```json
{
  "$schema": "issue-1.json",
  "id": "B755",
  "title": "The AppArmor Profile Proved On The Ubuntu Leg",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 54,
  "blockedBy": ["B752", "B753", "B754"],
  "covers": "An acceptance scenario on the ubuntu leg shows the task profile loaded and compiled under abi 5.0, doctor's AppArmor line for a machine-wide install against an account install, and tasks that start and reach their vault socket.",
  "openQuestions": 0
}
```

**Why this size.** M: a new scenario and its steps in the acceptance kit, the leg installed once machine-wide with the
profiles loaded and once in the account, and runs that reboot the machine (`@restart`). It shrank with the stand-in
binder gone, which moved to [B757](README.md#b757), but the two installs and the reboots keep it an M. Estimate: 63
minutes (Core's median for M), p80 229.

**What must be true.** What [B753](README.md#b753) and [B754](README.md#b754) deliver is shown on a real Ubuntu
machine as a person installs Porelo, not only in unit tests.

Split from [B175](README.md#b175)'s step 9, whose step 2 asked for this scenario.

**Decided 2026-11-13 (operator).** One socket reaches a task, the vault's, and it is a file in the task's mount
namespace ([B752](README.md#b752)). There are no ssh-agent or mailbox sockets. AppArmor's peer rule does not judge a
path socket, so this scenario proves no lock against a stand-in; that proof moves to [B757](README.md#b757).

## Plan

- A new feature in `acceptance/suite`, tagged `@restart`, on the ubuntu leg:
  - installed machine-wide (the `.deb`, as `Leg.run` does as root for `--os ubuntu`), `porelo-task` is loaded under
    `abi <abi/5.0>` from the package's `postinst`, and `apparmor_parser -Q` compiles it;
  - `doctor`'s `apparmor profile` line says `installed` for the machine-wide install, and for an account install says
    the lock is not held there and why;
  - a task starts, and its agent reaches the vault proxy through `/run/porelo-vault/vault.sock`.
- The steps go into the acceptance kit, with `--agent stub` as every scenario has.
- Red first: `doctor`'s line of an account install, run before [B754](README.md#b754), says `installed`.

## Acceptance

- On the ubuntu leg the scenario passes, installed machine-wide with the profiles loaded and installed in the account.
  Seen to fail: run before [B754](README.md#b754), the account install's `doctor` says `installed`.
- The scenario's run is announced before it starts, since `@restart` reboots the machine.
