# A host a playbook brings up

This page is for whoever writes the playbook that puts a project on a server: what it reads from Porelo, and in
which form. A playbook reads Porelo as a contract, never text meant for a person. The names on this page are kept;
where one changes, the version a reader checks changes with it.

A host comes up in two plays. The first prepares the machine and needs nothing Porelo knows: the host package that
runs it writes that play's inventory itself. It ends with Porelo's setup script, taken from the `porelo` package
rather than downloaded, once the repository is added by the key's pinned fingerprint, as
[Running Porelo](running.md#preparing-a-machine) says; the script makes the account. The second play runs on the
host, as that account, once `porelo setup` has run there. It takes its answers from the commands below.

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

## Where a host reaches a project's repositories

On a host, whatever `upstream` a row of a project's file says, a row with a `central` is reached at
`http://127.0.0.1:<git_port>/<project>/<last part of central>.git`, the copy the host's follow keeps, with `<git_port>`
from `/etc/porelo-host/forge.conf`. So a project filled before it came to a machine needs no refiling, and a task's
work never reaches a central address past the host's push judge. A task on a row with no `central` is refused, and so
is a start whose `--upstream` names another address. Git reads that forge with a token asked of the host for each
command, so it needs no vault entry.

## A task certificate judged for the host

A machine is a host whose persons start tasks when `/usr/local/sbin/porelo-host-person` is there; it is root's alone and reached by sudo. Porelo runs
it only by that path, through `sudo -n`, never with a shell, and never passes a task name beginning with `-` or `@`:

- **Start:** `porelo-host-person task <task>`, standard input the task's certificate line and one line end, after
  Porelo has checked the certificate itself. Exit 0: standard output is the forge token alone, or empty on a host
  without a forge; one trailing line end is not part of it. Porelo keeps the token in the task's vault entries for
  its gate, never in the container or a file. Any other exit fails the start: 69 is answered as 69, every other
  code as 65. Porelo shows the helper's standard error, never its standard output.
- **End:** removing the task runs `porelo-host-person task --end <task>`, which answers
  `{"task":"<task>","ended":true}`; any other answer is reported as not ended.
- **Show:** `porelo task certificate <fingerprint>` asks `porelo-host-person task --show <fingerprint>` for a
  certificate this machine did not keep, and does not believe an answer naming another key.

The helper has Porelo judge the certificate before it records anything, run as root's throwaway user with no home,
vault or network; it reads only the files its flags name and writes nothing:

```
porelo task check-certificate --task <task> --login <login> --keys <file> [--krl <file>] [--json] < certificate
```

- **`--keys`** is the person's keys in force, one OpenSSH public key line each, as the host applied them from its
  verified organization; an empty file refuses every certificate. **`--krl`** is the revocation list in force.
- **Standard input** is one line of at most 16 KiB, ending in at most one line end; anything longer, a second line
  or no line is refused before anything parses it.
- **Accepted** only as `porelo task start` issues it: one `ssh-ed25519-cert-v01@openssh.com` user certificate,
  signed by exactly one of `--keys`' lines, an Ed25519 key, neither that key nor the task's key revoked by the
  list; exactly one principal, `task:<project>:<task>`, of that task and a project name or `default`; the key id
  `porelo task <task> by <login>`; valid now or within five minutes from now, and valid before at most seven days
  and five minutes from now, both by the host's clock, as the start judges it; no critical option, and exactly
  the extensions `ssh-keygen` grants by default, each empty: `permit-agent-forwarding`, `permit-port-forwarding`,
  `permit-pty`, `permit-user-rc` and `permit-X11-forwarding`. <!-- not-a-citation: an OpenSSH extension -->
- **The answer** with `--json`, exit 0, alone on standard output; `fingerprint` is the task key's, `signed_by` the
  person's key that signed it, the times UTC:

```json
{"task": "fix", "project": "acme", "principal": "task:acme:fix", "key_id": "porelo task fix by alice",
 "fingerprint": "SHA256:...", "signed_by": "SHA256:...",
 "valid_after": "2026-10-31T14:00:00Z", "valid_before": "2026-11-07T14:01:00Z"}
```

- **Refusals**, with nothing on standard output and one line on standard error naming the rule, controls replaced:
  65 for a certificate not accepted, 64 for a task or login that is not one, 66 for a named file missing or
  unreadable, the revocation list included, and 70 for Porelo's own fault. The host records the certificate only
  after exit 0, and checks itself that `project` is one the person belongs to.

## A push judged when it arrives

The pre-receive hook of the host's forge has Porelo judge every commit a push brings, rather than a checker of its
own, so the rules that decide which signatures count are Porelo's alone. It runs in the hook's environment, without a
home, a vault or the network, against the files the host applied, and writes only the scratch files ssh-keygen reads,
under `TMPDIR`, removed before it answers:

```
porelo commit verify --repository <git-dir> --signers signers --certifiers certifiers \
    --krl revoked.krl --certificates certificates --json < pre-receive input
```

- **`--signers`** are the project's keys in force, as the organization answers them (no `valid-before` line signs a
  new commit); **`--certifiers`** its `certifiers` lines, of which only those in force at the arrival count (no
  `valid-before`, any `valid-after` passed, the `git` namespace admitted), each line read as ssh-keygen reads it - a
  principal list in quotes may hold spaces, a blank is a space or a tab only, and a `valid-after` day past its month's
  end or second 60 or 61 rolls over;
  **`--krl`** the revocation list in force; **`--certificates`** the host's recorded task certificates, one
  `porelo-host-person task --show` answer per line.
- **Each new commit, merges included, must carry an SSH signature that holds now**, over what git verifies - the commit
  without any header whose name starts with `gpgsig` - however the commit is dated: a person's by a key in force,
  verified as git verifies it by each principal `ssh-keygen -Y find-principals` names for the key's lines, a
  task's through its certificate from a certifier, valid now, for `task:<project>:<task>`, or a task's plain key whose
  recorded certificate would itself be accepted; nothing the KRL revokes. Anything else is refused, and a deleted ref judges
  nothing.
- **The pushed objects are read through the quarantine git gives the hook**; no other git configuration, hook or
  proxy of the account takes effect.
- **Exit 0 lets the push in; 65 refuses it**, with one line per refused commit on standard error, `<commit>
  <reason>`, which the hook may show the pusher. Every commit is judged, so all refusals are named at once. The JSON
  answer on standard output is every verdict with its signer, for the host to record; its fields are in
  [Commands](commands.md#commit).

## The answers a second run reads

A playbook changes only what differs, so a second `up` on a host that is up changes nothing. It asks:

| Command | Answer |
|---|---|
| `porelo agents --json` | `{"vault": "open"\|"shut"\|"none", "agents": [...]}`, each with `name`, `found_by` (the description or executable that named it), `credential` (the vault entry it signs in under: its default provider's, or its own name when it is not brokered), `flow` (how its sign-in works, as its definition's `login.flow` says: `paste-code`, `device-code` or `loopback`, and "" when it does not say) and `signed_in`. `signed_in` is given only while the vault is open, and is false for a sign-in lacking what its provider sends beside the token; `credential` and `signed_in` are both left out for an agent whose default provider cannot be used (none named, none declared, or a dialect it does not serve), since its start is refused whatever the vault holds; `name`, `found_by` and `flow` are always given. No value is printed. |
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
