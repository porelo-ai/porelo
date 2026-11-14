# B755 — The AppArmor Profile Proved On The Ubuntu Leg

```json
{
  "$schema": "issue-1.json",
  "id": "B755",
  "title": "The AppArmor Profile Proved On The Ubuntu Leg",
  "state": "Review",
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

- A new feature in `acceptance/suite`, tagged `@restart` and `@machine-package`, run on a VM deployed machine-wide
  (`deploy` without `--account`). The CI legs leave `@machine-package` out: a leg installs this tree's build into its
  account and loads only the profiles as root, so no package is installed there.
  - installed machine-wide from the `.deb`, `porelo-task` is loaded under `abi <abi/5.0>` from the package's
    `postinst`, and `apparmor_parser -Q` compiles it;
  - `doctor`'s `apparmor profile` line says `installed` for the machine-wide install, and for an account install says
    the lock is not held there and why;
  - a task starts, and its agent reaches the vault proxy through `/run/porelo-vault/vault.sock`.
- The steps go into the acceptance kit, with `--agent stub` as every scenario has.
- Red first: `doctor`'s line of an account install, run before [B754](README.md#b754), says `installed`.

## Acceptance

- On the Ubuntu VM deployed machine-wide, the scenario passes, installed machine-wide with the profiles loaded and as
  an account's copy. The run replaces the machine's package, needs a native build first and may reboot, so it is
  scheduled for the day.
  Seen to fail: run before [B754](README.md#b754), the account install's `doctor` says `installed`.
- The scenario's run is announced before it starts, since `@restart` reboots the machine.

## Built

- `machine/apparmor-profile.feature` (`@slow @restart @machine-package`): the profile came from the package, under
  `abi <abi/5.0>`, held in enforce mode and compiled, and held again after a reboot; `doctor` says `installed` for
  the machine-wide install and `DEGRADED`, with why, for an account's copy; a `--agent stub` task's processes are in
  `porelo-task`, its agent is given `/run/porelo-vault/vault.sock`, and its request reaches the vault proxy.
- Two kit steps in `PackageSteps`: the machine runs AppArmor (skips otherwise), and Porelo is installed machine-wide
  by its package; the second's decision is unit-tested, seen red then green. Every step is defined (a Cucumber dry
  run). The legs leave `@machine-package` out in both passes, seen red then green in `HostServicesTest`.
- Run on the Ubuntu VM deployed machine-wide from the package of the native build of the tested commit, the
  account's own copy set aside: the five scenarios, with the reboot, passed but the fifth. That one failed first on
  its own race - a start typed into the terminal is not waited for - and then reached a tmux server on a path
  socket from inside `porelo-task (enforce)`: the peer rule judges abstract names only, and the file rules let the
  task reach every file of its container, so the scenario asked for what the profile does not promise. It now waits
  for the start and serves an abstract name, `@porelo-foreign`, from a plain exec of the agent's user: that exec
  reaches it, the task's session in `porelo-task (enforce)` is refused, and the scenario passed on its rerun.
- Not seen red: the fifth scenario against a profile weakened to a bare `unix` rule, which needs the profile
  reloaded as root on the machine.
- The reading (agy, gemini-4-argon-high) found two grave gaps, checked in the code, and both are closed:
  - The label check read a task started with `--detach`, which runs no agent: that start returns before any
    session. Now the scenario attaches in a terminal, where the stub, asked nothing, waits as a running agent, and
    the container's root reads every process's label: the stub must be `porelo-task (enforce)`.
  - Nothing asked for the effect `porelo-task` exists for. A new scenario starts a tmux server by a plain exec of
    the agent's user (outside `porelo-task`, no `porelo` label): the same user reaches it from a plain exec, and the
    task's session, in `porelo-task`, is refused. A profile weakened to a bare `unix` rule fails it.
  - The after-restart check now reads `porelo-task` and `porelo` by their exact names and checks the script's status.
  - Declined: the machine-wide step does not look for an account's hooks or user unit left by an earlier
    `deploy --account` - the run is on a VM deployed machine-wide from scratch; the AppArmor step skipping where
    AppArmor does not run is what the Fedora leg needs; `dpkg-query -S` names the owner, not the conffile's content -
    the run installs a fresh package.
  - Every step is defined: a Cucumber dry run of the feature with `-Pwhole` ran its 5 scenarios, 0 failures.
