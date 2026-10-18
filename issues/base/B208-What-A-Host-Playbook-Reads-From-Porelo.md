# B208 — What A Host Playbook Reads From Porelo

**Status:** Backlog.
**Size:** M - one command that writes the inventory from a verified project file and the machine file, with
its version; three answers a program reads (`project following`, `task status`, `vault status`); tests and docs. The
role itself is porelo-host's.

**What must be true.** The playbook that puts a project on a server (porelo-host, its role for Porelo's own commands,
asked for on 2026-10-18 from
[PJ40](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj40)) calls only Porelo's commands and
reads what it needs from Porelo as a contract, never by parsing text meant for a person:

1. **The second play's inventory with its variables, written by Porelo on the host** once `porelo setup` ran there,
   from `project.yml`, the repositories' files and the person's machine file on the mounted drive. Its file, the
   variables' names and a version the role checks are the contract. The role needs:
   - the account;
   - the project's name and URL, and the key its project file is signed with;
   - the tasks to start, each with its name, `-p` and `-r`;
   - the keys to put into the vault, each as a name and a path on the person's drive, never a value.

   The first play's part (the host's address, the account and the packages) needs nothing Porelo knows and is
   written by porelo-frontend's host package
   ([F131](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f131)), decided 2026-10-18, since no
   Porelo runs on the person's computer before the host exists.
2. **Answers a program can read**, so the role changes only what differs on a second run:
   - "this project is already followed, with this key";
   - "this task is already running" (today `task start` exits 65 with "is already running" only without a terminal or
     with `--no-input`);
   - "the vault is open".

**A host nobody sits at, decided 2026-10-18:** for now the playbook's `up` stops after `porelo setup`, a person
runs `ssh -t <host> porelo vault unlock`, and a second play goes on. A vault opened without a person is a later issue
of its own, not part of this one. The mechanism for it exists already: `vault init` and `vault unlock` take
`--passphrase-command`; only where that command reads from on such a host is undecided.

To be linked to porelo-host's issue for that role once it is published.

**Decided 2026-10-18: the tasks and the keys come from the person's machine file**, not from `project.yml`: which
tasks run on one person's host and which keys that person holds are the person's own. The file's two sections are
defined in Porelo's `doc/hosts.md`; porelo-frontend's package reads the file only for the first play.

## Plan

1. **`porelo project inventory --machine-file FILE`** prints the second play's inventory as YAML on standard output.
   The role writes it to a file, so Porelo writes nothing on the drive. It reads:
   - from the machine file, the project's URL and its key, the tasks and the keys;
   - the project file, fetched from that URL and checked against the key exactly as `project follow` checks it,
     but recording nothing. An unverified project file is refused with 65, so the play never follows what it did
     not check.

   The YAML it writes:

   ```yaml
   porelo_inventory_version: 1
   all:
       hosts:
           localhost:
               ansible_connection: local
               porelo_account: core
               porelo_project:
                   name: porelo
                   url: "git@github.com:porelo-ai/porelo-project.git"
                   signed_by: "<fingerprint>"
               porelo_tasks:
                   - { name: core-1, project: porelo, repository: core }
               porelo_vault_keys:
                   - { name: github-deploy, path: "/mnt/drive/keys/github-deploy" }
   ```

   - A task naming a repository the project file does not have is refused with 65, naming both.
   - A key's path must be under the machine file's directory and must not be read. Only its name and path go out,
     never a value.
   - `porelo_inventory_version` is raised on any change of a name or a meaning, and the role refuses a version it
     does not know.
2. **`porelo project following --json`**: each follow with `name`, `url`, `signed_by` (the key's fingerprint, or
   `null` when unverified) and `verified`. The role compares, and follows only when one of them differs.
3. **`porelo task status NAME --json`**: `state` (`running`, `stopped` or `absent`), `project` and `repository`. The
   role starts only what is not `running`; the 65 of `task start` stays the guard for a race.
4. **`porelo vault status`**: exit 0 with "open", 3 with "shut" and 4 with "none". `--json` gives `{ "exists",
   "open" }`. "Open" is a cached passphrase in the keyring that opens the vault, proved as `vault unlock` proves it.
   The role's second play stops with the line for the person (`ssh -t <host> porelo vault unlock`) at 3.
5. **Tests:**
   - unit tests of the inventory over a project file and a machine file in a temporary directory: a good one, an
     unverified project file, an unknown repository, a key outside the directory;
   - one test per `--json` output that pins its names, so a rename fails a build rather than a play;
   - one scenario in a feature: `project inventory` on the VM over a fixture project, then `ansible-inventory -i -
     --list` reading it.
6. **Docs:** a page `doc/hosts.md` (the contract: the file, the names, the version, and what each answer means),
   the three outputs in `doc/commands.md`, and the changelog.

## Acceptance

- `porelo project inventory --machine-file m.yml` on a host after `porelo setup` prints an inventory that
  `ansible-inventory` reads, carrying the account, the project with its key, the tasks and the keys by path.
  Seen red first: the command is unknown.
- A project file that fails its signature check gives 65 and no inventory.
- `project following --json`, `task status --json` and `vault status` give what a second run of the role needs to
  change nothing: a second `up` changes nothing on a host that is up.
- No output of these commands holds a secret: the keys only by name and path.

## To be checked

- **`vault status`'s exit codes.** 3 and 4 are chosen to stay clear of 64, 65 and 70, which mean a refusal; nothing
  in Porelo uses them yet.
- **Proving "open" costs one key derivation**, about a second. If a role asks often, `--json` could say "cached"
  without proving; this plan proves, since "cached but wrong" would let the play go on to a vault that refuses.
