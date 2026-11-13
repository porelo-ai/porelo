# B754 — Doctor Says Where The AppArmor Lock Holds

```json
{
  "$schema": "issue-1.json",
  "id": "B754",
  "title": "Doctor Says Where The AppArmor Lock Holds",
  "state": "Review",
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

## Built

- **`AppArmorProfile` (wire):** reads the process's label from `/proc/self/attr/apparmor/current`, not the calling
  thread's, through an injectable path. A stack such as `porelo//&unconfined` counts as outside `porelo`, and the
  answer names every part of it. A stacked process is never asked to enter `porelo` again.
- **`doctor`:** its `apparmor profile` line names plain `porelo`, the stacked label, no profile, or a label that
  could not be read. The line keeps its first word and the `installed` prefix. On an account install it says the lock
  is not held there, and why.
- **`Podman.confining`:** wraps a task in `porelo-task` only where this process wears plain `porelo`.
- **`AppArmorEntry`:** reads `apparmor_restrict_unprivileged_unconfined` under an injectable proc root. Where it is 1,
  unreadable or invalid, Porelo does not enter its profile itself. Where it is 0 or absent, the entry stays for the
  label it gives.
- **`doc/security.md`:** its AppArmor bullet says that an account install has the 0700 directory and not the lock.

Red on the base with only the tests changed: `AppArmorProfileTest` 7 failures in 19 and `PodmanTest` 1 failure in 76;
apps/app does not compile, because its tests use the new label state. Green, run locally: 19, 76 and 32 tests, 0
failures.

Left for [B752](README.md#b752)'s answers: the paragraph before that bullet in `doc/security.md` still describes path
sockets.
