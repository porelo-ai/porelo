# The project file

Everything `project.yml` can say, and what each key is for.

**You do not need one to begin.** A task started in a checkout with no project named runs in the
built-in project `default`, which uses Porelo's own settings. Write a project file when you want other
settings.

**It lives in a git repository, committed: the code's own, or one of its own that names the code repositories
under `repositories`.** A machine gets it with `porelo project follow <name> <url or local path>`, and reads a commit,
never the working tree; Porelo never writes one. That command names anything wrong with
the file, such as an egress set this machine does not have.

**Only `project.name`, `project.security_class` and `image.base_image` are required.** Everything else
has a default that suits most projects.

**A project is not a repository.** It is a named unit of work over one or more repositories: its own,
which holds this file, and any it lists under `repositories`. `porelo project list` shows them. A project that
lists repositories works only in those: its own repository holds the file, its planning and its issues, and no
task is started there. A project that lists none works in its own.

```yaml
project:

  # Required. Lower-case letters, digits and hyphens, at most 63 characters.
  # A task's container is named 'porelo-<name>-<task>'.
  name: "myproject"

  # Optional. For people; shown where the project is listed.
  description: "The thing that talks to the payment provider"

  # Required: offline, guarded or online. See security.md.
  #   offline  no network beyond this host; external egress and upstream are refused,
  #            host_services is allowed, and the project is followed only from a file.
  #   guarded  reaches what egress names, pushes to a gate, holds no upstream credential.
  #   online   pushes to a gate that passes its own branch on at once; needs 'upstream'.
  security_class: "guarded"

  # The immediate Git destination for the project's OWN repository: the host's forge or a
  # central or company forge. Required for online, refused for offline, and never seen by
  # the agent: in guarded the gate forwards what a person approved, and in online it
  # forwards the task's own branch at once.
  upstream: "git@github.com:you/myproject.git"

  # Optional. The one word the agent of the project's own repository goes by: "Agent Planner" in
  # a conversation and on its commits, with the rules of a repository's nickname below. Without
  # one it goes by "Agent myproject".
  nickname: "Planner"

  # Optional. The public keys of the people whose signed commits change this file. A commit
  # signed by a key a machine has pinned that adds or removes one here makes the machine pin or
  # unpin it. A key never counts for the commit that adds it.
  # signers:
  #   - "ssh-ed25519 AAAA... you@example.org"

# Optional. The repositories this project's agents change.
# A task works on exactly one, chosen with 'porelo task start --repository <name>' or by the
# checkout it is started in; there is no default. Each has its own mirror, gate and review branch.
repositories:

  # Names use lower-case letters, digits and hyphens, with single dots between letters or digits;
  # they start and end with a letter or digit and have at most 63 characters.
  backend:
    # Optional. Without an upstream, the work stays on this machine.
    upstream: "git@github.com:you/backend.git"
    description: "The thing that talks to the payment provider"
    # Optional. The issue prefixes this repository owns, each one or more capital letters and named
    # by no other repository. A task on it is told them in PORELO_ISSUE_PREFIXES ("B,P").
    issues: ["B", "P"]
    # Optional. The agent this repository is worked with, as 'porelo agents' lists it: a task
    # start without --agent takes it, and refuses when it is not installed here.
    agent: "claude"
    # Optional. One word, a letter first, at most 32 letters, digits or hyphens: its agent goes by
    # "Agent Payments" in a conversation and as the author of its task's commits. Without one it
    # goes by "Agent backend". No two repositories, the project's own among them, may go by one
    # name, whatever the case.
    nickname: "Payments"
    # Optional. How far others can build on this repository, one of four words; any other is
    # refused. Porelo only checks the word and changes nothing by it.
    #   crazy-chicken  Just hatched and running in every direction: interfaces, formats and even
    #                  the purpose may change any day. Use it only to try ideas, never to build on.
    #   early-bird     Does its job and is used inside the project, but its contracts may still
    #                  change with notice in the changelog. Build on it if you can follow its
    #                  snapshots.
    #   stable         Its contracts change only in a new major version, with a migration path, and
    #                  it is released and tested on every supported platform. Safe to build on.
    #   deprecated     Kept only for those who still depend on it: no new features, fixes only for
    #                  security. Its replacement, if any, is named in its README.
    maturity: "early-bird"

  frontend:
    upstream: "git@github.com:you/frontend.git"

    # Optional. ADDED to the project's egress, never replacing it. To make a repository reach
    # less, move the set out of the project's block into the repositories that need it.
    egress:
      sets: [nodejs]
      domains: ["packages.internal.example"]

    # Optional. REPLACES the project's limits key by key. A key not written here keeps the
    # project's value, not the default. 'none' opts out of a limit.
    limits:
      memory: "16g"

image:

  # Required. What the task image is built from. Porelo adds curl, git, ssh, tmux and python3 with
  # apt, apk or dnf; a base without them must ship curl, git, ssh and tmux itself, and builds
  # without python3 if it has neither it nor a package manager.
  base_image: "ubuntu:24.04"

  # Optional. Extra Containerfile lines, run as root at build time. Keep it small: any change
  # rebuilds the image.
  snippet: |
    RUN apt-get update && apt-get install -y --no-install-recommends jq \
        && rm -rf /var/lib/apt/lists/*

  # Optional, instead of 'snippet': the same lines in a file of their own.
  # snippet_file: "Containerfile.fragment"

  # Optional. Where apt fetches from at build time. This is the default.
  # Use http: Ubuntu mirrors serve no TLS, and apt checks the signed Release file instead.
  # Name one fast mirror rather than a list - apt spreads requests over all of them, so a slow
  # one slows every build. Only matching archive paths are replaced; suites and signing keys stay.
  # The default changes Ubuntu only; a source without a path (a cache) replaces every URIs line.
  package_sources:
    - "http://azure.archive.ubuntu.com/ubuntu/"

# Optional. What the task may reach. A name not allowed here does not resolve at all.
egress:

  # Curated sets by name. 'porelo shield sets' lists what this machine has.
  sets: [os-packages-debian, git-hosting, maven]

  # Any other host by name. Ports 80 and 443 only.
  domains: ["nexus.corp.example"]

  # Services on this host, on TCP 443 only, in every class including offline.
  # These names resolve locally to the gate's host address, never through upstream DNS.
  host_services: ["forge.internal.example", "registry.internal.example"]

  # Only what a task reaches WHILE IT RUNS needs a set or a domain. The image is built on the host,
  # outside the task's firewall, so packages a 'snippet' installs need none; the gate fetches and
  # pushes on the host, so the task's own git needs none. A company's mirror is a domain, not a set.
  # What each set opens: os-packages-debian and -fedora the distributions' package mirrors;
  # git-hosting github.com, gitlab.com, bitbucket.org, codeberg.org; maven Maven Central; nodejs,
  # python, go, rust their package registries; containers the container registries.
  # 'porelo shield sets --verbose' lists every host.

  # Names that must never resolve, whatever allows them; also wins under an allowed parent.
  # It refuses a name, not an address: a host sharing an allowed address stays reachable there.
  refused: ["telemetry.nexus.corp.example"]

# Optional. What one task may consume. These are the defaults.
limits:
  memory: "8g"     # "none" opts out
  pids: 2048       # process count; stops a fork bomb
  cpus: "4"        # "none" opts out
  hand_in: "64m"   # the largest file 'porelo task give' may hand to a running task

# Optional. Whom the project's tasks may write to. Tasks of one project reach each other by task
# name without being listed, and a project with a conversation has 'people', the people in it,
# without being listed either.
mail:
  peers:
    reviewer:
      # Required. <transport>:<address>; '<transport>:' is the project's conversation there.
      address: "matrix:"
      # Optional: vouched (one of your own machines) or external (default, checked again here).
      trust: external
      # Optional. Messages per day with this peer, each way.
      per_day: 200
      # Optional. How closely a message to this peer is watched, over its class's rule below.
      # mode: prompt
  # Optional. How closely a message is watched, set once for every task of the project: prompt (it
  # waits for a person), allow (it goes once the filter accepts it), deny (it never goes), off
  # (like allow; the filter still checks). A person can still hold a peer's messages.
  # rules:
  #   project: allow   # another task of this project - the default
  #   room: allow      # the project's conversation and the people in it - the default
  #   others: deny     # every other peer - the default
  # Whatever the mode, only what the filter flags waits for a person. A mode outside the four is
  # refused when the file is read, naming the key. The mode is read at every message, so a changed
  # file holds for the next message of every task.
  # Optional, default blocking. 'reporting' has the filter only report what it finds in a task's
  # outgoing message, never refuse it; a person holding it is shown what it would have refused.
  # outgoing_filter: blocking
  # Optional. Each transport's own settings, by scheme. A transport named here gives the project its
  # conversation, through which its tasks reach each other; a peer is needed only for someone else.
  # Refused once 'access' names an access file: they are in that file then.
  # transports:
  #   matrix: {}

# Optional. Where the project's access file is - its host, persons and transports' settings,
# signed like this file and kept out of it. Exactly one of the two. See "The access file" below.
# access:
#   repository: git@forge.example:acme/acme-access.git   # access.yml in a repository of its own
#   file: access.yml                                      # or access.yml beside this file

# Credentials every task of this project holds beyond its agent's own: vault entry name and the
# destination it is for. Only NAMES are here; a task gets a token worthless anywhere else.
# Anyone who can start a task here can use them. A run may add more with --credential but not
# remove these. An offline project declares none.
credentials:
  search: brave-search

# Optional, and for an ONLINE project only - this example is guarded, which refuses it. The forge
# that builds what a task pushes, and the vault entry it is read with; each build's verdict, and
# the log of each failed job, arrive in the task's /porelo/files.
# HTTP is allowed only on loopback (127.0.0.1, ::1 or localhost), without user information.
# builds:
#   forge: github
#   credential: github-actions
#   api: https://ghe.example/api/v3   # optional; unset is the forge's own public API
#   logs: failure                     # optional: failure (default) or all

# Optional. The values a task of this project may be started with, each checked against its
# declaration before anything is created and fixed for the task's life. A start hands them in with
# --set NAME=VALUE, --set NAME (from the caller's shell), --set-file FILE or --set-env. They are
# not secrets: they stand in the task's record and environment; credentials belong in the vault.
parameters:
  type:
    description: "The kind of work"
    values: [feature, bugfix, hotfix, release]   # a fixed list, or
    default: feature
  ticket:
    description: "The ticket in the tracker"
    pattern: "[A-Z]+-[0-9]+"                     # a regular expression the whole value matches
    required: false
    env: "TASK_TICKET"                           # default PORELO_PARAM_TICKET; none keeps it out

# Optional. How a task's branch at the forge is named, and which names the forge accepts. Without
# it, porelo/<task>. A repository may name its own under 'repositories.<name>.branch'.
branch:
  template: "{type}/porelo/{task}"   # {task}, {project}, {repository} and the values above
  pattern: "^(feature|bugfix|hotfix|release)/porelo/[a-z0-9-]+$"   # required with a template
```

