# [B538](README.md#b538) — A Task's Container Without The Doubled Project Name

**Status:** Backlog. Filed 2026-11-09 from move 3's findings 10/19 and [B532](README.md#b532), as the operator decided
it (the operator's decision of 2026-11-09 on the open points of wave 2, point 6: keep the name now, plan the change).
**Priority:** high (the operator, 2026-11-09).
**Size:** L at least, to plan: every place keyed by the container name moves, with a way for tasks made before.

**What must be true.** A task of repository `porelo-pi` in project `porelo` is the container `porelo-pi`, not
`porelo-porelo-pi`; tasks made before keep working (attach, resume, stop, their mail, vault entries, sockets), and no
two projects can make one container name.

## What it costs (to plan)

- Every task's state is keyed by the container name: `task/<container>/` (TaskPaths), its mailbox (MessagingPaths),
  vault entries named by it, `/run/user/<uid>/porelo/<container>/` sockets, podman labels, the room's addresses file.
  A rename orphans all of it for tasks made before unless they are migrated or both names are read.
- Uniqueness: `porelo-<project>-<task>` cannot clash between projects; dropping the doubled part when the repository's
  name begins with the project's can make two projects' tasks share a name: repository `porelo-pi-x` in project
  `porelo` would become `porelo-pi-x`, the name task `x` of project `pi` has today. The plan must make the new name provably unique, or refuse the clash.
- Plan reading before the build (it touches every task path).
