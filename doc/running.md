# Running Porelo on a machine

This page is for whoever runs the machine: how a fresh machine becomes a Porelo machine, how the daemon
runs and how you reach it from another computer, and how to add your own tools to a task's image.

## Preparing a machine

A machine you have just rented, reachable over ssh as a user who can become root, becomes a Porelo machine
with one script: `porelo-setup.sh`. It is published beside the packages and versioned with them, as
`porelo-dist-deb/setup/porelo-setup-<version>.sh` and `porelo-setup-latest.sh`. Fetch the one that matches
what you want to install.

It is the one part of Porelo that is a file and not a command. A machine being prepared has no packages, no
daemon and no account for the daemon to run as yet.

**The download is checked only by HTTPS.** Its `.sha256` comes from the same server, so it proves nothing
against that server. Where that matters, as on a host a program prepares, take the script from the `porelo`
package instead, which apt checks against the repository's key:

1. add the key by its fingerprint and the source line, as [Getting started](getting-started.md#debian-and-ubuntu)
   does;
2. `sudo apt install porelo`;
3. `sudo /usr/share/porelo/setup/porelo-setup.sh --user agents`. It finds the key, the source line and Porelo in
   place, says each is already so, and does the rest.

On Fedora the packages are not signed yet, so `dnf` checks the script no better than the download.

### What it does, as root

```
sh porelo-setup-latest.sh [--user agents] [--distribution releases] [--with <package>]... [--show] [--list [--json]]
```

- **Keeps the distribution the installed Porelo came from**, `snapshots` for a snapshot, unless
  `--distribution` names one, so running it again never moves a machine to another. Without Porelo installed
  it takes `releases`. It says which, and why.

- **Configures Porelo's package repository** and installs Porelo, the message filter, and every package
  `--with` names. `--list` says what there is to choose from: agents, transports and homeservers.
  `--list --json` says it as one JSON object, for a program.
- **Creates the account tasks run as**, `agents` by default (`--user` names another), with **linger**, so
  its user services survive a logout, and with **subuid and subgid ranges**, without which rootless podman
  cannot map a container's users. These are where a hand-made account usually goes wrong.
- **Checks what no package can promise**: that the repository really offers Porelo (an already installed
  package would hide a broken source), and that dnsmasq can fill the firewall's allow set.
- **`--show` prints every command and runs none of them**, so you see what root will do before you give it
  root.

It reads `/etc/os-release` and **refuses an operating system it does not know** instead of trying its best:
a half-prepared machine is worse than an unprepared one, because the next step believes it. **Running it
twice changes nothing the second time**, and says so, so a run abandoned halfway can simply run again.

| Exit | Meaning |
|---|---|
| 0 | the machine is prepared, or already was |
| 2 | the arguments do not make sense |
| 3 | this operating system is not one Porelo knows how to prepare |
| 4 | not running as root |
| 5 | a check failed, and the machine is not usable as it stands |

The acceptance tests prepare their rented machines with this script and nothing else, so the path you
take is the path that is tested.

### Then, as each user: `porelo setup`

**The script does not start the account's daemon.** A root script starting another user's service would
be wrong about the session it runs in. What is left belongs to the account, and is one command in its own
session, at a terminal (over ssh: `ssh -t`):

```
porelo setup
```

It registers podman's hooks, starts the daemon and creates the vault (see
[the command reference](commands.md#setup)). The script's exit code says its steps ran; only
`porelo doctor` can say the machine works.

## The daemon

`porelod` serves what the CLI does over an owner-only unix socket, so an interface on this machine, or on
another one through ssh, can use it. **Nothing in the CLI needs it running.**

```
porelod --help        # says what it is
porelod --version     # says which build
porelod               # serves, until it is stopped
```

It takes no other options, and an option it does not know is refused, not ignored.

### The systemd user unit

`porelod.service` is a **user** unit, on purpose. **A Porelo node is an OS user, not a machine**: the vault,
the containers in rootless podman's per-user storage, the hooks podman reads and the socket under
`$XDG_RUNTIME_DIR` all belong to one user. Two people with their own logins are two nodes, and each runs
their own daemon.

```
porelo setup                      # enables and starts it, unless it already runs
systemctl --user status porelod
```

`porelo setup` runs `systemctl --user enable --now porelod`. The package installs the unit but enables
nothing: starting a daemon is each account's own decision.

**A daemon that must outlive your session needs lingering**, or systemd stops everything you run when you
log out. `porelo-setup.sh` turns it on for the account it creates; for any other account:

```
loginctl enable-linger "$USER"
```

The unit restarts the daemon when it fails. It deliberately leaves out three things:

- **No `NoNewPrivileges=yes`.** The first rootless podman call after a boot needs the setuid `newuidmap`
  and `newgidmap`, which no-new-privileges forbids. With it, the daemon works only while something else
  has already run podman, and reports podman missing on a fresh boot (`newuidmap: write to uid_map failed:
  Operation not permitted`).
- **No `RuntimeDirectory=porelo`.** The same directory holds each task's state, and systemd removes what
  it created when the unit stops. A stopped daemon would take running tasks' state with it.
- **No `porelod.socket`, so no socket activation.** The first connection does not start the daemon by
  itself. Start it with `systemctl --user start porelod`, over ssh if need be.

### After an update

**Nothing to do.** The package starts nothing when it installs, so the daemon restarts itself: within a few
seconds of an update it notices that its binary was replaced, and asks the account's own systemd to reload the unit
definitions and restart it. Every account that runs `porelod` as its unit does this by itself. Running tasks keep
running; they take the new version when they are next started.

`porelo doctor` says when the daemon runs another version than the command - a daemon started by hand, which nothing
restarts, says so once in its own output. By hand it is:

```
systemctl --user daemon-reload && systemctl --user restart porelod
```

Without `daemon-reload` first, systemd warns that the unit file changed on disk, and runs the new binary under the
old definition.

### A task outlives the daemon

Stopping a unit stops every process in its control group. So everything a task leaves running - the
container's monitor (`conmon`), its network, its resolver, its helpers - is started in a scope of its own
under `porelo.slice`, by the daemon and by the CLI alike. Stopping, restarting or losing the daemon does not
end a task. `systemctl --user status porelo.slice` shows one scope per helper and one for the container's
monitor, each named after its task. A scope ends by itself once nothing in it runs, so removing a task
leaves none behind. On a machine without a user manager the processes are started as they are.

### The socket

The daemon binds `$XDG_RUNTIME_DIR/porelo/porelod.sock`, readable only by its owner.

- **A daemon that is stopped or signalled removes it.** Only `SIGKILL` can leave one behind.
- **A second daemon does not take the socket from the first.** Starting one while another listens is
  refused, naming the path.
- **A socket left by a `SIGKILL` or a reboot is taken over** by the next start, which removes it only after
  checking that nothing answers on it.

### What the daemon shows of itself

The daemon reads the vault, so it cannot be core-dumped or read by a debugger, not even by its own user. Its entries
under `/proc/<pid>` belong to root, so `readlink /proc/<pid>/exe` and `ss -p` show you nothing of it. Ask it
instead, or ask the kernel by the unit's control group:

```
printf '%s\0' '{"method":"org.fuin.porelo.Tasks1.Process"}' | porelo daemon connect
ss -ltun --cgroup | grep "cgroup:$(systemctl --user show -p ControlGroup --value porelod)"
```

The first answers its pid and the executable it runs. The second lists any network port the unit listens on, and
prints nothing.

### A daemon that answers nothing

If `porelod` accepts a connection but answers no call, have it print where its threads are before restarting it:

```
systemctl --user kill -s QUIT porelod
journalctl --user -u porelod
```

The dump goes to the daemon's journal, names its background work (`porelo-follow`, `porelo-upstream`,
`porelo-messages`), and the daemon keeps running under the same pid. Keep that dump with the report of the hang.

### Reaching it from another computer

Nothing listens on the network. You reach the daemon through ssh as the account it runs for, in one of two
ways:

- **Through the ssh session itself.** `porelo daemon connect` joins its standard input and output to the
  socket, so `ssh HOST porelo daemon connect` (or the same as an ssh `ProxyCommand`) gives a client the
  daemon's stream. No socket file is made on your side, and the connection lasts as long as the ssh
  command. It is one connection, so each stream a client keeps open, such as a watch and two log tails, is an
  ssh session of its own. If no daemon runs, it says so on standard error and exits with 69.
- **Through a forwarded socket.** `ssh -L` can forward a local socket file to the remote
  `$XDG_RUNTIME_DIR/porelo/porelod.sock`, and a client then opens the local file, as many times as it likes over
  the one forward. The socket file and the forward's lifetime are yours to manage. Where SELinux enforces, the
  forward needs this package's policy module loaded, and `porelo doctor` names an older one.

If the daemon is not running, `ssh HOST systemctl --user start porelod` starts it under systemd, supervised
and restarted on failure, instead of leaving an unsupervised process behind an ssh command. For it to keep
running after you disconnect, the account needs lingering.

## The terminal in a task

An agent in a task runs in a tmux session inside its container: that is what lets you close the window and come back
to it. Porelo sets the session up to feel like the terminal outside. Esc arrives within 10 ms, and Shift+Enter, Ctrl and Alt
keys arrive. Focus, truecolour, the clipboard (OSC 52), links and the window title pass. The mouse wheel goes to an
agent that asked for the mouse. Measured with tmux 3.4, the version of the default base image, these differences remain:

- **Scrolling back.** The session's history is tmux's, not your terminal's: your terminal's own scrollback holds
  nothing of it. The wheel and Shift+PageUp scroll tmux's history wherever the agent writes into it, as Claude Code
  does, and Shift+PageDown scrolls back down. While scrolled back, keys go to tmux rather than to the agent, until
  you scroll to the bottom or press `q`.
  An agent that draws its own screen, as Oh My Pi does, gets the wheel and PageUp itself and scrolls its own view.
- **Selecting text with the mouse** selects in tmux and copies to your clipboard. Your terminal's own selection is
  reached with Shift held, in most terminals.
- **Shift+Enter in xterm's form** arrives at the agent in the CSI u form.
- **Images** reach the terminal only from a program that wraps them for tmux; most agents do not.

**Leaving without ending it.** Press Ctrl+b, then d: tmux detaches, the session and the agent go on, and
`porelo task attach TASK` or `porelo task start TASK` at a terminal brings you back. Porelo sets no prefix of its
own, so this is tmux's default; a prefix set in your own `~/.config/porelo/tmux.conf` (below) takes its place.
Closing the terminal window detaches the same way. Leaving the agent itself, with its own quit command, ends the
session.

**Your own settings.** A file `~/.config/porelo/tmux.conf` is copied into each task you start, read-only for the agent,
and read after Porelo's settings: key bindings, the prefix, the mouse and colours, set once for all your tasks with no
image rebuilt. It takes effect for tasks started after you change it. Two things are set again after it, because
Porelo depends on them: how much a session remembers (`history-limit`), which `porelo task attach` states, and the
terminal the agent is told it has (`default-terminal`).

## Handing a file to a running task

A task's work comes from its repository, but some things a task needs never belong in git: a build log, a
screenshot, a specification, a data extract. `porelo task give TASK FILE` hands such a file to a running task, and
the agent finds it in `/porelo/files`.

- **`/porelo/files` holds nothing else**, in every task, whatever the agent and the image, from the moment the task
  starts. So a prompt can say "read what arrives in `/porelo/files`" before anything has arrived, and an agent can be
  told to read all of it.
- **A file appears whole or not at all**, owned by root and readable by the agent, which cannot change or remove
  it. It is never in `/workspace`, so nothing the agent commits takes it out through the gate.
- **The same name replaces the file**, and `porelo task take-back TASK NAME` removes it.
- **It lives as long as the container**: a stop and a start keep it, since the container keeps its files, and
  removing the task removes it. A task created again under the same name starts with an empty `/porelo/files`.
- **The largest file a task takes** is `limits.hand_in` in the [project file](project-file.md), 64 MiB unless it
  says otherwise, fixed when the task starts like its other limits.
- **Only into a task, never out.** Nothing here takes a file out of a task; work leaves through the gate.
- **Every hand-in is written down**: who, when, which task, the name, the size and the sha256, never the content.
  `porelo task files TASK` shows it, also after the task is gone, and `porelo task status TASK` lists what the task
  holds now. It records what Porelo did: a file copied into a container with `podman cp` is not in it.
- **A base image that already has `/porelo` is refused** when the task's image is built, naming the image: Porelo
  keeps that directory for itself, so that everything in it arrived the same way.

An interface on another computer hands a file over the daemon's socket in parts, so the same works from wherever
the socket reaches.

## Being told what the build of a push did

A task in an `online` project pushes to its gate, which passes the task's own branch on to the upstream at once as
`porelo/<task>`, and the forge builds it somewhere the task cannot see. When the project file names the forge, Porelo follows every commit the task pushes and hands the build's verdict
into the task as it changes - the task asks for nothing and holds no forge credential:

```yaml
builds:
  forge: github               # the build reader to ask, as its package installs it
  credential: github-actions  # the vault entry it reads the forge with; it never enters a task
  # api: https://ghe.example/api/v3   # a forge of your own; unset is the forge's public API
  # logs: all                 # every job's log once the build is finished; default: each failed job's
```

- **Only in `online`**: in `guarded` the forge builds nothing until a person approves the work at the gate, and in
  `offline` there is no build. A project of either class with a `builds` section is refused when it is read.
- **What arrives in `/porelo/files`**, with `by` "porelo": `build-<first 12 of commit>.txt`, the verdict - one of
  `queued`, `running`, `success`, `failure`, `cancelled`, `unknown` - and, once it is `failure` or final, one line per
  job with its result and its log's file; and `build-<first 12 of commit>-<n>.log`, the last 64 KiB of a job's log
  as text - less when more than 32 logs share 2 MiB - for each failed job, or for every job with `logs: all`. `<n>` is
  the job's place in that list, so the failed job's log is the file its `job:` line names, not `-1.log`. The file is
  replaced as the verdict changes.
- **A wait ends**: at a verdict that will not change, after two hours of a commit's build, or when the task stops.
  A rate limit, a credential the forge refused, a repository it does not show and a shut vault are each said in the
  verdict's detail, never as a failed build.
- **Porelo keeps no copy of a log.** What it writes down is which builds it delivered - commit, verdict, each job's
  result and its log's name, size and sha256 - and that outlives the task. `porelo task status TASK` shows the builds.
- **The forge is read from the host**, by a build reader Porelo starts beside the task; its API host is not something
  the task can reach. Readers are packages of their own, installed beside Porelo from the same repositories - for
  GitHub, `sudo apt install porelo-build-github` or `sudo dnf install porelo-build-github`, which puts it at
  `/usr/libexec/porelo/builds/github`. Writing one is in [writing a build reader](build-readers.md).

## Asking what a branch or a commit built

Ask the same forge reader without starting a task:

```bash
porelo build status -p myproject -r backend --branch=main
porelo build status -p myproject -r backend --commit="$(git rev-parse HEAD)" --logs=all --json
```

The project must name `builds:` and be `online`. The default log policy is `failure`; `--logs=all` asks for every
job's log and `--logs=none` asks for none. In a checkout of a followed project, omit `-p` and `-r` to use the project
and repository its origin matches. A query never adds a checkout to `default`. The token stays on the host, in the
vault and the reader's socket calls; human output escapes terminal controls in the forge's text.

A failed or cancelled build exits 1; a queued, running, successful or unknown build exits 0. A refused query exits
separately and names the next action; the [command reference](commands.md#build) lists every code and the JSON
fields.

## Values a task is started with

A project can declare values a task of it is started with, under `parameters` in the
[project file](project-file.md): the kind of work, a ticket, a label. Each is handed in at the start:

- `--set type=bugfix` gives the value itself.
- `--set ticket` takes the value of the declaration's variable (`env`, `PORELO_PARAM_TICKET` by default) from your
  shell.
- `--set-file values.yml` reads `NAME: VALUE` lines from a file; repeatable, the last file winning.
- `--set-env` takes every declared variable your shell has set.

Nothing is taken from your shell without `--set NAME` or `--set-env`, so a variable left over from yesterday does not
name today's task. Given more than once, `--set` wins over a file, a file over your shell, your shell over the
declaration's `default`.

- **Checked before anything is made.** A value outside its declaration, a name the project does not declare, or a
  required value missing refuses the start, naming what is allowed. The daemon's `CanStart` answers the same,
  `PARAMETER_REFUSED`.
- **Fixed for the task's life.** The task holds its values in its environment and its record; a later change to the
  project file does not reach it, and starting a stopped task again with `--set` is refused.
- **Shown with where each came from**: `porelo task status TASK` says `type=bugfix (command line)`, and the daemon's
  `Task` carries the same.
- **Seen by the agent, never changed by it**, in the declared variable and in its guide, below.
- **Not secrets.** They stand in the task's record, its listing and its environment. A credential belongs in the
  vault.

## What a task's agent is told

An agent learns what Porelo gives it in its task from one guide, which it takes into its standing instructions at
every start when its definition declares how (`instructions`): Porelo writes it to `/run/porelo/guide/README.md`,
mounted into the task and read only there. Where the task has a mailbox, the mailbox's text from
[messages between tasks](messages.md#what-the-agent-is-told) follows it in the same file. A task made before
this version keeps its mailbox's guide until it is made again.

When a file arrives in `/porelo/files` - handed in by a person, or a build's verdict and its logs - and the agent waits
at its prompt, Porelo types one line naming it, as it does for a message: never while a question to a person is
open, and never for an agent whose definition says nothing about what its screen shows at rest. A file that arrived
while the agent worked is announced once it rests; a verdict that changes is announced again.

The text is the same for every task of one Porelo version, word for word; a task started with
[values](#values-a-task-is-started-with) has a part naming each, and the variable it is in, after all of it:

## Porelo in this task

This task runs in Porelo. What follows is what Porelo gives you here besides your workspace:
files handed to you, the builds of what you push, and a mailbox where the task has one.

**When your work is done, commit it and push it with `git push porelo`, and name no branch.** The push
goes to this task's own place, `$PORELO_TASK_REF`, at the gate on this machine: where a person reviews
it, or, in an online project, from where it goes on at once to this task's own branch at the forge -
never onto a branch you name. A person takes back only what you pushed; what is only in your
workspace stays in this task.

**When you are told the repository you work from moved on, `git fetch porelo` brings it.**
Rebase or merge your work onto it before you push again.

## Files handed to you

- **`/porelo/files` holds the files handed to this task**, by a person or by Porelo, and nothing
  else. A file appears there whole, never half written.
- You can read them but not change or remove them. Copy one into your workspace to change it.
- A file handed in again under the same name replaces the one before.
- **Look there when you start, and whenever you come to rest.** When a file arrives while you
  wait at your prompt, a line may appear there naming it; not every agent is given one.

### The builds of what you push

Where the project follows its builds, Porelo watches what the forge builds for each commit you
push to the task's branch, and hands in what it did:

- `build-<commit>.txt`, `<commit>` the first 12 characters of the commit: lines of
  `key: value` - `commit`, `verdict`, one `job` line per job, `since`, and `detail` when
  there is more to say.
- `verdict` is `queued` or `running` while the build goes on, then `success`, `failure` or
  `cancelled`. It is `unknown` when there is no build or Porelo cannot read it, and `detail`
  says why.
- A `job` line names the job and what became of it, and the file holding the end of its log
  when one was handed in: `build-<commit>-<n>.log`. A failed job's log always is, when the
  forge has one.
- The file is replaced each time the verdict changes.

**After you push, read `build-<commit>.txt` until its verdict is final, before you call the work
done.** On `failure`, read the log each failed `job` line names, fix the cause, and push again.

## Adding your own tooling to a task

Two things are called "the agent", and they live in different places:

- **The agent package** (for example `porelo-agent-claude`) installs **on the host**, under
  `/usr/libexec/porelo/agents/`, or anywhere with one description file in `/usr/share/porelo/agents.d/` that names it. It is Porelo's adapter: it knows the agent's flags, where it keeps its
  credentials and how to read its output.
- **The agent's CLI** (for example `claude`) runs **inside the task image**.

How the CLI gets into the image is up to the agent package. Claude Code's package (about 6 MB) carries a
pinned URL and SHA-256, which Porelo puts into the generated `Containerfile`; podman's layer cache then
downloads the CLI (about 320 MB) once per pinned version. Pi's package (about 70 MB) carries the tool
itself, verified once where the package is built against a lockfile that pins every dependency by hash, so
the image build downloads nothing for it.

A task image is built in three layers, in this order:

1. **base**: the distribution image the project names, plus an unprivileged `agent` user, `/workspace`,
   and `curl` with CA certificates;
2. **agent**: the pinned, verified download the agent package asked for, or a copy of what it carries;
3. **project**: your own lines.

`porelo task prepare -p PROJECT` builds a project's task image without starting a task, and
`--rebuild` says how much of it to discard:

| `--rebuild` | What it keeps |
|---|---|
| `CACHED` (the default) | whatever podman's cache still considers valid, exactly as a task start does |
| `AGENT` | the base image and its packages; the agent's tooling is rebuilt |
| `EVERYTHING` | nothing: the base image's packages are downloaded again |

`CACHED` still runs the build and lets the cache decide layer by layer, so preparing and starting
never disagree about what the image should contain. An unknown depth is refused rather than taken as
the cheapest. `--agent` names whose tooling to install; with none installed, the image is prepared
without an agent, for working inside the container by hand. The build's output is shown line by line
while it runs, and `--dry-run` says what it would do.

Layer 2 needs network access **while the image is built**, to the vendor's download host, for an agent that
downloads its CLI. The egress firewall governs the running task, not the build. An air-gapped machine
needs a mirror for that URL, or an agent whose package carries the tool.

Your tooling goes in layer 3, either inline in `project.yml`:

```yaml
image:
  base_image: "ubuntu:24.04"
  snippet: |
    RUN apt-get update && apt-get install -y --no-install-recommends ripgrep jq \
        && rm -rf /var/lib/apt/lists/*
```

or, for anything longer, in a file beside it:

```yaml
image:
  base_image: "ubuntu:24.04"
  snippet_file: "tooling.dockerinclude"
```

Use one or the other; Porelo refuses both together.

- **Your lines run as root**, before the image switches to the `agent` user, because installing packages is
  what they are almost always for.
- **They run after the agent layer**, so the agent's CLI is already there.
- `porelo task start --dry-run` shows what would be built without building it. The generated
  `Containerfile` is kept in `$XDG_DATA_HOME/porelo/build/<project>/` for you to read.

Nothing you add escapes the rest of the model: the container still starts with no capabilities and with
`NoNewPrivs`, and installing something does not widen what the task may reach.

**Build time and run time differ.** Your lines run while the image is built, which the firewall does not
govern, so they can fetch from anywhere. While the task runs, its resolver answers NXDOMAIN for every name
the project has not declared, so a tool that reaches out then fails to resolve. Two remedies, and you may
need both:

- fetch what the tool needs here, in the build, so the running task needs no network for it;
- if it really must reach out while the agent works (a package registry is the usual case), name the host
  in the project's `egress` section, where the grant is reviewed like any other change. See
  [what a task can reach](reach.md) and [the project file](project-file.md).