## branch

A forge commonly allows branches only under its own paths, one per kind of work, with rules attached to each. The
`branch` block makes a task's work arrive there, as `bugfix/porelo/login-fix` instead of `porelo/login-fix`:

- **The name is settled at the start** from `template` - or from `--branch NAME`, for a name no template makes - and
  must match `pattern` and be a name git takes. A name the pattern refuses, or a placeholder no value fills, is
  refused before anything is made; the start shows the branch, and `porelo task status` keeps showing it as `forge`.
  A resumed task, and a task after the file changed, keeps its branch.
- **An `online` task's push arrives under it** at the upstream. A start whose branch the upstream holds already, or
  whose pattern matches the upstream's default branch, is refused, since the gate passes work on with force. Two
  tasks of one repository never share a branch.
- **A `guarded` task's approval goes to it**: `porelo gate approve` with no branch approves onto the task's own branch,
  and a branch a person names must match `pattern`, so work reaches only branches the forge's rules cover. A project
  without the block approves as before, onto `main` unless another branch is named.
- **The pattern also keeps those branches from every task**: the gate drops every branch it matches from the mirror
  an agent fetches from, as it drops `porelo/*` without one, so no task sees another task's work at the forge. A
  template without a pattern is refused for that reason.

## Limits

A task gets 8 GB of memory, 2048 processes and 4 CPUs unless its project replaces them. `limits.cpus: none`
removes the CPU quota and the default thread hint. With a CPU limit, `OMP_NUM_THREADS` defaults to that limit rounded
down, at least 1, so `nproc` and tools using it size their workers accordingly. A nonempty value set in the image or
an explicit project value wins. `getconf` and `sysconf` still read the host's cores; the CPU quota throttles the work.

