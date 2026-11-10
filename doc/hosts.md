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

## A person's login on a host

On a host a person's login is the name of a Linux account, so a card's login is a lower-case letter, then up to 31 lower-case letters, digits or dashes. A host also refuses `root`, `porelo` and any `porelo-...`. GitHub allows more: a login with a capital is the same login in lower case, and one past 32 characters cannot have a card.

## Where a host reaches a project's repositories

On a host, whatever `upstream` a row of a project's file says, a row with a `central` is reached at
`http://127.0.0.1:<git_port>/<project>/<last part of central>.git`, the copy the host's follow keeps, with `<git_port>`
from `/etc/porelo-host/forge.conf`. So a project filled before it came to a machine needs no refiling, and a task's
work never reaches a central address past the host's push judge. A task on a row with no `central` is refused, and so
is a start or a gate command whose `--upstream` names another address. `porelo` and `porelod` read a project alike. Git
reads that forge for a person's commands with a token asked of the host for each command, so those commands need no
vault entry, and the token is lent for that forge's address alone. An online task's gate pushes with the task token
issued for `porelo-<login>`, kept in the task's vault entries. The forge attributes that push to the task account;
an explicit approval uses the person's token. A missing task token refuses an online task's push.

## The host's published project progress

The follow publishes each applied project's progress beside its central measurements in
`/etc/porelo-host/organization/central/<project>.json`, including projects waiting before their
first migration. The timestamped envelope adds `state` and an optional `why`; `host_repositories`
contains logical project-file repository names with their own progress state and optional reason.
Central measurement rows continue to name the forge's copies, so a logical name differing from its
copy's name cannot attach progress to the wrong repository. Reasons are printable and bounded to
200 code points, including grouped reasons for large projects. Unmigrated projects are published
without asking central. The file uses the existing root ownership, plain-file, no-symlink, size
and named-user read ACL rules; the daemon reads at most 1 MiB and refuses the whole answer on an
unknown field, state, duplicate or malformed value. Older files without progress remain readable.
The central directory stays closed until updated answers succeed. A standalone distance refresh
preserves progress for that project and leaves other projects' publications in place; only a full
follow prunes departed projects.

`Projects` and `WatchProjects` report this progress as `hostState` and `hostWhy` on projects and
repositories. Waiting and refused progress can only withhold what the placement file authorizes.
In particular, `waiting-for-token` withholds carrying until a person supplies the forge token to
install each new repository's read key and copy it; the host keeps no token. Unavailable or
malformed publications withhold carrying with `cannot-say` and an explanation naming the refused
field or value. Repository progress distinguishes a taken copy from one still waiting or not
carried, independently of its last central distance.

## Open pull requests over the daemon

`org.fuin.porelo.Tasks1.PullRequests(project)` reads the open pull requests this account's person token can
see in the project's repositories carried by the host. Each row names the repository key, positive review number,
title, exact head commit, author, committer, `Assisted-by` and `Co-Authored-By` trailers, and state. Makers come from
the local mirror, not the forge's account fields. They are claims in the commit, not a signature or approval judgment.
Titles and makers are bounded plain text for display; clients must render them as text.

`WatchPullRequests(project)` with varlink's `more` sends an initial snapshot, then changes, polling five seconds
after each completed round. `pullRequests` is the complete current open list; `ended` contains confirmed `merged`
or `closed` rows once per observed transition. These events end individual rows; the watch stays open. A review
opened and ended entirely between polls can go unseen. Without `more`, the watch answers one snapshot.

Both methods read the vault entry `<name>.person` written by `porelo host forge-token`. The name and port come from
the same bounded, root-owned plain forge-settings file that command reads. A missing or unusable entry answers
`MissingHostForgeToken(credential, storeCommand)`, naming `porelo host forge-token`. A locked or unreadable vault is
`HostPullRequestsRefused` with reason `vault-unreadable`. Neither method mints credentials, fetches git objects,
or is available to a task through MCP: they use the person's forge identity to read work awaiting human judgment.

HTTP stays on the configured port at `127.0.0.1`, with no proxy or redirects, a ten-second connection timeout and
a twenty-second request timeout. Each HTTP response and complete daemon reply is limited to one MiB; each project
to 100 repositories and 500 open rows; each repository to 100 pages of 50. Maker queries allow ten seconds and
64 KiB of output. Shown fields keep at most 1000 code points, and at most 50 help trailers are returned.
A bound, unavailable mirror commit, forge failure, or loss of access refuses the observation and ends a stream
with a named error. Absence alone never means closed. Clients must mark retained data stale and ask again.

## A person's gathered push and pull

