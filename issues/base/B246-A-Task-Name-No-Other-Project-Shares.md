# B246 — A Task Name No Other Project Shares

**Status:** Accepted; after porelo-project [PJ39](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj39).

**What must be true.** A task's container name belongs to one project and one task, so no task of another project
ever has it - not while it runs, and not afterwards, when what Porelo keeps by that name is read again.

## Why

`ContainerName.of` joins the two with a hyphen, `porelo-<project>-<task>`, and both names may hold hyphens: project
`a-b` with task `c` and project `a` with task `b-c` are both `porelo-a-b-c`. While one runs, a start of the other is
refused (`TaskInventory.ofAnotherProject`: "the two names meet"). But Porelo keys more than the container by that
name - the vault's `task/<container>/` entries (route tokens, a transport's task secrets), the state directory, the
clearance decisions, the mailbox - and a transport keys a task's account by the `--task` it is given, which is the
container's name. Whether any of it outlives the first task's removal, and so reaches the other project's task, is
not yet read.

## The shape proposed

- **A separator neither name may hold:** `porelo-<project>_<task>`. Project and task names are lower-case letters,
  digits and hyphens, and podman allows `_` in a container's name, so the split is unambiguous; `ContainerName.MAX_LENGTH`
  holds as before. A task that exists keeps its name, since every act goes to the container's recorded id.
- **The other way, refusing a pair that collides**, needs every project's task names known at each start and still
  leaves a removed task's state under a name the other project can take; it is not proposed.
- A transport keys a task's account by `--project` and `--task` together, which it is given today
  (porelo-message-matrix [MX28](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx28)).

## To be checked

- What of a removed task stays under its container's name - vault entries, state, clearance, mailbox - and so would
  reach a later task of another project with the same name today.
- Every place that takes a project or task out of a container's name, against the rule that a name is never parsed
  for them.