Image builds use the project's memory limit and CPU quota, and pass `OMP_NUM_THREADS` to their `RUN` steps as a
build argument, rounded down to at least 1, so `RUN nproc` sees the bound. The argument is declared after the packages
layer and before the snippet and agent layers; it does not become an image environment variable.
`limits.cpus: none` leaves the build without these resource flags or the build argument;
`limits.memory: none` removes the memory flag. Open-file limits stay the container runtime's.

## Services on this host

`egress.host_services` names services on the task's own host, such as its forge and registry. Each name uses the
same lower-case host-name syntax as `egress.domains`; a name in both keys is refused when the file is read,
including when one key belongs to the project and the other to a repository. A repository's host services add to
the project's, as its domains do. `egress.refused` still wins for a service's name or a parent of it.

The task's resolver answers each declared name with the gate's address: `169.254.1.2` under pasta, or the address
Porelo probes for the gate otherwise. It never asks upstream DNS for that name and never adds the address to the
ordinary egress allow set. The firewall opens that address on **TCP 443 only**, beside the gate's own port, in
every security class **including offline**. No host-service declaration opens no additional host port.

Under pasta this reaches the host's loopback. A fallback network must expose the service at the gate's fallback
address; a listener bound only to loopback may be unreachable there. TLS still uses the declared name; trust in
its certificate must already be present in the task. Whatever listens on the host's loopback 443 is reachable
by every task of a project that declares a host service: see [the accepted exposure](decisions.md#declaring-a-host-service-opens-the-hosts-loopback-443).

## A repository's own image

A repository says what it needs to be built in, in a file of its own: `porelo.yml` at its top, checked in. It holds
`image:` and nothing else - `base_image`, `snippet` or `snippet_file`, and `env`:

```yaml
image:
  base_image: "ubuntu:24.04@sha256:..."
  snippet: |
    RUN apt-get update && apt-get install -y --no-install-recommends rpm
  env:
    JAVA_HOME: /opt/graalvm
```

- **Only the image.** Egress, limits, `package_sources`, the class and every other rule stay the project's: a
  repository that could name them would widen what its own tasks may do. Any other key is refused by its name, at the
  start, before an image is built. A variable Porelo sets in every task (`PATH`
  `/home/agent/.local/bin:/usr/local/bin:/usr/bin:/bin`, `HOME` `/home/agent`, `LANG` `C.UTF-8`, or any `PORELO_*` set
  per task) is refused too, naming the value and that the line must be removed.
- **The image sees nothing of the repository:** the snippet runs in Porelo's own build directory, so a `COPY` or `ADD`
  of a repository file finds nothing there. What a repository ships for its tasks is installed at run time, or
  fetched by the snippet against a hash written into it.
- **Read from reviewed work only:** the repository's mirror at its default branch, never a branch a task pushed
  (`porelo/*`), and a `snippet_file` only as a plain file of the repository, never a link or a path that leaves it. On a
  start that fetches, the mirror's file is read again after the gate's fetch, and a refusal from the mirror waits for
  the fetched file to decide; a start that does not fetch (`--dry-run`, `--no-gate`) refuses on the mirror's file as of
  its last fetch and says so. When the mirror's `HEAD` names a default branch that does not exist, `task start` refuses
  after the fetch, naming the branch, the mirror's branches and the fix, rather than starting with the project's image;
  `project follow` warns with the same words and still follows. Each gate refresh updates the mirror's `HEAD` from the
  upstream's symbolic `HEAD` once the mirror holds that branch.
- **The project file can say it as well**, under `repositories.<name>.image`, with the same keys.
- **A local override** lies in `~/.config/porelo/projects/<project>/<repository>/.porelo.yml`, in the same format,
  never checked in.
- **What wins, key by key:** the local override, then the project file's entry for the repository, then the
  repository's `porelo.yml`, then the project's `image`. In the built-in project `default`, a task started from a
  checkout reads `.porelo.yml` in that checkout, then `porelo.yml` there, then the default image; approved work lands on
  `porelo/<task>`, never on the checked-out branch, so a task cannot change either file under the person. `base_image`
  and the snippet are each taken whole from one of them; `env` per variable, so an override that sets one variable
  changes only that one.
- **The start and its dry run name each key's source** (`base image ... (from ...)`, `image lines from ...`,
  `image env NAME from ...`, and `snippet ... lines from ...`). An image a repository's own sources shape is built as
  `porelo/<project>_<repository>` in its own build directory, so two repositories' images never replace or block each
  other; one shaped by the project's `image` alone keeps `porelo/<project>`.
