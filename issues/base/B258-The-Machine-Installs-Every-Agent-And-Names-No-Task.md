# B258 — The Machine Installs Every Agent And Names No Task

**Status:** Accepted.
**Size:** M - the machine file and the inventory lose the tasks; both catalogues, the setup script's and
`InstallablePackages`, keep only what Porelo's own source offers; `doc/hosts.md`, the decision and tests.

**What must be true.** A person's machine file names the project and the keys, and nothing about tasks or agents. A
host's play installs every agent Porelo's repository offers, read from the setup script's catalogue. The person
starts tasks by hand. The catalogue, and every package `--with` installs from it, comes only from Porelo's own
package source, never from another source configured on the machine.

## Why

The operator decided on 2026-10-23 that bringing a host up and down is kept strictly apart from starting tasks. A
person rents a machine for themselves, starts a task per sprint item by hand, and brings the machine down once the work
is on the central forge. So `up` starts no task and stops for no agent's sign-in, and `down` stops every task first.
The operator also decided that the machine file names no agents: `up` installs every agent package there is, so every
agent is ready at a task's start and the person chooses nothing for the machine. This replaces
[B217](README.md#b217)'s tasks, each naming its agent, which is rejected.

Every published agent package declares `Provides: porelo-agent`, so the repository is the catalogue, and the setup
script's `--list --json` and the daemon's `Installable` already read it. A new agent reaches a host by being published
there. But both read the providers of every source the machine has configured, so a third source offering a package
that provides `porelo-agent` would be listed and installed beside Porelo's own.

## Decided

- The machine file holds `project` and `keys`. A file naming `tasks` or `agents` is refused with 65, saying that
  the machine installs every agent and that tasks are the person's. Porelo ignores what it does not read, so a silent
  pass would leave the person waiting for tasks that never start.
- `porelo_tasks` leaves the inventory, and nothing comes in its place. `porelo_inventory_version` goes to 2.
- No list of agents is kept in porelo's inventory or in porelo-host's role. porelo-host's
  [HO19](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho19) reads `porelo-setup.sh --list --json` and
  installs each `kind: agent` with `installed: false` through `--with`.
- `down` stops the account's tasks with `porelo task stop --all`. It exits 0 when nothing of the account runs any more,
  and 70 when something survived, naming it on standard error.

## Plan

1. **`MachineFile`** reads `project` and `keys` only. A `tasks` or `agents` key is refused with an
   `IllegalArgumentException`, which `project inventory` turns into 65. `Task` and its two name checks go.
2. **`ProjectInventoryCommand`** writes no `porelo_tasks`, and drops the repository and container checks that only
   tasks needed. `VERSION` is 2.
3. **Porelo's own source**, the one the setup script writes:
   - on apt, `/etc/apt/sources.list.d/porelo.list` with `https://fuinorg.jfrog.io/artifactory/porelo-dist-deb`;
   - on dnf, the repository `porelo` in `/etc/yum.repos.d/porelo.repo` with `.../porelo-dist-rpm/<distribution>`.

   A package belongs to it only when the candidate the package manager would install comes from there:
   - on apt, `apt-cache policy <name>`'s candidate has a line under that URL;
   - on dnf, the candidate's `repoid` is `porelo` and that repository's base URL is under that address.
4. **The setup script's catalogue** lists only such packages. **`--with NAME`** is refused with 5, naming the source,
   when NAME's candidate is not from Porelo's source, before anything is installed. Under `--show` the check is
   printed with the other commands.
5. **`InstallablePackages`** keeps only such packages, by the same rule and the same queries, word for word as the
   script's.
6. **`doc/hosts.md`:**
   - the machine file's example and text lose `tasks`;
   - the inventory's example and text lose `porelo_tasks`, and its version is 2;
   - the table loses `task status`'s row and the sign-in stop, and gains `task stop --all`'s row;
   - the first play's paragraph names the catalogue as where the agents come from.

   **`doc/decisions.md`**'s host entry says the operator's two decisions. **`doc/transports.md`** names `clear`
   among the verbs that get `--machine`, as `TransportLifecycle.run` adds it.
7. **Tests, each seen red first:**
   - `tasks`, and `agents`, each refused with 65 and nothing on standard output;
   - the inventory has no `porelo_tasks` and says version 2;
   - a provider from another source is not listed and `--with` refuses it with 5, on both families, with stand-ins
     for the package manager;
   - `InstallablePackages` leaves the same provider out;
   - a provider from Porelo's source is still listed and installed.
8. A changelog line. [B217](README.md#b217)'s unreleased line goes.

## Acceptance

- `project inventory` of a machine file naming `tasks` ends 65 and prints nothing on standard output. One with
  `project` and `keys` prints version 2 with no `porelo_tasks`.
- On a machine with a second source that offers a package providing `porelo-agent`, neither `--list --json` nor the
  daemon's `Installable` names it, and `porelo-setup.sh --with` that package ends 5.
- porelo-host's [HO19](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho19) installs every agent from the catalogue and starts no task.
