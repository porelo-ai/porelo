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

## A credential carried to a host

A host's vault starts empty. A person who keeps a provider's key in their own machine's vault copies it to their
vault on a host from the window, without typing it again, in three calls on the two daemons:

1. The host's daemon makes a key for this one copy, `CredentialReceiver`. Its private half stays in that daemon's
   memory until an entry opens with it or five minutes have passed; a few live at once. A shut vault makes none.
2. This machine's daemon seals the entry to that key, `SealCredential`, and answers ciphertext bound to the entry's
   name and kind.
3. The host's daemon opens it with the key, which then ends, and stores the entry under the same name,
   `ReceiveCredential`. An entry of that name already there is kept unless the person asks to replace it.

The window carries the sealed entry from one daemon to the other at once and keeps nothing. A sealed entry opens
nowhere else, under no other name or kind, and not twice. How it is sealed is in [Security](security.md#a-credential-carried-to-a-host).

An agent's sign-in on this machine is carried the same way, so a person signed in here need not sign the agent in
again on the host. The window lists what can be carried, `ImportableCredentials`: each installed agent and provider
whose sign-in the agent's own file holds, with its kind, never a value. It then asks `SealCredential` for an agent and
provider instead of a vault entry's name. This machine's daemon reads the sign-in as `ImportCredential` does and
seals it under the name and kind that import would store it under, so the host keeps it where its tasks look; this
machine's vault is neither opened nor written. What is carried is what an import stores - the sign-in and its
kind, without a refresh token kept beside it - so a sign-in that expires is signed in again on the host. A failure
to read it is said without the agent's own words.

## A host's identity in the person's vault

A rented host is given back its identity at each later rental, so it is the same host to the central forge and to
every machine that pinned its host keys: the organization's read key, each repository's read key and its SSH host
keys. The person's vault keeps them encrypted, one entry per host under the reserved name `host-identity/<host>`,
written through the daemon alone:

- `HostIdentityKeep` keeps the identity after the host's first setup and again before each `down`, as the host has
  carried more repositories. An identity already kept is replaced only by one with the same organization key, the
  same host keys and every kept repository unchanged; anything else is `HostIdentityExists` until the person asks
  to replace it.
- `HostIdentityRead` answers the whole identity, private halves included, for the moment it is piped into the host's
  setup. A host's own daemon neither keeps nor answers it, as its vault is not the person's. `HostIdentityPublic` answers it without any private half, and
  `HostIdentities` names the hosts.

The value is checked whole when it is kept: no unknown field or control character, each repository's title as its
migration registers it, its key id above 0 and its address at the organization's own forge, one to three host keys of
distinct types, and each private key one unencrypted OpenSSH key whose public half is the one given. An Ed25519 key's
public half is derived from its seed and every section of the file must name the same; the others' are derived by
`ssh-keygen` from a copy in a directory of the daemon's own under its runtime directory, removed at once and, after a
crash, when the daemon starts. A read checks the kept value's fields again and derives nothing. A refusal names the
field, never a key, and a field the shape does not know by where it is, never by its name. Only the person's own socket reaches these methods, never a task's.
`porelo vault put` and `porelo vault remove`, and the daemon's methods for an entry or a carried credential, refuse
the name, `Credentials` never lists it, and `porelo vault clear` names each of its keys as left at the central forge.

## Root's person maintenance

The host package provides `/usr/sbin/porelo-host person purge <login>` and `person restore-runtime`.
Both require the real root uid and hold the same exclusive `forge.conf` lock as the follow. They use
root's checked people records, fixed program paths and account database; neither grants a new sudo rule.
The purge target is explicit. Commands that act for their calling person still take that person only
from `SUDO_USER`, with `SUDO_UID` checked against the account database.

Losing admission removes SSH, group, session and forge access and stops the person's tasks. Matrix
accounts remain available for readmission. Only absence from every applied project listing and the
host admins starts the seven-day purge clock; a listed locked member has no purge deadline. The follow
purges on its first successful pass at or after that deadline. An explicit `person purge` enforces the
same locked, absent and due conditions. A pending lock is finished before deletion; an incomplete
organization membership inventory refuses purge. Root, system and unrecorded accounts are refused.

Purge expels the person's Matrix identities and deletes their forge identities and tokens, then stops
tasks before clearing the vault as that person. Only a transport account kept elsewhere may remain
after vault cleanup. It stops the user manager before removing the OS account and home, releases the
person's subordinate IDs, and reserves the login in root's private purged list. A failed step returns
nonzero and retains retry state; cleanup that temporarily lifts account expiry attempts to expire it
again and stop its manager even on failure. An already absent account can finish the remaining cleanup.

For `up`, run `person restore-runtime` as root. It repairs only records with memberships, unlocked and
without a pending lock: the person-owned 0700 return-socket directory, its missing daemon link and the
root-owned 0644 tmpfiles rule. A person's existing entry at the daemon link stays, including a dangling
link. Unsafe directories or boot rules are refused. Each selected person's `user@<uid>.service` is
restarted; locked, pending and membership-free records are untouched. The JSON answer `{"changed":N}`
counts people whose runtime files changed, excluding manager restarts, so a repeated file repair answers
zero even though admitted managers restart again.

## A host's identity carried to the next rental

A host's identity is its organization read key, the read key of each repository it carries and its SSH host keys.
It is taken off a host before `down` and put back on the next rental, so the keys registered at the central forge
and the host key a person's machine pinned stay the same.

- **`porelo-host identity export`**, as root, prints it on standard output as one JSON object, and nothing else:
  `host`, `organization` (`public`, `private`), `repositories` (`project`, `repository`, `address`, `key_id`,
  `title`, `public`, `private`) and `ssh_host_keys` (`type`, `public`, `private`). A repository is in it once its
  read key is registered (its key id is above 0); its title is `porelo host <host> project <project>`. A key the
  migration reused, registered under another title, is left out and named on standard error as `<project>/<repository>
  not carried: its key is registered under another title`: `porelo host give-up` could never remove it by this
  host's title. The SSH host keys are the ed25519, ecdsa and rsa keys of `/etc/ssh` that exist. Export writes
  nothing, not even the follow's lock file.
- **`porelo-host identity import`**, as root, reads that object on standard input, at most 256 KiB. It writes the
  SSH host keys first, `/etc/ssh/ssh_host_<type>_key` at 0600 and its `.pub` at 0644, then
  `/etc/porelo-host/organization/read/` as export reads it: the organization key, each repository's key pair and
  its entry in the project's `repositories.json`, files 0600 in directories 0700. Each file is written whole
  through a staged file beside it and an atomic move.
  - **A fresh host**, with nothing at all under `/etc/porelo-host/organization/read/`, has never carried an
    identity: its host keys are the first-boot pair the image made. Import replaces them and removes a type the identity does not carry,
    so the host's keys are then exactly the identity's.
  - **A host that already carries an identity**, or holds anything under its read keys, gets only what is missing.

  Every check, the modes of the directories it writes into included, is made before the first write. A write that
  fails after the first one puts back what the import replaced or removed and removes what it added. Before it
  replaces a file, it keeps the old one as a second link beside it, `.porelo-host.kept.<name>`; when putting one back
  fails too, that link stays, since it may hold the only copy of an old host key. Each public
  half is derived by `ssh-keygen` from a private file in `/run/porelo-host/identity-scratch`, root's alone and
  cleared at reboot, which the import removes when it ends, an earlier run's included.

  It refuses, with nothing changed:
  - a field it does not know, or one missing;
  - another host's identity, or a title that is not this host's;
  - a key that is not one unencrypted OpenSSH key whose public half, derived by `ssh-keygen`, is its `public`;
  - on a host that already carries an identity, a key or manifest entry that differs, or a host key of a type the
    identity does not carry.
  - while a `.porelo-host.kept.<name>` an earlier import could not put back lies beside a file it would replace,
    naming it, so a person moves it back or removes it first.

  It answers one line: `imported` when it wrote or removed a file, `unchanged` when the identity was already there.
  It does not restart sshd; the play does.

Both refuse any `SUDO_*` variable and hold the `forge.conf` lock and the follow lock, as the follow does; export,
on a host with no follow lock file yet, takes the `forge.conf` lock alone, since a follow takes that one first. No key
text reaches standard error, the journal or a refusal.

## Who reads the host's status

`porelo-host org status` (with `--json` for the JSON form) answers two callers differently:

| Caller | Reads | Holds |
|---|---|---|
| root, logged in or from root's units, never through sudo | the organization as root applied it, read at once | everything, the admin pin's fingerprint (`pinned`) and the last refusal included |
| a person marked `host_admin` for this host, logged in as themselves | `/run/porelo-host/status.json`, as the last follow wrote it | the host, the organization's state, the commit it follows, the persons with their state, host admin mark and project roles, the projects with their state, reason, time and repositories, and when that follow ran |
| anyone else | nothing | |

Each follow writes the second view whole, by rename, mode 0640, owned by root and the group `porelo-host-admins`.
It is built from root's status by naming the fields it may hold, so a field root's status gains later stays out of
it; it never holds a token, key, pin or fingerprint. The follow keeps that group to exactly the admitted persons the
organization marks `host_admin` for this host: it makes the group as root when it is missing, and a person no longer
marked, locked or pending leaves it at the next follow. A purged person's account leaves with its groups. A group
change reaches a login when it starts: a person newly marked logs in again before they read the view, and one no
longer marked keeps reading it until their open sessions end.

A caller without root gets past the root gate for this one read alone, asked as `org status` or `org status --json`
and without sudo; any other verb, option or a call through sudo is refused with 77 as before, and such a caller never
writes. The answer is root's line, or root's JSON, with the view's age: `followed <time>, <n> minutes ago`, or
`age_seconds` in JSON. A follow that cannot keep the group leaves the last view in place and exits 69, so the view's
time is what tells a stale one. A host no follow has written the view on yet answers `not followed yet` (`{"followed":null}` in JSON),
exit 0. A view that is not root's 0640 plain file is refused with 69, as is one that is not UTF-8; one that is not JSON with 65, and a person outside
the group, who cannot read the file, with 77.

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

Each follow pass, every five minutes and whenever a person asks one, brings a carried repository's copy up to
central's `main` by itself where the copy's `main` is an ancestor of it. For a repository other than the project's
own, root reads central's `main` with the host's read key for that repository into a private scratch repository
seeded with the copy's `main`, so only what the copy lacks travels, and fast-forwards the copy's `main` unless a file
under `.forgejo/workflows`, `.gitea/workflows` or `.github/workflows` changed on the way; such a change waits for a
person's pull request from `central/main`. The project's own repository follows central's tip the same way, through
the project file Porelo verified: a workflow or an image `snippet_file` holds it at the signed file. The copy is never
moved backwards or sideways, and the row is measured again after the move. Every project's rows are
measured before any copy is moved, and each move has its own minute; one that does not fit in the pass or runs out
leaves its row `moved-on` until the next pass, and Git checks every object root fetches for it.
A row's central state is `up-to-date`, `ahead` (the copy holds commits central lacks, and central nothing new), `moved-on` (central holds commits the copy
lacks and the follow did not take them), `diverged` (each holds commits the other lacks: the copy's own work waits
for `porelo host pull` and a task that merges `central/main`, then the person's gathered push), or `cannot-say` with
a reason.

`Projects` and `WatchProjects` report this progress as `hostState` and `hostWhy` on projects and
repositories. Waiting and refused progress can only withhold what the placement file authorizes.
A project whose placement file does not read stays listed, with `hostFileError` naming the file and
why, and every other project is listed as usual.
In particular, `waiting-for-token` withholds carrying until a person supplies the forge token to
install each new repository's read key and copy it; the host keeps no token. Unavailable or
malformed publications withhold carrying with `cannot-say` and an explanation naming the refused
field or value. Repository progress distinguishes a taken copy from one still waiting or not
carried, independently of its last central distance.

`taking` means the follow holds a token and is copying repositories. It publishes progress after
each copy: `hostTakingDone`, `hostTakingTotal`, and the fixed UTC start time `hostTakingSince` in
`Projects` and `WatchProjects`. The counts are absent while the signed repositories are being
counted; all three fields are absent in other states. `waiting-for-token` means a token is still
needed, including when a copy failed and its repository's reason explains why.
Root's `porelo-host org status` answers during the run. Current unlocked project members can read
progress and the last measured central distances during the run; membership ACLs are refreshed
before any progress becomes readable. Root alone can open the follow lock, which is root-owned `0600`. A member's daemon checks the
published follow PID, its start ticks and the machine boot identity through `/proc`. A missing,
hidden, terminated or different process, a different boot, or an unreadable identity reports
`cannot-say`. These identity fields remain inside the member publication. An older Porelo also reports `cannot-say`
while `taking` is on disk.
A non-root admin reading a saved `taking` row receives `cannot-say` because it cannot tell a live run from a stopped one, without opening the follow lock.

A project the host carries and the person has not taken on this machine yet lists the host's room as its
conversation when the host names a homeserver for it, not ready until it is set up. `SetUpMessages(project)` takes the project first, as its first start
would, and then sets the room up, so a person opens the project's room before any task. A project file that does
not read lists a conversation that is not ready, its detail naming `porelo project check`, never the error's words,
rather than none.

## Open pull requests over the daemon

`org.fuin.porelo.Tasks1.PullRequests(project)` reads the open pull requests this account's person token can
see in the project's repositories carried by the host. Each row names the repository key, positive review number,
title, exact head commit, author, committer, `Assisted-by` and `Co-Authored-By` trailers, and state. Makers come from
the local mirror or the head fetched from the host's forge into Porelo's own store, never the forge's account
fields. They are claims in the commit, not a signature or approval judgment.
Titles and makers are bounded plain text for display; clients must render them as text.

A reply is one varlink message (4 MiB), and no project is refused for having more open pull requests than one holds.
When the rows do not fit, `pullRequests` and `ended` hold the first of them and `paged` says how many each list holds
in all, names the `reading` and gives `next`; `PullRequestsPage(project, reading, from)` with that `next` answers the
following rows and its own `next`, until a page has none. Positions run through `pullRequests` and then `ended`, so
appending each page's two lists to the first reply's gives each list whole, every row once, in order. A reading is the
one forge read the first reply answered, a `PullRequests` reply or a watch's snapshot: the daemon keeps it ten
minutes, and at most its 16 newest. A reading no longer kept, or one of another project, answers
`PullRequestsReadingGone`, and the client asks `PullRequests` again; a position the daemon did not give answers
`InvalidParameter` naming `from`. A reply that fits names no reading and keeps none.

A project carried for this person can be read before its first start places a `project.yml` on the
machine. Before the first read, both methods take an unplaced project as a question before a start does:
the host's helper lends a fetch token, the signed project file is verified and placed, and the file names each
repository's copy by its central address. A watch takes once before its first round. A refused take answers
`HostPullRequestsRefused` with the take's reason. A project neither placed nor carried for this person is refused,
naming the known projects, including carried ones.

`WatchPullRequests(project)` with varlink's `more` sends an initial snapshot, then changes, polling five seconds
after each completed round. `pullRequests` is the complete current open list; `ended` contains confirmed `merged`
or `closed` rows once per observed transition. These events end individual rows; the watch stays open. A review
opened and ended entirely between polls can go unseen. Without `more`, the watch answers one snapshot. A snapshot
that does not fit one message is paged as a `PullRequests` reply is: it carries its first rows and `paged`, it is a
reading of its own, kept the same way, and `PullRequestsPage` gives its rest while the watch goes on; a snapshot that
fits carries no `paged`. A newer snapshot's reading may take an older one's place, so a client still paging an older
snapshot drops it for the newer. The stream ends only on a named error of the watch's own; `PullRequestsReadingGone`
is only ever an answer to `PullRequestsPage`.

Both methods read the vault entry `<name>.person` written by `porelo host forge-token`. The name and port come from
the same bounded, root-owned plain forge-settings file that command reads. A missing or unusable entry answers
`MissingHostForgeToken(credential, storeCommand)`, naming `porelo host forge-token`. A locked or unreadable vault is
`HostPullRequestsRefused` with reason `vault-unreadable`. When the gate's mirror lacks a review head, the reader
fetches exactly `refs/pull/<number>/head` from that repository's loopback copy into
`$XDG_STATE_HOME/porelo/reviews/<project>/<repository>.git`. The fetched ref must name exactly the row's head before
its makers are read. The person token reaches git only in its environment, with no proxy or redirects. The gate's
mirror is never written. Neither method is available to a task through MCP: they use the person's forge identity
to read work awaiting human judgment.

HTTP stays on the configured port at `127.0.0.1`, with no proxy or redirects, a ten-second connection timeout and
a twenty-second request timeout. Each HTTP response is limited to 32 MiB, above the largest legal page of pull
requests, and each daemon reply to one varlink message (4 MiB), the rest in pages as above. Every page of open
pull requests is read, as far as the forge says the list goes: a forge may answer fewer than the 50 asked on every
page, as a Forgejo whose `MAX_RESPONSE_ITEMS` is lower does, so a short page is no end. The list ends where the
`Link` header names no `rel="next"` page or, without a `Link` header, where `X-Total-Count` pull requests are read;
a forge that says neither is read to its first empty page. Only the header's word is taken, never its address: the
next page is asked of the same forge by its number. A page that repeats a pull request already read is refused as an
unusable answer. Maker queries allow ten seconds and 64
KiB of output. Review fetches are shallow, keep what they receive in one pack whatever its size, and allow twenty
seconds per git command; the makers query, each fetch command and the 75 seconds a read waits for another read's
fetch of the same repository follow the account's `machines` factor. Shown fields keep at most 1000 code points,
and at most 50 help trailers are returned.
A repository the forge answers with an error is refused alone, in the answer's `refused`, with its status, the
path asked (`/api/v1/repos/<copy>/pulls`, never the host's address or the token) and a reason: `forge-access` for 403
or 404 (its copy is not made yet or the person is not admitted: an administrator of the host makes it or admits
them; the forge answers both alike), `forge` for any other status (ask again later), and `not-carried` for an earlier
row of a repository the host no longer carries. The other repositories still answer, and a refused repository's
earlier rows are kept, not ended, until it answers again. A 401 refuses the whole read with `forge-token`: the forge
refuses the person token, renewed with `porelo host forge-token`.
Only the repositories the window shows as carried here are asked: those the host's published progress names taken
(the project's own goes with the project), within those its project record names. A record names the repositories
the host's card names, and none when the card carries the whole project. Progress that does not read refuses the
whole read with `not-carried`.
A bound, a head whose makers cannot be read or fetched, or a forge that cannot be reached refuses the observation and ends a stream
with a named error. Absence alone never means closed. Clients must mark retained data stale and ask again.

## A person's gathered push and pull

`porelo host push <project> [<repository>...]` sends the host's approved local `main` to central; `porelo host pull
<project> [<repository>...]` brings central `main` to local `central/main` for reconciliation in a task. These are the
person's commands, run in their own account, replacing `porelo-host-push` and its `--pull` mode. Pull never changes
local `main`, and a gathered push never forces central `main`. A rejected push stops before later repositories and
brings central's missing commits back for a task to merge and obtain approval.

The window offers the same gathered push through Tasks1 `PushToCentral(project, repositories)`. Repository
selection accepts project-file keys or forge names and is optional. Its stream names each forge repository and its stage, then lists what was pushed and what was
already up to date. If everything is unchanged, `NothingToPush` says so. Vault unlock, missing central credentials,
refused central authentication, untrusted host setup, unavailable local token issuance and central push refusal
are separate named answers the window can act on. No process output or credential is sent to the window.

Before the first push, every selected central Git receive endpoint is checked with the central credential and
the checked CA. Public repository reads do not replace this write-authentication check. A central HTTP `401`
stops the whole selection before any repository is pushed. The command names the central forge and `401` and
exits nonzero; the daemon returns `CentralTokenRefused` with `forge` and `status`. That refusal asks for no
credential and offers no replacement-token command. A token refused during a later transfer retains any earlier
confirmed pushes in its answer; no rollback is claimed.

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

The person's `$XDG_CONFIG_HOME/porelo-host/push/<project>.conf`, located through the account database, supplies `local`,
`owner`, `central-ca`, and `repository <forge-name> <project-key> <https-url>` lines. Follow takes each key from the
verified project file. Several keys for one forge repository repeat the row and resolve to one transfer. Existing
`repository <forge-name> <https-url>` rows remain readable. Either name selects the same repository; unknown or
ambiguous names and selecting one repository twice through different names refuse before transfer, naming the
available key/name pairs. Its directories belong to the person and are not writable by others; `push` is 0700 and
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

The authentication check uses a root-checked `curl`, with its defaults disabled, the same central CA and credential,
HTTPS only, and no redirects or proxies. It reads the Git receive advertisement with a fifteen-second deadline;
captured output is bounded to 16 MiB. Authorization reaches curl only through private stdin. HTTP error bodies
are discarded, and incomplete error bodies cannot erase an observed `401`.

The only privileged operation is the existing `porelo-host forge issue-token`, through this exact sudo rule:

```sudoers
%porelo-people ALL=(root) NOPASSWD: /usr/sbin/porelo-host forge issue-token
```

It takes the person from `SUDO_USER` and checks `SUDO_UID`, with no person argument. Sudo starts in `/` with only
`PATH=/usr/sbin:/usr/bin` and `LC_ALL=C`, so caller secrets, locale and working directory are not inherited. Its stdout goes directly into
the vault and the current operation, never a terminal. If saving a newly issued local token fails, the gathered
operation warns and continues using it in memory. There is no privileged push or pull verb. For removal, the host
keeps `porelo-host project check-central`, the read-only preservation check; `down` is separate.

## Ask the host to follow now

Run `porelo host follow` in the person's account on the host. The window runs this command through the host's
login shell; porelod never invokes the privileged verb. It waits for one follow pass, joining a pass already
running or requested by another person or the timer. Both waiters receive the same completion, including failure.

The command runs checked system programs from `/`, without a shell or caller environment:
`sudo -n /usr/sbin/porelo-host org follow-now`. The person comes from sudo's `SUDO_USER` and `SUDO_UID`, checked
against the account database and current root-owned admission and revocation records. Task accounts and unknown,
locked, pending or revoked people cannot start a pass. The exact additional sudo rule is:

```sudoers
%porelo-people ALL=(root) NOPASSWD: /usr/sbin/porelo-host org follow-now
```

There is no login, service or token argument. The fixed `porelo-host-follow.service` continues to run
`/usr/sbin/porelo-host org follow` as root with no caller credential. The package and this sudo rule must both be
installed; an older host program answers unavailable and the client names the update it needs.

A completed pass answers `{"invocation":"<32 lowercase hex digits>","completed":true,"exitCode":0}` and exits
with that pass's status. A failed pass carries its nonzero status in the answer and the process exit code. A
refusal or inability to obtain a result has no completed answer and a diagnostic on stderr. Status 64 means usage
or a missing sudo identity, 65 means refusal, 69 means unavailable, and 73 means an unsafe or inaccessible host
file or lock; a completed pass may itself return those nonzero statuses. The client preserves the host's output
and status; it maps an older host's unknown-verb status 64 to unavailable status 69.

The host waits at most 660 seconds, including a ten-second account lookup bound. The person's process allows
twelve minutes for that answer. Configuration locks are released before requesting or waiting for the pass.
Canceling a waiter leaves the shared service running. Private receipts under `/run/porelo-host/follow-now`
associate systemd start jobs with invocation identities and retain completions until reboot, so a newer pass
cannot replace the result selected by an earlier caller. Organization details and service logs remain root's.

## A task certificate judged for the host

A machine is a host whose persons start tasks when `/etc/porelo-host` or the installed `/usr/sbin/porelo-host` is
there. The program is root's alone and reached by sudo. Porelo runs
it only by that path, through `sudo -n`, never with a shell, and never passes a task name beginning with `-` or `@`:

- **Start:** `porelo-host task start <task>`, standard input the task's certificate line and one line end, after
  Porelo has checked the certificate itself. Exit 0: standard output is the forge token alone, or empty on a host
  without a forge; one trailing line end is not part of it. Porelo keeps the token in the task's vault entries for
  its gate, never in the container or a file. Any other exit fails the start: 69 is answered as 69, every other
  code as 65. Porelo shows the helper's standard error, never its standard output.
- **End:** removing the task runs `porelo-host task end <project> <task>`, which answers
  `{"task":"<task>","ended":true}`; any other answer is reported as not ended.
- **Record and token:** the host records a task as `/var/lib/porelo-host/tasks/<login>/<project>/<task>.json`, the
  project taken from the certificate's principal (`default` for a task started uncertified), and names its forge
  token `porelo-task-<project>_<task>`; a project never holds `_`. So one person's tasks of one name in two projects
  keep their own record and token, and neither's start or end touches the other's. A record of the earlier layout,
  `tasks/<login>/<task>.json`, is still listed and swept, and ended by its token `porelo-task-<task>` when its
  certificate names the project the end names; such records are not moved.
- **Show:** `porelo task certificate <fingerprint>` asks `porelo-host task show <fingerprint>` for a
  certificate this machine did not keep, and does not believe an answer naming another key.

The helper has Porelo judge the certificate before it records anything, run as root's throwaway user with no home,
vault or network; it reads only the files its flags name and writes nothing:

```
porelo task check-certificate --task <task> --login <login> --keys <file> [--krl <file>] [--most-days <days>] [--json] < certificate
```

- **`--keys`** is the person's keys in force, one OpenSSH public key line each, as the host applied them from its
  verified organization; an empty file refuses every certificate. **`--krl`** is the revocation list in force.
- **`--most-days`** is the longest a certificate may hold, in whole days from 1 to 3650, 7 without it; the host
  passes its own `certificate_days` from [`host.yml`](configuration.md#the-hosts-settings), never a person's.
- **Standard input** is one line of at most 16 KiB, ending in at most one line end; anything longer, a second line
  or no line is refused before anything parses it.
- **Accepted** only as `porelo task start` issues it: one `ssh-ed25519-cert-v01@openssh.com` user certificate,
  signed by exactly one of `--keys`' lines, an Ed25519 key, neither that key nor the task's key revoked by the
  list; exactly one principal, `task:<project>:<task>`, of that task and a project name or `default`; the key id
  `porelo task <task> by <login>`; valid now or within five minutes from now, and valid before at most `--most-days`
  days and five minutes from now, both by the host's clock, as the start judges it; no critical option, and exactly
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
  65 for a certificate not accepted, 64 for a task or login that is not one or a `--most-days` outside 1 to 3650,
  66 for a named file missing or unreadable, the revocation list included, and 70 for Porelo's own fault. The host
  records the certificate only after exit 0, and checks itself that `project` is one the person belongs to.

## A task started without a certificate on a host in test mode

A measuring host has no applied organization and no certifier, so no task could start there. Only root switches
on test mode: its role writes `/etc/porelo-host/test-mode.json` at `up`, `{"v": 1, "switches":
["start-uncertified"], "why": "<text>"}`, and removes it at `down`. The host acts on it only as a regular file, not a
link, root's, mode `0600`, in root's directories that neither group nor others can write; a file that is missing,
of another `v`, unreadable or failing a check holds no switch, never all, and a name the host does not know is
ignored. `sudo porelo-host test-switches --json` answers `{"switches": [...]}`. Where a start gets no certificate,
Porelo asks it; without `start-uncertified` it refuses as before. With it, Porelo warns on standard error that the
task starts **without** a certificate and that what it passes on is vouched for by nobody, asks "Start the task
without the check? y/N" at a terminal, and takes `--yes` as the answer; without a terminal and without `--yes` it
refuses. It then runs `porelo-host task start <task>` with an empty standard input, which the host accepts only
while the switch holds, for a person in `porelo-people` and the project `default`, and records as uncertified.
No flag of Porelo's turns the check off on a host that is not in test mode, and a start through the daemon is
refused as before.

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
  `porelo-host task show` answer per line.
- **Each new commit, merges included, must carry an SSH signature that holds now**, over what git verifies - the commit
  without any header whose name starts with `gpgsig` - however the commit is dated: a person's by a key in force,
  verified as git verifies it by each principal `ssh-keygen -Y find-principals` names for the key's lines, a
  task's through its certificate from a certifier, valid now, for `task:<project>:<task>`, or a task's plain key whose
  recorded certificate would itself be accepted; nothing the KRL revokes. Anything else is refused, and a deleted ref judges
  nothing.
- **The pushed objects are read through the quarantine git gives the hook**; no other git configuration, hook or
  proxy of the account takes effect.
- **Exit 0 lets the push in; 65 refuses it**, with one line per refused commit on standard error, `<commit>
  <reason>`, which the hook may show the pusher. Every commit is judged, however many the push brings, so all
  refusals are named at once. The JSON answer on standard output is every verdict with its signer, for the host to
  record; its fields are in [Commands](commands.md#commit).
- **The commits are walked in windows of 4096**, each handed on as it is judged, so neither the verifier nor the
  host keeps a list of the push in memory. Every window walks against the refs as they stood when the walk began,
  so a ref that lands meanwhile hides no commit of the push; refs that cannot be read whole refuse it. Root stages
  the verdicts it reads under `/var/lib/porelo-host/push`, on disk, not in `/run`, and removes them once the push
  is answered; a judgement first removes those a killed porelo-host left, older than its own deadline, and names
  in its log a folder that cannot be removed.
- **The pusher sees the first 100 refused commits and how many more are refused.**
- **The worker may run 270 seconds and the host waits 280** for it, both times the host's `machines` factor from
  `/etc/porelo-host/host.yml`.

The host's forge itself shows these commits as unverified (`no_gpg_keys_found`): Forgejo counts an SSH signature only
for a key its account holder verified by signing a token in the forge's web settings, which neither the follow nor a
task's start does, and a commit whose committer email is not the account's never verifies there. That is how it is
meant: the push judge above is the check, and the forge's badge is not.

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

A playbook writes only what differs; `up` also restores admitted people's runtimes and restarts their user managers.
It asks:

| Command | Answer |
|---|---|
| `porelo agents --json` | `{"vault": "open"\|"shut"\|"none", "agents": [...]}`, each with `name`, `found_by` (the description or executable that named it), `credential` (the vault entry it signs in under: its default provider's, or its own name when it is not brokered), `flow` (how its sign-in works, as its definition's `login.flow` says: `paste-code`, `device-code` or `loopback`, and "" when it does not say) and `signed_in`. `signed_in` is given only while the vault is open, and is false for a sign-in lacking what its provider sends beside the token; `credential` and `signed_in` are both left out for an agent whose default provider cannot be used (none named, none declared, or a dialect it does not serve), since its start is refused whatever the vault holds; `name`, `found_by` and `flow` are always given. No value is printed. |
| `porelo-setup.sh --json` | `{"changed", "parts"}` alone on standard output, every other line on standard error. `changed` is false when nothing was installed or written. A package older than the repository offers stays, and is said on standard error. |
| `porelo task stop --all` | Stops every running task of the account and its helpers, and removes nothing. Exit 0 when nothing of the account runs any more, nothing having run included; 70 when something survived, each named on standard error as `porelo: still running: …`. `down` runs it before `vault clear` and stops on 70. Standard output is for a person. |
| `porelo vault clear --yes --force` | Clears the account's vault, locked or open, and what the keyring caches of it, without asking and without a terminal. Exit 0 when the vault is gone; 1 when anything was not removed, a running task holding a token from it among them, each named. `--json` gives the same as values. |
| `porelo vault put NAME --json` | `{"name", "stored"}` alone on standard output, every other line on standard error; `stored` is false when the entry already held exactly this value, kind and settings, and then nothing is written. The same for `--from-file` and standard input. No part of the value is printed. The play reports a put as changed only where `stored` is true. |
| `porelo vault status` | Exit 0 open, 3 shut, 4 no vault, 70 for a file that is not a vault. `--json` gives `{"exists", "open", "path"}`. "Open" is proved by reading the vault with what the account keeps. |

**A person locked out.** [Root's person maintenance](#roots-person-maintenance) removes admission and keeps
the record for retry on failure. Vault cleanup runs as the person, without a terminal or input, only after
all their tasks have stopped.

**A host nobody sits at.** The second play stops when `porelo vault status` answers 3 or 4, and the vault is
opened from outside: on their own machine a person runs `porelo host unlock HOST --host-key SHA256:... --login ACCOUNT`,
which derives the share with their key and sends it over ssh, and makes the vault when there is none, as
[Credentials](credentials.md) says. A host vault has no passphrase and never asks for one.
