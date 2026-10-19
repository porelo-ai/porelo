# B217 — A Machine File's Task Names Its Agent

**Status:** Idea.

**What must be true.** Each task in a person's machine file may name the agent it runs, and `porelo project
inventory --machine-file` carries that name into the second play's inventory. So a host's play installs the agents
its tasks need and starts each task with its own, all from the one file the person keeps.

## Why

The operator decided QH20 on 2026-10-19: a host's agents come from the machine file's tasks (a), not from a list of
the host package's own (b). Under (b) one task's agent would be named in two places, which may disagree. porelo-host
installs the packages through the setup script's `--with`, from the signed repository. Its stop names
`agent-login:<agent>` where the vault holds no credential for one. Then it runs `porelo task start --agent`, which
exists.

## The shape proposed

- **`MachineFile`:** reads an optional `agent` in each entry of `tasks`, the name `task start --agent` takes, and an
  optional `package`, what installs it. A name that is no agent's or package's form is refused with 65, as for the
  other faults of Porelo's part.
- **`porelo project inventory`:** writes both into each `porelo_tasks` entry beside `container`, as `agent` and
  `agent_package`, and leaves them out when the task names no agent. Without `package`, `agent_package` is
  `porelo-agent-<agent>`, which the three agents published today follow. It is a default, not a rule: a vendor
  names its package freely and registers its agent by a description under `agents.d`, so the person names any other
  package.
- **`doc/hosts.md`:** names the field.
- **`porelo_inventory_version` stays 1:** an added name changes no meaning, and a play that knows version 1 ignores
  what it does not read. The page says that adding a name does not raise the version.

## To be checked

- Whether a task that names no agent takes the project's default, as `task start` does, and how the play says which
  agent that is.
