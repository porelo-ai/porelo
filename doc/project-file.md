# The project file

Everything `project.yml` can say, and what each key is for.

**You do not need one to begin.** A task started in a checkout with no project named runs in the
built-in project `default`, which uses Porelo's own settings. Write a project file when you want other
settings.

**Written by hand, it needs no repository and no organization.** Write it to
`$XDG_CONFIG_HOME/porelo/projects/NAME/project.yml`, with `project.name` the same `NAME`, then run
`porelo task start -p NAME`, on a person's machine or as an account on a host. Nothing checks who wrote it, and
that is how an offline project comes to a machine.

**It lives in a git repository, committed: the code's own, or one of its own that names the code repositories
under `repositories`.** A machine reads it at each `task start` of a project its organization names, at a commit that start judged,
never the working tree; Porelo never writes one. See [how it reaches a machine](#how-the-file-reaches-a-machine).

**Only `project.name`, `project.security_class` and `image.from` are required.** Everything else
has a default that suits most projects.

**Every key is one Porelo knows, at the place it belongs.** A file holding anything else is refused whole, each
fault named with its line, as [a key Porelo does not know](#a-key-porelo-does-not-know) says.

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
  #            and host_services is allowed.
  #   guarded  reaches what egress names, pushes to a gate, holds no upstream credential.
  #   online   pushes to a gate that passes its own branch on at once; needs 'upstream'.
  security_class: "guarded"

  # The immediate Git destination for the project's OWN repository: the host's forge or a
  # central or company forge. Required for online, refused for offline, and never seen by
  # the agent: in guarded the gate forwards what a person approved, and in online it
  # forwards the task's own branch at once.
  upstream: "git@github.com:you/myproject.git"

  # Optional. Whether a merge in the project's own repository on the host's forge needs another
  # person's approval: required, the default, or optional, where the author merges their own.
  # Porelo only reads it; the host's forge enforces it.
  review: "required"

  # Optional. The one word the agent of the project's own repository goes by: "Agent Planner" in
  # a conversation and on its commits, with the rules of a repository's nickname below. Without
  # one it goes by "Agent myproject".
  nickname: "Planner"

# Optional. The repositories this project's agents change.
# A task works on exactly one, chosen with 'porelo task start --repository <name>' or by the
# checkout it is started in; there is no default. Each has its own mirror, gate and review branch.
repositories:

  # Names use lower-case letters, digits and hyphens, with single dots between letters or digits;
  # they start and end with a letter or digit and have at most 63 characters.
  backend:
    # Optional. Without an upstream, the work stays on this machine.
    upstream: "git@github.com:you/backend.git"
    # Optional. Where a host takes this repository from, when 'upstream' names the host's forge.
    # Without it a host takes nothing. It ends in the repository 'upstream' ends in.
    central: "git@github.com:acme/backend.git"
    # Optional. As the project's own 'review' above, for this repository.
    review: "required"
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

  # A repository names no image, egress or limits: its image is in its own porelo.yml, and what a
  # task may reach or consume is the project's alone.
  frontend:
    upstream: "git@github.com:you/frontend.git"

image:

  # Required. What the task image is built from. Porelo adds curl, git, ssh, tmux and python3 with
  # apt, apk or dnf; a base without them must ship curl, git, ssh and tmux itself, and builds
  # without python3 if it has neither it nor a package manager.
  from: "ubuntu:24.04"

  # Optional. Variables set in the image, each an ENV line after the 'run' lines. A repository's
  # porelo.yml names an image of its own, taken whole instead of this one.
  env:
    JAVA_HOME: /opt/graalvm

  # Optional. Extra Containerfile lines, run as root at build time: RUN, ARG and ENV only. Keep it
  # small: any change rebuilds the image.
  run: |
    RUN apt-get update && apt-get install -y --no-install-recommends jq \
        && rm -rf /var/lib/apt/lists/*

  # Optional, instead of 'run': the same lines in a file of their own.
  # run_file: "Containerfile.fragment"

# Optional. What the image build itself fetches from.
build:

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
  # outside the task's firewall, so packages a 'run' installs need none; the gate fetches and
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
  subagents: 0     # the most subagents a task's agent runs at the same time; 0 for none

# Optional. Whom the project's tasks may write to. Tasks of one project reach each other by task
# name without being listed, and a project with a conversation has 'people', the people in it,
# without being listed either.
mail:
  peers:
    # A peer's name is letters, digits, '-', '_', '.' and '@', starting with a letter or digit; a task
    # of another project is its whole principal, "task:<project>:<task>", the one form a ':' takes,
    # and only for a task a start can make: not 'people', 'person' or only digits, not beginning
    # 'porelo-', and with 'porelo-<project>-<task>' at most 64 characters.
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
  # transports:
  #   matrix: {}

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

## Starting branch

A task started from a checkout uses that checkout's branch. `porelo task start --from develop` names
another branch. Without a matching checkout, the root-level `base_branch: develop` setting chooses
one; otherwise the repository's default branch is used. The start prints the branch and its source.
A detached checkout needs `--from`. A branch absent from the mirror is refused before a container is
created: publish it to the repository's source and fetch, or choose an available branch.

Review compares with the recorded starting branch, including after a restart. Without a `branch`
scheme, approval defaults to that branch; a declared scheme still chooses the task's forge branch.
An `online` task still forwards to its own forge branch. The starting branch does not change where
Porelo reads trusted image settings: `porelo.yml` still comes from the mirror's default branch.

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
layer and before the `run` lines and agent layers; it does not become an image environment variable.
`limits.cpus: none` leaves the build without these resource flags or the build argument;
`limits.memory: none` removes the memory flag. Open-file limits stay the container runtime's.

`limits.subagents` is how many subagents a task's agent may run at the same time inside the task: 0, the default, for
none, and 1 or more for that many at once, not over the whole task. `porelo task start --subagents N` wins over the
file. The number is fixed when the task is created and kept
with it: a stopped task brought back, after a reboot too, hands its agent the same number again, takes `--subagents`
with that number and refuses another with exit code 64. The agent's adapter turns it into the agent's own switch, and
its definition says what it can enforce: a task asking for more than that, such as any subagent of an agent that can
only switch them off, is refused with exit code 64 before anything is made, naming the agent and what it can enforce,
and the daemon's `CanStart` answers `SUBAGENTS_REFUSED` in the same words. A number below 0 or one that is not whole is
refused too. An agent started attended hears its number only from the files its adapter writes into the container, so a
task whose agent's files cannot be written is not started; a shell or an unattended run starts and says so, since a
shell starts no agent and an unattended run carries the number in its own request. The files are written as soon as the
container is up, before its relay, gate and workspace, so a task that is not started is stopped, kept or not; a file in
the workspace is written once the workspace is checked out, which refuses to overwrite a file its repository tracks. On
a host, the task's forge token is taken last, once the task's key is kept, which is how `porelo task remove` ends the
token; a start whose key cannot be kept takes none and is refused with exit code 65.

## Services on this host

`egress.host_services` names services on the task's own host, such as its forge and registry. Each name uses the
same lower-case host-name syntax as `egress.domains`; a name in both keys is refused when the file is read. Like all
of `egress`, they are the project's, for a task on any of its repositories. `egress.refused` still wins for a
service's name or a parent of it.

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
`image:` and nothing else - `from`, which it must name, `run` or `run_file`, and `env`, as the project file's `image`
does:

```yaml
image:
  from: "ubuntu:24.04@sha256:..."
  run: |
    RUN apt-get update && apt-get install -y --no-install-recommends rpm
  env:
    JAVA_HOME: /opt/graalvm
```

- **Only the image.** Egress, limits, `build.package_sources`, the class and every other rule stay the project's: a
  repository that could name them would widen what its own tasks may do. Any other key is refused by its name, at the
  start, before an image is built. `run` takes `RUN`, `ARG` and `ENV` lines only, here and in the project file: a
  `FROM`, `COPY`, `ADD`, `USER` or `ENTRYPOINT` is refused at its line. A variable Porelo sets in every task (`PATH`
  the task account's local `bin` folder, then `/usr/local/bin:/usr/bin:/bin`, `HOME` `/home/agent`, `LANG` `C.UTF-8`, or any `PORELO_*` set
  per task) is refused too, naming the value and that the line must be removed.
- **The image sees nothing of the repository:** the `run` lines run in Porelo's own build directory. What a
  repository ships for its tasks is installed at run time, or fetched by a `run` line against a hash written into it.
- **Read from reviewed work only:** the repository's mirror at its default branch, never a branch a task pushed
  (`porelo/*`), and a `run_file` only as a plain file of the repository, never a link or a path that leaves it. On a
  start that fetches, the mirror's file is read again after the gate's fetch, and a refusal from the mirror waits for
  the fetched file to decide; a start that does not fetch (`--dry-run`, `--no-gate`) refuses on the mirror's file as of
  its last fetch and says so. When the mirror's `HEAD` names a default branch that does not exist, `task start` refuses
  after the fetch, naming the branch, the mirror's branches and the fix, rather than starting with the project's image;
  Each gate refresh updates the mirror's `HEAD` from the
  upstream's symbolic `HEAD` once the mirror holds that branch.
- **The project file names no image for a repository:** an entry under `repositories` holding `image` is refused,
  naming the repository's own `porelo.yml` as the place for it.
- **A local override** lies in `$XDG_CONFIG_HOME/porelo/projects/<project>/<repository>/.porelo.yml`, in the same format,
  never checked in.
- **What wins, whole:** the local override, then the repository's `porelo.yml`, then the project's `image`. In
  the built-in project `default`, a task started from a checkout reads `.porelo.yml` in that checkout, then
  `porelo.yml` there, then the default image; approved work lands on `porelo/<task>`, never on the checked-out branch,
  so a task cannot change either file under the person. The first of them there is gives the whole image - `from`, its
  lines and its `env` - and nothing of a later one is mixed in, so an image is always one that somebody wrote whole.
- **The start and its dry run name each key's source** (`base image ... (from ...)`, `image lines from ...`,
  `image env NAME from ...`, and `snippet ... lines from ...`). An image a repository's own sources shape is built as
  `porelo/<project>_<repository>` in its own build directory, so two repositories' images never replace or block each
  other; one shaped by the project's `image` alone keeps `porelo/<project>`.
- **A repository's entry in the project file names no egress or limits either:** what a task may reach or consume is
  the project's alone, so a repository that needs other rules is another project.
- **The review ranks a change to `porelo.yml` or `.porelo.yml` as dangerous:** `porelo.yml` chooses the image a task is
  built from, as root and outside the task's firewall (ranked wherever it lies, though only the top-level one is read),
  and `.porelo.yml` is a local override, never to be checked in.

## How the file reaches a machine

**A project of an organization is read at each start, and at nothing else.** `porelo task start` asks the
organization for the project's repository and for the keys of the people in it, fetches that repository, and judges
the newest change to `project.yml`: its commit must be signed by the key of a person with the project's admin role,
valid at that commit's date. A file it names is not judged, as [the decision](decisions.md#a-projects-own-file-is-judged-by-its-newest-change-alone)
says. A file naming another project than the one asked for is refused. What passes is placed on this machine, at the commit judged, never at the tip, and a
push between the judging and the fetch changes nothing. A task's settings stay what its start placed: what runs, a
gate and a listing read that, and a change in the repository waits for the next start.
The daemon's `CanStart` is asked at every change in a dialog, so it reads only for a project not on this machine yet, and
the start has the last word.

**A start through the daemon reads with the daemon's environment, which holds no agent of the person's.** A project in an
organization read by ssh with a key only in the person's own agent answers 69 `cannot-say` there, and goes from the command
line. A token, or a key in the vault or a file, works for both.

| A start | Exit | `reason` | Because |
|---|---|---|---|
| goes no further | 69 | `cannot-say` | the forge, the repository or a current reading of the organization is out of reach; a reading kept from before decides nothing, even with the project's repository in reach |
| goes no further | 65 | `refused-state` | what was read is refused: `words` names the commit and why |
| goes no further | 66 | `not-opened` | the organization files no repository for the project yet |
| goes no further | 78 | `no-homeserver` | a room peer is named, and neither the file nor this host says where its homeserver is |

**A key revoked in the organization counts until the project's next start.** It stays a believed certifier of the
project's messages on a machine until then: nothing reads the organization between starts. On a host, the host's file
answers at once, because root keeps it current.

**A host answers for the organization.** A root-owned file for each project, under
`/etc/porelo-host/organization/projects`, gives the project's keys, its address and where to fetch from, and a start
reads it instead of asking. A person root has locked cannot read it, and is answered as on any other machine. Where
that file's `fetch_from` is the host's own gate, `http://127.0.0.1:<port>/`, the fetch uses a forge token made for this
person by the host's helper, `porelo-host-person forge-token`, for that fetch alone, kept in memory and given to Git in
its environment; no other address asks for it. A locked or revoked person is 65 `refused-state`, and anything else
from the helper is 69 `cannot-say`. The helper takes back this person's other tokens, so a push at that moment can
fail a fetch: it is tried once more with a new token. The homeserver and the room in that file count only when
`project.yml` names none.

**A change at the central forge waits on a host with a forge of its own.** There, a start fetches from the host's
forge, so a commit at the central address reaches it only after `porelo-host-push --pull` and a merged task. A deploy
key can stay listed at the central forge after a project goes: porelo-host's documentation has the detail.

**A name both the organization and this machine use is the organization's.** `porelo project list` shows that one row,
with its `source`. A person the organization does not list under that name gets no row for it.

**A project of this machine alone is this machine's business.** Write its file by hand, or start a task in a checkout
holding one: nothing judges it, and the person who wrote it runs it. `porelo project remove` takes it away again. A
project the organization named goes when the organization no longer names it, at the next start of that name.

## A key Porelo does not know

**Every key Porelo does not know is refused, and the file with it.** Ignored, a key would be no setting at all while
the file looked applied. The refusal names every fault at once, each with the file, its line and the key. Where a key
belongs elsewhere, it says where, and for a key within two letters of one it knows, the key meant:

    project.yml:3: 'project.securty_class' is not a setting; did you mean 'security_class'?
    project.yml:7: 'mail.upstream' is not a setting; it belongs under 'project:'
    project.yml:9: 'image.base_image' is not a setting; write 'from' instead
    project.yml:14: 'repositories.backend.image' is not a setting; a repository's image is in its own porelo.yml, and the project's is 'image' at the top level

**A file written for a later Porelo is refused by an earlier one**, saying the key is not one this Porelo knows and
that it may be older than the file: every person who starts a task of the project updates before their next start.

**A file of the earlier format is refused the same way, and written anew.** Its image keys were renamed (`base_image`
is `from`, `snippet` is `run`, `snippet_file` is `run_file`), `image.package_sources` moved to `build.package_sources`,
and an entry under `repositories` holds no `image`, `egress` or `limits` any more; each such key is refused at its
line with what to write instead.

Porelo does not check the names under `credentials` and `repositories`, or what a transport takes under
`mail.transports.<scheme>` (the transport checks that).

## Reaching a private repository

Git reaches a repository with the person's own credential, chosen by its address. An `ssh` address
uses their ssh key from the vault: `git.ssh.<host>`, then `git.ssh`, then `ssh.default`. An `https`
address uses their token stored as `git.token.<host>`. See [credentials](credentials.md) for declaring
and storing credentials.

The credential needs read access to the project's own repository, which the machine reads for its
configuration, and write access to work repositories, where it pushes approved work. A token for git
needs no forge administration right. The forge's API uses a token for its own operations, separately
from git.

## What is not in here

- **Providers.** They are chosen per run: `porelo task start --provider openrouter`. An agent is named per
  repository (`repositories.<repository>.agent`), and `--agent` chooses another for one run.
- **Secrets.** Everyone who can read the repository reads this file. Credentials live in the vault;
  `credentials:` only names vault entries. See [authentication](credentials.md).
- **Paths.** Where the workspace, state and gate mirror live is Porelo's choice; `porelo doctor` shows it.
