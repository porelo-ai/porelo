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

## The machine file

The person's machine file lives on their mounted drive. It is theirs: it names the provider, the offer and the zone
for the first play, which Porelo does not read, and, for the second, three sections Porelo reads:

```yaml
project:
    name: porelo
    url: "git@github.com:porelo-ai/porelo-project.git"
    signed_by: "SHA256:WQGwmhyDCFLwcZ6bK2mzCOXfex1G7vUPdPOVkjtrsfY"
tasks:
    - { name: core-1, repository: core }
keys:
    - { name: github-deploy, path: keys/github-deploy }
```

- **`project`**: the project to bring up, where its repository is, and the key or fingerprint its project file must
  be signed with. There is no unverified form: a play never follows what it did not check.
- **`tasks`**: the tasks to start, each by the name `porelo task start` takes and the project's repository it works
  on. Which tasks run on a person's host is the person's, so they are here and not in `project.yml`.
- **`keys`**: the keys to put into the vault, each by the vault entry's name and its path on the drive, relative to
  the machine file. A path outside the machine file's directory is refused. Porelo never reads a key here.

## The second play's inventory

`porelo project inventory --machine-file FILE` prints it on standard output; the play writes it to a file. The
project file is checked against the machine file's key exactly as `porelo project follow` checks it, and nothing is
recorded, followed or pinned.

```yaml
all:
    vars:
        porelo_inventory_version: 1
    hosts:
        localhost:
            ansible_connection: local
            porelo_account: core
            porelo_project:
                name: porelo
                url: git@github.com:porelo-ai/porelo-project.git
                signed_by: SHA256:WQGwmhyDCFLwcZ6bK2mzCOXfex1G7vUPdPOVkjtrsfY
            porelo_tasks:
              -   name: core-1
                  project: porelo
                  repository: core
                  container: porelo-porelo-core-1
            porelo_vault_keys:
              -   name: github-deploy
                  path: /mnt/drive/keys/github-deploy
```

- **`porelo_inventory_version`** is 1. It is raised on any change of a name or a meaning here, and a play refuses a
  version it does not know.
- **`porelo_account`** is the account the command ran as.
- **`porelo_tasks`** carries each task's `container`, the name `porelo task status` takes.
- **Refusals**, with nothing printed on standard output: 66 when the machine file is not there; 65 when Porelo's part
  of it is wrong, when the project file fails its check against the key, when a task names a repository the project
  file does not have, or a task name a container cannot carry.

## The answers a second run reads

A playbook changes only what differs, so a second `up` on a host that is up changes nothing. It asks:

| Command | Answer |
|---|---|
| `porelo project following --json` | `{"follows": [...]}`, each with `name`, `url`, `commit`, `outcome`, `verified`, `signed_by` (the keys pinned for it), `detail` and `refused`. The play follows a project only where its `url` or key differs. |
| `porelo task status CONTAINER --json` | `{"name", "state", "running", "project", "repository"}`. A task that is not there answers `"state": "absent"` and exit 0. The play starts only what is not `running`; `task start` answering 65 "is already running" stays the guard for a race. |
| `porelo vault status` | Exit 0 open, 3 shut, 4 no vault, 70 for a file that is not a vault. `--json` gives `{"exists", "open", "path"}`. "Open" is proved by reading the vault with what the account keeps. |

**A host nobody sits at.** The second play stops when `porelo vault status` answers 3 or 4, and the vault is
opened from outside: a person runs `ssh -t HOST porelo vault unlock`, or a program that holds the passphrase writes
it into `ssh HOST porelo vault unlock --passphrase-stdin` (`vault init --passphrase-stdin` for a new vault), as
[Credentials](credentials.md) says.