- **The review ranks a change to `porelo.yml` or `.porelo.yml` as dangerous:** `porelo.yml` chooses the image a task is
  built from, as root and outside the task's firewall (ranked wherever it lies, though only the top-level one is read),
  and `.porelo.yml` is a local override, never to be checked in.

## How the file reaches a machine: following

A machine **follows** a project's repository: `porelo project follow <project> <git-url> --signed-by "<key>"`.
It pulls; nothing pushes to it. One project failing to fetch does not stop the others. `--signed-by` can
be given once for each key you sign with: every one is pinned, and a commit signed by any of them is
accepted. Which projects a
machine follows is decided by a person, one command each, and per account: two people on one machine
follow their own.

- **`follow` is synchronous.** When it returns 0, a task can be started for the project at once. It
  takes a local path or `file://` as well as a forge URL, so a repository on the same machine is
  enough, and a project followed `--unverified` starts tasks like any other.
- **Every change to a protected file is signed by a key in force before that commit.** The first-parent
  range from the commit in force to the fetched tip is checked commit by commit, each against its first
  parent, including merges, deletions and both ends of a rename. Protected files are `project.yml`,
  `access.yml` when named beside it, `machine-signers`, `porelo.yml` in the project's own repository,
  and every `image.snippet_file` named by the project, a repository, or that `porelo.yml`, read from
  the committed YAML. The `.gitattributes` files at the root and
  in their ancestor directories are protected too, since Git can transform the checked-out files
  through them. Unsigned document-only commits are applied;
  a later signed commit cannot cover an earlier unsigned protected change. The first follow and an
  accepted rewrite still check the tip against the anchor given. A tip that does not
  descend from the one in force waits for `project follow ... --accept-rewrite`: a rebase by somebody
  with the key looks the same from here as an older signed file served again to put back a rule that
  was taken away. Say it only when you know why the history moved.
