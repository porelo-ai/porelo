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
for the first play, which Porelo does not read, and, for the second, the sections Porelo reads:

```yaml
project:
    name: porelo
    url: "git@github.com:porelo-ai/porelo-project.git"
    signed_by: "SHA256:WQGwmhyDCFLwcZ6bK2mzCOXfex1G7vUPdPOVkjtrsfY"
keys:
    - { name: github-deploy, path: keys/github-deploy }
access:
    bundle: bundles/access.bundle
```

- **`project`**: the project to bring up, where its repository is, and the key or fingerprint its project file must
  be signed with. There is no unverified form: a play never follows what it did not check.
- **`keys`**: the keys to put into the vault, each by the vault entry's name and its path on the drive, relative to
  the machine file. A path outside the machine file's directory is refused. Porelo never reads a key here.
- **`access`**, for a project whose project file names an access repository: the bundle of that repository, beside
  the project's on the drive, under the same rule as a key's path. Its URL is never fetched, so a project naming an
  access repository needs it, and one naming none refuses it.
- **Neither tasks nor agents.** A machine file naming `tasks` or `agents` is refused: the machine installs every
  agent, and which tasks run is decided by the person when they start them.

## The second play's inventory

`porelo project inventory --machine-file FILE` prints it on standard output; the play writes it to a file. The
project file is checked against the machine file's key exactly as `porelo project follow` checks it, the access
bundle against the keys the project holds, as a follow checks a fetched access file, and nothing is recorded,
followed or pinned.

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
                  files:
                      registration_token_file: ~/.config/porelo/matrix/host-registration-token
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
  **`files`**, where there is any, names each file the transport expects, by its setting (every setting named
  `*_file`) and the path as the settings give it, `~/` meaning the account's home. The settings are the verified
  access file's for a project that names one, and the project file's otherwise, so a setting kept only in the private
  access file, such as Matrix's `registration_token_file`, is still found. Nothing else of a transport's settings
  reaches the inventory: a path is never a secret, another setting may be.
- **Refusals**, with nothing printed on standard output: 66 when the machine file or its access bundle is not there;
  65 when Porelo's part of it is wrong (a missing `project` field, a key or bundle outside the file's directory, a
  `tasks` or `agents` section), when the project file fails its check against the key, when the access file fails
  its check or its bundle is missing or has no use, or when the settings name under a transport a `*_file` setting
  that is not one path, absolute or under `~/`, without `..` (the setting is named, its value never).

A host follows a project whose access file is in a private repository from the two bundles a person uploads,
`porelo project follow NAME project.bundle --signed-by KEY --access-repository access.bundle`: the host holds no
credential for that repository, and its URL is never fetched.

## A project verified without following it

Root's `sync` on a host and an interface's *Join* run `porelo project verify ... --json` rather than a checker of
their own, so the rules that decide which keys and commits count are Porelo's alone. It is run on copies: root runs
it as a throwaway unprivileged user on its copies of a person's bundles, with no home, vault or network, against
the key pinned at the project's registration.

```
porelo project verify --repository project.bundle --signed-by "ssh-ed25519 AAAA..." \
    --since <commit> --access-repository access.bundle --access-since <commit> --json
```

The answer, alone on standard output, for the newest commits that hold:

```json
{"project": "acme", "commit": "<project commit>", "access_commit": "<access commit>",
 "signer": "ssh-ed25519 AAAA...", "signers": ["ssh-ed25519 AAAA..."],
 "host": {...}, "developers": {...}, "transports": {...}}
```

- **`host`** is the access file's `host:` with its keys as written (`address`, `endpoint`, `wireguard_public_key`,
  `network`, `ssh_host_key`, `ca_sha256`), or `null`; **`developers`** each developer by account, with `laptops`
  and `forge_login`; **`transports`** the access file's settings, or the project file's for a project naming none,
  when `access_commit` is "".
- **`signer`** is the key that signed the project commit. **`signers`** are the keys in force after it, a handover
  walked: a handover commit is signed by the key before it and hands on to its successors, so `signers` is what a
  caller pins and names with `--signed-by` next time, with `commit` and `access_commit` as `--since` and
  `--access-since`.
- **The name** is the one at `--since`, so a later commit renaming the project is refused as a follow refuses it.
  A first check has no `--since`; a caller that knows the project names it with `--project`, or another project's
  bundles signed with the same key would pass under their own name.
- **Refusals**, with nothing on standard output: 65 when a check fails, naming the outcome a follow would give
  (`unknown_key`, `not_signed`, `rewritten`, ...), a key, name or commit is not one, a path is a URL, an access
  option has no use, or a setting has no form in JSON; 66 when a path is not there. A repository without a
  `project.yml` at its tip is named so, before any signature is looked at.

## The answers a second run reads

A playbook changes only what differs, so a second `up` on a host that is up changes nothing. It asks:

| Command | Answer |
|---|---|
| `porelo project following --json` | `{"follows": [...]}`, each with `name`, `url`, `commit`, `outcome`, `verified`, `signed_by` (the keys pinned for it), `detail`, `refused`, and `access_commit` and `access_outcome` (the project's access file in force and how its last try ended, both "" for a project naming none). The play follows a project again where its `url` or key differs, or where the followed `commit` is none of the uploaded bundle's heads. |
| `porelo agents --json` | `{"vault": "open"\|"shut"\|"none", "agents": [...]}`, each with `name`, `found_by` (the description or executable that named it), `credential` (the vault entry it signs in under: its default provider's, or its own name when it is not brokered) and `signed_in`. `signed_in` is given only while the vault is open, and is false for a sign-in lacking what its provider sends beside the token; both are left out for an agent whose default provider cannot be used (none named, none declared, or a dialect it does not serve), since its start is refused whatever the vault holds. No value is printed. |
| `porelo task stop --all` | Stops every running task of the account and its helpers, and removes nothing. Exit 0 when nothing of the account runs any more, nothing having run included; 70 when something survived, each named on standard error as `porelo: still running: …`. `down` runs it before `vault clear` and stops on 70. Standard output is for a person. |
| `porelo vault clear --yes --force` | Clears the account's vault, locked or open, and what the keyring caches of it, without asking and without a terminal. Exit 0 when the vault is gone; 1 when anything was not removed, a running task holding a token from it among them, each named. `--json` gives the same as values. |
| `porelo vault put NAME --json` | `{"name", "stored"}` alone on standard output, every other line on standard error; `stored` is false when the entry already held exactly this value, kind and settings, and then nothing is written. The same for `--from-file` and standard input. No part of the value is printed. The play reports a put as changed only where `stored` is true. |
| `porelo vault status` | Exit 0 open, 3 shut, 4 no vault, 70 for a file that is not a vault. `--json` gives `{"exists", "open", "path"}`. "Open" is proved by reading the vault with what the account keeps. |

**A person locked out.** A person dropped from the signed list is locked out at once, as their own account, with
no terminal and standard input closed: `porelo task stop --all`, then, only on its exit 0, `porelo vault clear
--yes --force`. Exit 0 from both means no task of the account runs and its vault is gone; anything else leaves the
account to be purged by hand.

**A host nobody sits at.** The second play stops when `porelo vault status` answers 3 or 4, and the vault is
opened from outside: a person runs `ssh -t HOST porelo vault unlock`, or, for automation only, a program that holds
the passphrase writes it into `ssh HOST porelo vault unlock --passphrase-stdin` (`vault init --passphrase-stdin` for
a new vault), as [Credentials](credentials.md) says. An interface never feeds it from a form of its own.
