# B129 — An Agent Found By Its Description

**Status:** Implementing.
**Size:** S - the stub's package and the package check, two build modules, no contract.

**What must be true.** An agent of another vendor is found once its package is installed, under the vendor's own
names: the package, the executable and the place it lives need not start with `sokar`. The package puts one small
description file into a directory `sokar` reads, and the file says where the agent's executable is.

## Why

Today an agent is found by its file name alone (`AgentDirectory`): an executable whose name starts with
`sokar-agent-`, in `~/.local/share/sokar/agents/` or `/usr/libexec/sokar/agents/`. That ties every vendor's program to
a name and a directory that are Sokar's. A link with that name pointing at the vendor's program would work, but then
the name is still the contract, and an agent is what a directory listing happens to show. A description file makes it
something the vendor states on purpose.

## The shape

- Two directories, read in this order: the account's own (`~/.local/share/sokar/agents.d/`) and the system's
  (`/usr/share/sokar/agents.d/`). The system directory belongs to root, as a package installs it. A description in the
  account's directory wins over one of the same name in the system's, as an agent in the account's directory does
  today, so a person can try their own build without root.
- One file per agent, `<name>.yaml`, with the absolute path of the executable and nothing a vendor would have to
  repeat from the agent itself. The agent's definition stays where it is, answered by the agent:
  `executable: /opt/acme/bin/coder`.
- An agent is taken only when the path is absolute and names a regular file that is executable. A description that
  names anything else is said by `sokar agents` and `sokar doctor` with its file and the reason, and is not taken.
- `sokar agents --verbose` says for each agent which description found it and where its executable is. `sokar doctor`
  says what an account's description hides, as it does today for a binary.
- `agents/README.md` gains a section for a vendor: the package's name is free, the executable may live anywhere, and
  the one file under `agents.d/` is the whole registration. `doc/getting-started.md`, `doc/running.md`,
  `doc/build.md` and `doc/commands.md` say where agents are found.

## Acceptance

- An agent installed as `/opt/acme/bin/coder`, with `/usr/share/sokar/agents.d/acme.yaml` naming it and no file of
  Sokar's name anywhere, is listed by `sokar agents` and starts a task. The package check installs such an agent in a
  clean Debian and a clean Fedora and finds it there.
- Seen to fail first:
  - a description with a relative path, a path to a directory or to a file that is not executable is refused and
    said, and no agent is taken from it;
  - a description in the account's directory hides the system's of the same name, and `sokar doctor` says so;
  - removing the description file removes the agent from `sokar agents`, though its executable is still there.

## As built, 2026-10-09

- `AgentDirectory` reads `~/.local/share/sokar/agents.d/` then `/usr/share/sokar/agents.d/` beside the binary
  locations: a `<name>.yaml` with `executable: <absolute path>` adds that executable; the account's wins by name.
  Refused, and said with the file and why: no `executable`, a relative path, not a regular file, not executable.
- **Decided here for the "to be checked":** the `sokar-agent-*` file names are still read beside the descriptions, so
  the agent packages released before keep working; transports and build readers stay as they are for now.
- `sokar agents` says each refused description; `--verbose` says which description found an agent. `sokar doctor`
  says a description hidden by the account's and one not taken.
- **Tested:** `AgentDescriptionTest` - found under the vendor's names, the refusals, the account's hiding the
  system's, removing the description removing the agent, and the old names beside it.
- **Open:** the package check installing such an agent in a clean Debian and Fedora.

## Plan

Built and on main (`AgentDirectory`, `AgentDescriptionTest`, `DoctorCommand`, the docs). Left: the package check
installing an agent that carries no file of Sokar's name. Both questions once under "To be checked" are answered in
"As built": the old `sokar-agent-*` names are still read; transports and build readers stay as they are.

1. **The stub is packaged as a vendor would.** `agents/pom.xml` maps the stub's executable to
   `/usr/lib/sokar-agent-stub/stub` instead of `${agent.install.dir}`, and adds
   `agents/stub/src/main/agents.d/stub.yaml` (`executable: /usr/lib/sokar-agent-stub/stub`) under
   `/usr/share/sokar/agents.d/`, in the `.deb` (jdeb `dataSet`) and the `.rpm` (`mapping`) alike.
   `${agent.install.dir}` stays a property for the agents' own repositories.
2. **The package check proves it.** `dist/package-check` `Install.COMMON` gains two markers in `markers()`:
   `DESCRIBED` - `sokar agents --verbose` names `/usr/share/sokar/agents.d/stub.yaml` - and `NO-SOKAR-NAME` - no
   `sokar-agent-*` file under `/usr/libexec/sokar/agents`. Both run in Ubuntu 26.04, Debian 13, Fedora 43 and 44.
3. **What reads the old place follows.** `acceptance/legs` `Deploy` puts the stub into the account's directory under
   its old name, which the descriptions leave working; it stays. `dpkg -s sokar-agent-stub` and its removal are by
   package name and do not change. `agents/README.md` names the stub as the example of a description.

Red first: `InstallTest` expects the two new markers in the Debian and Fedora scripts, and `LayoutTest` (or a new
case beside it) expects `stub.yaml` in both packages and no file under `/usr/libexec/sokar/agents`.