- **A machine that cannot fetch keeps what it verified last**, says it is behind, and stops no task. It
  never falls back to an unverified copy.
- **An agent can propose configuration, never put it in force.** A project's repository holds its
  `project.yml`, and an agent on that repository may edit it, but its commit is not signed by a pinned
  key: it travels through the gate to a person, who signs it or does not.
- **What following changes:** what the project declares about itself, its egress sets, which agents it
  wants, and how closely its messages are watched. **What it never changes:** the vault and anything in
  it; what a person decided (a held peer); running tasks - a commit starts nothing and stops nothing.
- **A task runs against the file the machine verified**, never a `project.yml` somewhere else, and the
  commit it was verified at is kept with the task. A followed project with nothing in force starts no
  task. A task holds the file it started with: a later commit applies to the next task, never to one
  already running.
- **The repository wins for what it covers**: a local edit to a followed file is replaced, and the
  machine says so.

### When a machine does not apply a commit

`porelo project following` lists each followed project with the commit in force and how its last try
ended. A commit that was turned away is named on a line of its own, `refused <commit> <file> signed by
<fingerprint>`, beside the commit still in force: the project goes on running what it had, and what
was rejected is a separate question from what is running. Only a key's fingerprint is shown, never
anything from the vault.

| Outcome | What happened | What to do |
|---|---|---|
| `not_signed` | A commit requiring verification carries no signature. | Sign the offending commit and push the corrected range. A signed commit on top cannot cover it. |
| `unknown_key` | The signature is not authorized by the keys in force and the revocation policy. | Compare the fingerprint with the key meant to sign. It may be unknown, retired or revoked, or its signature did not verify. |
| `no_anchor` | No key is pinned for this project. | Follow again with `--signed-by`, or with `--unverified`. |
| `unreadable` | The commit or the repository could not be read. | Read `detail`. |
| `rewritten` | The tip is not a descendant of the commit in force. | Only if you know somebody with the key rewrote the history: follow again with `--accept-rewrite`. |
| `unreachable` | The repository could not be reached. | Look at the network or the URL. It may answer on the next try by itself. |
| `vault_locked` | This account's vault is shut, so the credential a private repository needs is out of reach. | `porelo vault unlock`. Nothing is wrong with the network or the URL. Reported only where a vault exists. |
| `no_credential` | The vault is open and holds no key for a repository that needs one. | Store the credential the detail names. |
| `unusable` | The commit verifies, and its `project.yml` does not read as a project, or names an egress set this machine does not have. | Fix the file and commit again. |