`porelo host push <project> [<repository>...]` sends the host's approved local `main` to central; `porelo host pull
<project> [<repository>...]` brings central `main` to local `central/main` for reconciliation in a task. These are the
person's commands, run in their own account, replacing `porelo-host-push` and its `--pull` mode. Pull never changes
local `main`, and a gathered push never forces central `main`. A rejected push stops before later repositories and
brings central's missing commits back for a task to merge and obtain approval.

The window offers the same gathered push through Tasks1 `PushToCentral(project, repositories)`. Repository
selection is optional. Its stream names each repository and its stage, then lists what was pushed and what was
already up to date. If everything is unchanged, `NothingToPush` says so. Vault unlock, missing central credentials,
refused central authentication, untrusted host setup, unavailable local token issuance and central push refusal
are separate named answers the window can act on. No process output or credential is sent to the window.

The daemon asks no passphrase, terminal or sudo password. Unlock the vault through the route it names, or store
`<forge>.central` with `porelo vault put <forge>.central`, then retry. It uses the same checked programs and existing
sudo rule as the command. Issuance and transfer hold one account lock so overlapping releases or a separate
`porelo host forge-token` cannot revoke an active transfer's token. The daemon gives each Git process ten minutes
and stops it when a streaming client disconnects. A one-shot client can finish sending while still reading. A cut-short write can have reached central; inspect it before retrying.
Confirmed earlier pushes remain in the result of a later refusal. This release is a person's judgment and is
never offered to a task through MCP, even when listed locally.

Root publishes the forge's `name` and `git_port` in `/etc/porelo-host/forge.conf`. That checked `name` determines the
vault entries: `<forge>.central` for the person's central token and `<forge>.person` for the freshly issued local
token. Store the central token with `porelo vault put <forge>.central`. A missing entry in a readable vault names
this command and runs
nothing. A non-interactively shut vault also refuses before any process runs. At a terminal, configuration is
validated before a passphrase is asked, and that passphrase is kept for this command only. A shut share-only host
vault names `porelo host unlock <host>`; a device-only vault names its enrolled device. Neither gathered command
reads a token from stdin or expands argument files.

The person's `$XDG_CONFIG_HOME/porelo-host/push/<project>.conf` supplies `local`, `owner`, `central-ca`, and `repository
<name> <https-url>` lines. Its directories belong to the person and are not writable by others; `push` is 0700 and
the file is 0600. Reads check opened descriptors, refuse links, pipes, NUL bytes and more than 64 KiB, and have a
ten-second budget. The CA's entire resolved path is root's alone. Root-owned bundle links, including ancestor links
and relative targets, use the same bounded component walk as the removal check. Without repository arguments, every
configured repository
is selected in file order. Git uses a private scratch directory and only the current destination's credential,
with no redirects, hooks, credential helpers, proxies, automatic maintenance or inherited configuration. Scratch and
active Git processes
are cleaned on interruption. Git output is captured completely up to 16 MiB per stream; an unreadable or larger
reply refuses without displaying a partial diagnostic. Once a process exits, its output pipes have one shared
two-second grace period; descendants retaining a pipe cannot hold the command indefinitely.

The only privileged operation is the existing `porelo-host forge issue-token`, through this exact sudo rule:

```sudoers
%porelo-people ALL=(root) NOPASSWD: /usr/sbin/porelo-host forge issue-token
```

It takes the person from `SUDO_USER` and checks `SUDO_UID`, with no person argument. Sudo starts in `/` with only
`PATH=/usr/sbin:/usr/bin` and `LC_ALL=C`, so caller secrets, locale and working directory are not inherited. Its stdout goes directly into
the vault and the current operation, never a terminal. If saving a newly issued local token fails, the gathered
operation warns and continues using it in memory. There is no privileged push or pull verb. For removal, the host
keeps `porelo-host project check-central`, the read-only preservation check; `down` is separate.

## A task certificate judged for the host

A machine is a host whose persons start tasks when `/etc/porelo-host` or the installed `/usr/sbin/porelo-host` is
there. The program is root's alone and reached by sudo. Porelo runs
it only by that path, through `sudo -n`, never with a shell, and never passes a task name beginning with `-` or `@`:

- **Start:** `porelo-host task start <task>`, standard input the task's certificate line and one line end, after
  Porelo has checked the certificate itself. Exit 0: standard output is the forge token alone, or empty on a host
  without a forge; one trailing line end is not part of it. Porelo keeps the token in the task's vault entries for
  its gate, never in the container or a file. Any other exit fails the start: 69 is answered as 69, every other
  code as 65. Porelo shows the helper's standard error, never its standard output.
- **End:** removing the task runs `porelo-host task end <task>`, which answers
  `{"task":"<task>","ended":true}`; any other answer is reported as not ended.
- **Show:** `porelo task certificate <fingerprint>` asks `porelo-host task show <fingerprint>` for a
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

## Reading a repository's arrivals

Root's push judge appends accepted commit judgements to its private ledger at
`/var/lib/porelo-host/arrivals/<project>/<repository>.jsonl` and publishes a complete snapshot at
`/etc/porelo-host/organization/arrivals/<project>/<repository>.jsonl`. Each UTF-8 JSON line carries the
recorded `verdict` and UTC `arrived` time. An `accepted` row also carries `commit`, `ref`, and the
verifier's `signer` object; `migrated` rows identify commits predating the judge, and an `armed` row
marks installation of the hook. Reading preserves those historical judgements without verifying
signatures again. The snapshot records the pre-receive judgement, not proof that the forge subsequently
updated a ref.

The snapshot is root-owned, created with mode 0600 and granted named-user read ACLs through the host's
existing publication helper. Readers are the applied project's unlocked members whose host person
records are unlocked and not pending, whose cards are present, unrevoked and unexpired in both the
applied organization and person files, and whose local accounts exist. The ACL mask permits read only;
others have no access. Publication directories permit traversal without listing (0711). Follow
revokes former readers before slow reconciliation and rebuilds snapshots from private ledgers, so an
upgrade or a membership change needs no new push. Taking off a repository or removing a project
revokes its publication before deleting private history. A failed publication leaves the answer unavailable.
If revoking old snapshots fails, their directory stays closed and further publication is refused
until root repairs the failure and runs `porelo-host org follow` successfully. Complete ledgers are
streamed when published. Follow scans the private ledger through its locked descriptor with a 128 KiB
bound per line, so ledger growth never prevents another judged push or follow. Arming records pre-hook
commits and the hook marker privately; the same follow publishes them after admission is complete.

Porelod's Tasks1 `Arrivals(project, repository)` reads that file directly, without sudo or a host
script. It accepts only a regular root-owned file under root-owned directories, with no symlinks and
no group or other write permission anywhere in the path, using the same checks as central state.
`state: read` carries `entries` in ledger order. Missing, unreadable, unsafe, oversized or malformed
data yields `state: cannot-say`, a named `why`, and no entries; it never means that nothing arrived.
The reader accepts at most 16 MiB, independently of the host's complete publication. An empty file
is unavailable: an armed repository without commits has an `armed` row. Repository names use the
forge's lowercase on-disk spelling; callers may use the repository's display casing. A task's MCP
endpoint can read arrivals only for its own project.

## The answers a second run reads

A playbook changes only what differs, so a second `up` on a host that is up changes nothing. It asks:

| Command | Answer |
|---|---|
| `porelo agents --json` | `{"vault": "open"\|"shut"\|"none", "agents": [...]}`, each with `name`, `found_by` (the description or executable that named it), `credential` (the vault entry it signs in under: its default provider's, or its own name when it is not brokered), `flow` (how its sign-in works, as its definition's `login.flow` says: `paste-code`, `device-code` or `loopback`, and "" when it does not say) and `signed_in`. `signed_in` is given only while the vault is open, and is false for a sign-in lacking what its provider sends beside the token; `credential` and `signed_in` are both left out for an agent whose default provider cannot be used (none named, none declared, or a dialect it does not serve), since its start is refused whatever the vault holds; `name`, `found_by` and `flow` are always given. No value is printed. |
| `porelo-setup.sh --json` | `{"changed", "parts"}` alone on standard output, every other line on standard error. `changed` is false when nothing was installed or written. A package older than the repository offers stays, and is said on standard error. |
| `porelo task stop --all` | Stops every running task of the account and its helpers, and removes nothing. Exit 0 when nothing of the account runs any more, nothing having run included; 70 when something survived, each named on standard error as `porelo: still running: …`. `down` runs it before `vault clear` and stops on 70. Standard output is for a person. |
| `porelo vault clear --yes --force` | Clears the account's vault, locked or open, and what the keyring caches of it, without asking and without a terminal. Exit 0 when the vault is gone; 1 when anything was not removed, a running task holding a token from it among them, each named. `--json` gives the same as values. |
| `porelo vault put NAME --json` | `{"name", "stored"}` alone on standard output, every other line on standard error; `stored` is false when the entry already held exactly this value, kind and settings, and then nothing is written. The same for `--from-file` and standard input. No part of the value is printed. The play reports a put as changed only where `stored` is true. |
| `porelo vault status` | Exit 0 open, 3 shut, 4 no vault, 70 for a file that is not a vault. `--json` gives `{"exists", "open", "path"}`. "Open" is proved by reading the vault with what the account keeps. |

**A person locked out.** A person dropped from the signed list is locked out at once, as their own account, with
no terminal and standard input closed: `porelo task stop --all`, then, only on its exit 0, `porelo vault clear
--yes --force`. Exit 0 from both means no task of the account runs and its vault is gone; anything else leaves the
account to be purged by hand.

**A host nobody sits at.** The second play stops when `porelo vault status` answers 3 or 4, and the vault is
opened from outside: on their own machine a person runs `porelo host unlock HOST --host-key SHA256:... --login ACCOUNT`,
which derives the share with their key and sends it over ssh, and makes the vault when there is none, as
[Credentials](credentials.md) says. A host vault has no passphrase and never asks for one.
