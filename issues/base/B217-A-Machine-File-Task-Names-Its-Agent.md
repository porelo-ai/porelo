# B217 — A Machine File's Task Names Its Agent

**Status:** Analyzed.
**Size:** S - two optional fields read and checked in `MachineFile`, written by `project inventory`, the page and
tests; no command changes.

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

## Plan

Read on 2026-10-19 in `MachineFile` and `ProjectInventoryCommand`:

1. **`MachineFile.Task`** gains `@Nullable String agent` and `@Nullable String agentPackage`, read from each entry's
   optional `agent` and `package`. An agent's name is checked as `task start --agent` takes it (lower case, digits and
   `-`), a package's as a Debian and RPM package name (`[a-z0-9][a-z0-9+.-]*`). Anything else, or `package` without
   `agent`, is refused with 65, naming the entry, as the other faults of Porelo's part are.
2. **`ProjectInventoryCommand`** writes `agent` and `agent_package` into the task's `porelo_tasks` entry when the task
   names an agent, `agent_package` defaulting to `porelo-agent-<agent>`. A task that names none gets neither, and the
   play starts it without `--agent`, so `task start` takes the project's default as it does today.
3. **`doc/hosts.md`:** the machine file's example and the inventory's example show both, and the page says that an
   added name keeps `porelo_inventory_version` at 1.
4. **Tests, each seen red first:** the fields read; the default package; a task without an agent written without
   either; each refusal with 65 and nothing on standard output.
5. A changelog line.

## Acceptance

- A machine file naming `agent: pi` gives `agent: pi` and `agent_package: porelo-agent-pi` in the inventory; one
  naming `package` too gives that package.
- A bad name, or `package` alone, is refused with 65 and no inventory.
- Machines' `tasks.yml` reads both from the inventory.

## To be checked

- Nothing left: a task that names no agent is started without `--agent`, as item 2 says.