`unreachable`, `unreadable` and `failed` (anything else, said in the detail) may clear on the next try
by themselves; every other refusal waits for a person, and nothing changes until somebody acts. `porelo
doctor` reports the same outcomes for every followed project that is not up to date, and names the
commit each project is in force at; a machine with no key pinned does not break, it quietly stops
following, so that is named too.

### Following without the key at hand

A follow with no key is refused and names the fingerprint of the key that signed the commit. Compare
it with the fingerprint you were told, and repeat the follow with it: `--signed-by SHA256:...`. The key
is then read out of the commit that was turned away and pinned, but only when its fingerprint is the
one you typed; a commit signed by any other key is refused and nothing is pinned. The anchor still
comes from you: the repository supplies the bytes, you supply which key they must be.

- **`--signed-by` adds to the pinned keys** rather than replacing them, since a machine follows
  several projects and they need not share a key.
- **`--unverified` and `--signed-by` together are refused.** They are two different instructions,
  not a stricter setting.
- **`--unverified` skips the signature check, not the reading.** A file that does not read as a
  project is still refused.
- **Unverified is per project.** It is shown in `project following`, as
  `unverified - whoever can push there decides what tasks here may reach`, and in `porelo doctor`,
  which reports it as degraded rather than failing, since somebody asked for it. A project followed
  without a key changes nothing about what is reported for one followed with a key.

### Changing the key you sign with

The key named at the follow hands on through the file itself. **Commit the new key under
`project.signers` beside the old one, signed with the old key:** every machine following the project
checks it against the old key and pins the new one. **Then commit a list with only the new key, signed
with the new key:** the old one loses authority for new protected changes. It stays in `project-signers`
with `valid-before="YYYYMMDDHHMMSSZ"`, at the handover commit's committer time in UTC, so old
commits still verify when history is read. Git includes the cutoff instant and uses the committer
time, which the signer controls; a backdated new commit by that key is therefore checked only
against keys in force, with every `valid-before` line excluded. Several handovers fetched at once
keep every retirement, even if the final keys equal the starting keys. Historical verification
still follows commit dates: a handover dated before an older commit cuts that commit off.
A commit that changes the list counts only when it is
signed by a key in force before it, so a key never vouches for the commit that adds it. Every commit
that changes the list between the one in force and the one fetched is checked in order, along the
first parent, so the hand-on and the commit signed only by the new key may arrive in one fetch. Keys
are pinned only with a commit that is applied, never before. `project follow` says which keys it
pinned and unpinned, by fingerprint.

A commit declares its `project.signers` list, or no declaration when the file or key is absent;
no declaration differs from an empty list. Any change leaves exactly the new list in force and
retires every omitted key at that commit's time, including out-of-band keys, the signer and keys
given with `--signed-by` on that command. Extra pins survive only while the declaration stays the
same. Once a list exists, empty included, removing the key or `project.yml` refuses the new `main`
and names the removal commit and file; change the signers instead of dropping them.

A first follow compares the tip with its first parent's declaration; a parentless tip follows no
declaration. When the parent declares a list, the tip's signer must be both named there and given
with `--signed-by`, so a key already retired by that history cannot start a follow. With no prior
list, the given keys authorize the tip, and a first declaration still retires every key it omits.
An accepted rewrite compares its new tip with the declaration previously in force. All checks,
including matching a requested fingerprint, finish before a follow publishes the clone, pinned
keys, followed record or event.

