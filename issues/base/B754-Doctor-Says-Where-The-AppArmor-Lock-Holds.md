# B754 — Doctor Says Where The AppArmor Lock Holds

```json
{
  "$schema": "issue-1.json",
  "id": "B754",
  "title": "Doctor Says Where The AppArmor Lock Holds",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 53,
  "blockedBy": ["B752"],
  "covers": "`doctor` reads a stacked label as not `porelo`, says the AppArmor lock holds for a machine-wide install only, and Porelo does not put a task of an account install into `porelo-task`.",
  "openQuestions": 0
}
```

**Why this size.** S: `AppArmorProfile` in `wire`, `AppArmorEntry` and `Podman.confining`'s condition, `doctor`'s
line, `doc/security.md`, and their tests. Estimate: 45 minutes (Core's median for S).

**What must be true.** A person reading `porelo doctor` on an AppArmor machine knows whether the lock holds for their
tasks, and an account install never puts its tasks in a profile that would refuse Porelo's own sockets.

Split from [B175](README.md#b175)'s step 9. On walk4 an `exec porelo` from plain `unconfined` landed in the stack
`porelo//&unconfined` (`apparmor_restrict_unprivileged_unconfined`), and `porelo-task` refused it. So an account
install's tasks would be refused by their own Porelo. Only a binary that `porelo` attaches to by path, the packaged
one under `/usr`, wears plain `porelo`.

## Plan

- `AppArmorProfile` (`wire`) reads `/proc/self/attr/apparmor/current`. A stack such as `porelo//&unconfined` counts
  as outside `porelo`, and its answer names the stack.
- `doctor`'s `apparmor profile` line (`DoctorCommand`, `apps/app`) says that the lock holds for a machine-wide
  install only, and for an account install or a developer's build it says the lock is not held there and why.
- `Podman.confining` (`runtime`) wraps a task only when this process wears plain `porelo`. `AppArmorEntry`
  (`apps/app`) no longer enters `porelo` by name where stacking is enforced. Where it is not, it stays only for the
  label it gives.
- `doc/security.md` states the bound: an account install has the `0700` directory, not the lock.
- First red: `AppArmorProfileTest` over a fake `/proc` with `porelo//&unconfined` expects outside; `AppArmorEntryTest`
  expects no self-entry under enforced stacking.

## Acceptance

- `doctor` on an account install on Ubuntu 26.04, with the profiles loaded, says the lock is not held for it and why.
  Its tasks start and reach Porelo's sockets.
- `doctor` on a machine-wide install says `installed` and its tasks run in `porelo-task`.
