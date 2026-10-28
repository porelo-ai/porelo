# A host a playbook brings up

This page is for whoever writes the playbook that puts a project on a server: what it reads from Porelo, and in
which form. A playbook reads Porelo as a contract, never text meant for a person. The names on this page are kept;
where one changes, the version a reader checks changes with it.

A host comes up in two plays. The first prepares the machine and needs nothing Porelo knows: the host package that
runs it writes that play's inventory itself. It ends with Porelo's setup script, taken from the `porelo` package
rather than downloaded, once the repository is added by the key's pinned fingerprint, as
[Running Porelo](running.md#preparing-a-machine) says; the script makes the account. The second play runs on the
host, as that account, once `porelo setup` has run there. It reads its inventory from `porelo project inventory`,
and its answers from the commands below.

**The machine installs every agent and starts no task.** The first play installs every agent Porelo's repository
offers: those the setup script's `--list --json` names with `"kind": "agent"`, passed to it as `--with`. Nobody keeps
a list; a new agent reaches a host by being published there with `Provides: porelo-agent`. Tasks are the person's,
started by hand on the host or from the interface, never by a play. Only `down` stops them, every one, before the
vault is cleared.

## The machine file

The person's machine file lives on their mounted drive. It is theirs: it names the provider, the offer and the zone
for the first play, which Porelo does not read, and, for the second, two sections Porelo reads:

```yaml
project:
    name: porelo
    url: "git@github.com:porelo-ai/porelo-project.git"
    signed_by: "SHA256:WQGwmhyDCFLwcZ6bK2mzCOXfex1G7vUPdPOVkjtrsfY"
keys:
    - { name: github-deploy, path: keys/github-deploy }
```

- **`project`**: the project to bring up, where its repository is, and the key or fingerprint its project file must
  be signed with. There is no unverified form: a play never follows what it did not check.
- **`keys`**: the keys to put into the vault, each by the vault entry's name and its path on the drive, relative to
  the machine file. A path outside the machine file's directory is refused. Porelo never reads a key here.
- **Neither tasks nor agents.** A machine file naming `tasks` or `agents` is refused: the machine installs every
  agent, and which tasks run is decided by the person when they start them.

## The second play's inventory

`porelo project inventory --machine-file FILE` prints it on standard output; the play writes it to a file. The
project file is checked against the machine file's key exactly as `porelo project follow` checks it, and nothing is
recorded, followed or pinned.

```yaml
all:
    vars:
        porelo_inventory_version: 2
    hosts:
        localhost:
            ansible_connection: local
            porelo_account: core
            porelo_project:
                name: porelo
                url: git@github.com:porelo-ai/porelo-project.git
                signed_by: SHA256:WQGwmhyDCFLwcZ6bK2mzCOXfex1G7vUPdPOVkjtrsfY
            porelo_transports:
              -   scheme: matrix
                  package: porelo-message-transport-matrix
            porelo_vault_keys:
              -   name: github-deploy
                  path: /mnt/drive/keys/github-deploy
```

- **`porelo_inventory_version`** is 2: version 1 had `porelo_tasks`, which is gone. It is raised on any change of a
  name or a meaning here, and a play refuses a version it does not know. A name added beside the others changes
  neither, so it keeps the version: a play ignores what it does not read.
- **`porelo_account`** is the account the command ran as.
- **`porelo_transports`** lists the transports the project's mail conversations and peers use, once each, in
  file order, with their `scheme` and `package`, `porelo-message-transport-<scheme>`. A project without mail gets an
  empty list. The machine file names no transport package; any homeserver package is the playbook's decision.
- **Refusals**, with nothing printed on standard output: 66 when the machine file is not there; 65 when Porelo's part
  of it is wrong (a missing `project` field, a key outside the file's directory, a `tasks` or `agents` section), or
  when the project file fails its check against the key.

## The answers a second run reads

A playbook changes only what differs, so a second `up` on a host that is up changes nothing. It asks:

| Command | Answer |
|---|---|
| `porelo project following --json` | `{"follows": [...]}`, each with `name`, `url`, `commit`, `outcome`, `verified`, `signed_by` (the keys pinned for it), `detail` and `refused`. The play follows a project only where its `url` or key differs. |
| `porelo agents --json` | `{"vault": "open"\|"shut"\|"none", "agents": [...]}`, each with `name`, `found_by` (the description or executable that named it), `credential` (the vault entry it signs in under: its default provider's, or its own name when it is not brokered) and `signed_in`. `signed_in` is given only while the vault is open, and is false for a sign-in lacking what its provider sends beside the token; both are left out for an agent whose default provider cannot be used (none named, none declared, or a dialect it does not serve), since its start is refused whatever the vault holds. No value is printed. |
| `porelo task stop --all` | Stops every running task of the account and its helpers, and removes nothing. Exit 0 when nothing of the account runs any more, nothing having run included; 70 when something survived, each named on standard error as `porelo: still running: …`. `down` runs it before `vault clear` and stops on 70. Standard output is for a person. |
| `porelo vault put NAME --json` | `{"name", "stored"}` alone on standard output, every other line on standard error; `stored` is false when the entry already held exactly this value, kind and settings, and then nothing is written. The same for `--from-file` and standard input. No part of the value is printed. The play reports a put as changed only where `stored` is true. |
| `porelo vault status` | Exit 0 open, 3 shut, 4 no vault, 70 for a file that is not a vault. `--json` gives `{"exists", "open", "path"}`. "Open" is proved by reading the vault with what the account keeps. |

**A host nobody sits at.** The second play stops when `porelo vault status` answers 3 or 4, and the vault is
opened from outside: a person runs `ssh -t HOST porelo vault unlock`, or, for automation only, a program that holds
the passphrase writes it into `ssh HOST porelo vault unlock --passphrase-stdin` (`vault init --passphrase-stdin` for
a new vault), as [Credentials](credentials.md) says. An interface never feeds it from a form of its own.