Every follow fetches full history and refuses a shallow source or clone, naming the repository.
A missing parent or an unreadable declaration baseline refuses the check; it never counts as no
declaration. Switching from `--unverified` to verified starts both repositories as a first follow:
their tips must verify, even when unchanged, and no unchecked commit supplies a baseline. A named
fingerprint must have signed a protected commit in the checked range; an earlier handover counts
even when a successor signed the tip. A range with only document changes cannot match it.

Following also checks new protected changes against `~/.config/porelo/revoked.krl` when present
(`$XDG_CONFIG_HOME/porelo/revoked.krl` with that variable set), as Git's `gpg.ssh.revocationFile`.
An empty, malformed, or unreadable KRL refuses the range, even when no commit needs a signature. The retained historical keys never
appear as authority in `following --json`'s `signed_by` or `verify --json`'s `signers`. A commit
already in force is not accepted again and stays unchanged after its signer retires.

## A key Porelo does not know

**A key that is clearly a mistake is refused.** That is a key under the wrong section, or one within
two letters of a real key. The error says where it belongs:

    project.yml: 'mail.upstream' is not a setting; it belongs under 'project:'

**A key Porelo no longer reads is refused by name**, with what decides instead, so a file that carries it does not look
as if it still did something: `unread_work_may_leave` is gone, and how closely a message is watched is set by
`mail.rules` and a peer's `mode`.

**Any other unknown key is accepted with a warning** from `task start`, so a file written for a newer
Porelo still runs on an older one. Porelo does not check the names under `credentials` and
`repositories`, or anything under `mail.transports.<scheme>` (the transport checks that).

## machine-signers, beside this file

The public keys a project's machines sign messages with live in `machine-signers`, next to
`project.yml`. **Secrets never travel; public keys travel through the project's repository**, which
every machine verifies: each commit changing that file must be signed by a key in force, and the
tip must descend from the commit in force,
so a removed signer cannot be restored by replaying an older commit.

- `porelo project enroll PROJECT` puts this machine's key up for review in the project's gate
  (`--remove` takes it back). The review marks a signer list as dangerous.
- `porelo gate approve enroll-<machine> --signed` merges it, signed with the approver's own git signing
  key. The machine never holds that key; without one configured it refuses.
- **The trust anchor is a person's step:** the signing key is given once, out of band, to
  `project follow --signed-by`. It is never read from the repository it verifies.

On each machine the same two kinds of key are kept apart by name, in `~/.config/porelo/`:
`machine-signers` holds the message keys of other machines it accepts messages from, and
`project-signers` the keys pinned with `--signed-by`, whose commits a followed project's configuration
is taken from. A person's key is never a machine's, and the other way round.

In `project-signers` only active lines named for the project being checked authorize new protected
changes. Git prints that project name as the principal. Retired lines serve historical verification.
Protected inputs must be regular committed files: a symbolic link could let an unsigned change to
its target alter what the following machine reads.

<a id="deploy-keys"></a>

## Reaching a private repository

Git reaches a repository with the person's own credential, chosen by its address. An `ssh` address
uses their ssh key from the vault: `git.ssh.<host>`, then `git.ssh`, then `ssh.default`. An `https`
address uses their token stored as `git.token.<host>`. See [credentials](credentials.md) for declaring
and storing credentials.

The credential needs read access to the project's own repository, which the machine reads for its
configuration, and write access to work repositories, where it pushes approved work. A token for git
needs no forge administration right. The forge's API uses a token for its own operations, separately
from git.

## The access file

A project file is often public. What would identify the people and machines behind a project - a
prepared host's address, each person's login, card and key fingerprint, and the
conversation server's settings - goes into a second file, `access.yml`, which
`project.yml` names under `access`:

- **`access: {repository: <url>}`** - `access.yml` at the top of a repository of its own. A machine
  fetches it with the person's own credential for that host, as it fetches the project's repository,
  so whoever follows the project needs read access to both.
- **`access: {file: access.yml}`** - `access.yml` beside `project.yml`, in the same commit. Nothing
  else is fetched. **It is then as visible as the project's repository**: if that repository is
  public, so are the host's address and the persons' cards, logins and key fingerprints. Porelo does
  not find out whether a repository is public; the choice is the project's.

