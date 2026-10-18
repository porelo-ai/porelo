# B208 — What A Host Playbook Reads From Porelo

**Status:** Idea.

**What must be true.** The playbook that puts a project on a server (porelo-host, its role for Porelo's own commands,
asked for on 2026-10-18 from
[PJ40](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj40)) calls only Porelo's commands and
reads what it needs from Porelo as a contract, never by parsing text meant for a person:

1. **An inventory with its variables, written by Porelo** from `project.yml`, the repositories' files and the person's
   machine file. Its file, the variables' names and a version the role checks are the contract. The role needs:
   - the account;
   - the project's name and URL, and the key its project file is signed with;
   - the tasks to start, each with its name, `-p` and `-r`;
   - the keys to put into the vault, each as a name and a path on the person's drive, never a value.
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
