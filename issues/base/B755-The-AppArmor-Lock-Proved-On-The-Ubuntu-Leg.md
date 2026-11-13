# B755 — The AppArmor Lock Proved On The Ubuntu Leg

```json
{
  "$schema": "issue-1.json",
  "id": "B755",
  "title": "The AppArmor Lock Proved On The Ubuntu Leg",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 54,
  "blockedBy": ["B752", "B753", "B754"],
  "covers": "An acceptance scenario on the ubuntu leg, installed machine-wide, shows a stand-in bound in the task's namespace refused and a task served by Porelo reaching its vault proxy, ssh agent and mailbox.",
  "openQuestions": 0
}
```

**Why this size.** M: a new scenario and its steps in the acceptance kit, a machine-wide install on the leg with the
profiles loaded, and runs that reboot the machine (`@restart`). Estimate: 63 minutes (Core's median for M), p80 229.

**What must be true.** The lock is shown to hold on a real Ubuntu machine as a person installs Porelo, not only in unit
tests.

Split from [B175](README.md#b175)'s step 9, whose step 2 asked for this scenario. walk4's measurements of v2 and v3
(abstract sockets refused for `unconfined` and for the stack, a binder in plain `porelo` reached) repeat here as one
scenario.

## Plan

- The ubuntu leg installs the `.deb` machine-wide (as `Leg.run` does as root for `--os ubuntu`), so the profiles load
  from the package's `postinst` and `porelod` runs from `/usr/bin`.
- A new feature in `acceptance/suite`, tagged `@restart`:
  - a script of the operator's outside `porelo` joins the task's namespace and binds the vault proxy's name before
    `porelod` does, and the agent's request is refused (`operation="connect"` denied in the kernel's log);
  - a task served by Porelo reaches the vault proxy, the ssh agent and the mailbox;
  - `exec porelo` inside the task is refused;
  - `doctor` says `installed`.
- The steps go into the acceptance kit, with `--agent stub` as every scenario has.
- Red first: the scenario run against the stack before [B752](README.md#b752), where the stand-in's path socket is
  reached.

## Acceptance

- On the ubuntu leg, installed machine-wide with the profiles loaded, the scenario passes. Seen to fail: run before
  the abstract sockets, the stand-in is reached.
- The scenario's run is announced before it starts, since `@restart` reboots the machine.