The access file is checked against the project's own signers in either place: a commit of the
access repository that changes `access.yml` must be signed by a key the project's chain has in force.
Every such commit in the first-parent range is checked; other commits need no signature. Its tip
must descend from
the access commit in force, or it waits for `--accept-rewrite` as a project commit does. Beside
`project.yml`, it is read from the verified project commit and from nothing else. A second
repository is for confidentiality, never for trust. Once the project file names another place, the
access commit of the old one is no longer in force; the new place's first commit needs no descent.

Until an access file is applied, nothing reaches a transport for the project: no account is made or
admitted, and its tasks' messages wait in their mailboxes, neither sent nor fetched;
`porelo project following` says why. Settings are taken from the commit in force, never from a
working tree.

```yaml
host:                           # optional
  address: host.example.org
  ssh_host_key: "ssh-ed25519 AAAA..."
persons:
  alice:
    fingerprint: "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    role: admin                 # admin or developer
    hosts: [example-host]       # at least one host name
    locked: false               # optional; defaults to false
revoked: []                     # optional list of key fingerprints
transports:
  matrix: {homeserver: "https://host.example.org:8448", room: "#acme:host.example.org"}
```

- **Public keys and addresses only.** No signers - those are the project file's - and nothing
  secret; a value holding a private key is refused.
- **Persons are checked by their cards.** Each `cards/<login>.card` lies beside `access.yml` in the
  same repository and is read from the same verified commit with its own history, never the working
  tree. Its signatures and chain of changes of key must reach the pinned `fingerprint`; the chain
  allows at most 64 changes. When history is needed, at most 64 file revisions are accepted; a
  longer history answers that person as revoked. A login matches `[a-z][a-z0-9-]{0,31}` and is neither `porelo` nor
  prefixed `porelo-`. A host name matches `[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?`.
- **Roles are `admin` or `developer`.** Any other role is refused by name. Either role can certify
  task keys and be kept as a person in the room when neither locked nor revoked. Only admitted
  admins are answered in `admins`.
- **A lock is local to this project.** `locked: true` leaves the person in the verified answer but
  removes them from `admins`, `certifiers` and the room's persons; removing it admits them again.
- **Only verified admitted persons participate.** Manual joins and remembered accounts count only
  while that login is admitted by the verified access commit. Locked, revoked and unnamed logins
  cannot join or reset an account, be heard, receive answers or have read receipts polled. An
  unverified follow admits nobody; a registered project with an access file beside it also admits
  nobody until its persons come from a verified commit. The room removes excluded persons on its
  next membership pass, keeping their records for a later unlock.
- **A revoked key admits nobody.** A missing or invalid card, a different login, an untrusted key or
  a revoked predecessor or a chain through a fingerprint in `revoked` answers that person as revoked, with empty `ssh` and
  `expires`, and names them on stderr. The other persons are still answered. To replace a lost
  key, put the new fingerprint and card in a signed access commit and list the old fingerprint
  under `revoked`. The project's signers remain in `project.yml`.
- **Retired keys are refused anywhere in the file:** `developers`, `laptops`,
  `wireguard_public_key`, `endpoint`, `network`, `ca_sha256` and `vpn_address`. There is no fallback
  to the laptop or VPN schema.
- **No transport runs for the project until its access file is applied.** While it is not readable,
  not verified, waiting for a rewrite, or names nothing for a transport the project uses, a task's
  start, `porelo talk` and the conversation's messages are refused or wait, each naming why.
  `porelo project following` shows the access file's state beside the project's.
- **Moving a project's transport settings into an access file:** write `access.yml` with the
  `transports` block from `project.yml`, commit and sign it where `access` will point, then remove
  `mail.transports` from `project.yml` and add `access` in one signed commit. A project file naming
  `access` that still holds `mail.transports` is refused.

## What is not in here

- **Providers.** They are chosen per run: `porelo task start --provider openrouter`. An agent is named per
  repository (`repositories.<repository>.agent`), and `--agent` chooses another for one run.
- **Secrets.** Everyone who can read the repository reads this file. Credentials live in the vault;
  `credentials:` only names vault entries. See [authentication](credentials.md).
- **Paths.** Where the workspace, state and gate mirror live is Porelo's choice; `porelo doctor` shows it.
