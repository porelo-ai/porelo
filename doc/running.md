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
twice changes nothing the second time**, and says so, so a run abandoned halfway can simply run again. Its last line is
`nothing changed` or `changed: <the parts>`, `would change` under `--show`. With `--json` the one object on standard output is
`{"changed": false, "parts": []}`, and everything else goes to standard error. It installs what is missing and upgrades nothing:
an installed package older than the repository offers is said, and stays until you run your package manager. The parts are
`key`, `source`, `packages`, `porelo`, `filter`, `with`, `account`, `linger`, `subids`, `between-users` and `drop`.

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
another one through ssh, can use it. **Nothing in the CLI needs it running**, except `porelo daemon connect`, which
only forwards to it.

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

Upgrade the CLI, daemon and hooks together. Existing tasks keep their actual container names for their entire
lifetime, including across a daemon restart and a task stop and resume. Their workspaces, agent sessions, mail,
credential routes and MCP children stay with that container. Starting an existing task by its project and logical
task name finds it under its original name.

New tasks in project `porelo` use `porelo-<task>`; in another project they use `porelo-<project>_<task>`. Optional
project and task short names supply the corresponding parts for new tasks. An existing `porelo-porelo-pi` stays
`porelo-porelo-pi` until removed; a new task `pi` in project `porelo` uses `porelo-pi`. There is no renaming command.
Use the actual name that `porelo task list` reports when addressing a container directly. The
[package upgrade acceptance run](build.md#testing-a-package-upgrade-in-one-account) checks old and new tasks together.

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
own, so this is tmux's default; a prefix set in your own `$XDG_CONFIG_HOME/porelo/tmux.conf` (below) takes its place.
Closing the terminal window detaches the same way. Leaving the agent itself, with its own quit command, ends the
session.

**Your own settings.** A file `$XDG_CONFIG_HOME/porelo/tmux.conf` is copied into each task you start, read-only for the agent,
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

The task listing takes review waiting from the gate's waiting list, using the task's recorded security class.
An online push that passed on asks for no review, even while its ref remains in the mirror. Guarded and offline
work keeps its review signal until the gate no longer lists its recorded ref. Rescued work remains in the gate's
review queue; a name ending in `-rescued` alone does not associate it with another task. A clearance question remains
a separate reason a running task needs an answer.

A task in an `online` project pushes to its gate, which passes the task's own branch on to the upstream at once as
`porelo/<task>`, or as the name the project's [`branch` block](project-file.md#branch) makes, and the forge builds it somewhere the task cannot see. When the project file names the forge, Porelo follows every commit the task pushes and hands the build's verdict
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
  `queued`, `running`, `success`, `failure`, `cancelled`, `unknown` - one line per workflow run with its state, and,
  once it is `failure` or final, one line per job with its result and its log's file; and `build-<first 12 of commit>-<n>.log`, the last 64 KiB of a job's log
  as text - less when more than 32 logs share 2 MiB - for each failed job, or for every job with `logs: all`. `<n>` is
  the job's place in that list, so the failed job's log is the file its `job:` line names, not `-1.log`. The file is
  replaced as the verdict changes.
- **A verdict is final only when every workflow is**: one that finishes first, a Shared rules check, is not the
  Build. A final commit is still asked every two minutes, so a workflow started later, by hand too, is followed and
  replaces `build-<commit>.txt`. A run's change of state alone does not replace the file, so it does not wake the
  agent: it is written down for the window, which shows one line per run. A failure while
  another run still goes on keeps the build asked every 20 seconds.
- **A wait ends**: two hours after the last change of a finished commit's verdict, after two hours of a build without
  a verdict, or when the task stops.
  A rate limit, a credential the forge refused, a repository it does not show and a shut vault are each said in the
  verdict's detail, never as a failed build.
- **Porelo keeps no copy of a log.** What it writes down is which builds it delivered - commit, verdict, each job's
  result and its log's name, size and sha256 - and that outlives the task. `porelo task status TASK` shows the builds.
- **The forge is read from the host**, by a build reader Porelo starts beside the task; its API host is not something
  the task can reach. Readers are packages of their own, installed beside Porelo from the same repositories - for
  GitHub, `sudo apt install porelo-adapter-github` or `sudo dnf install porelo-adapter-github`, which puts it at
  `/usr/libexec/porelo/builds/github`. A machine that holds the old `porelo-build-github` takes it off first, with
  `sudo apt purge porelo-build-github` or `sudo dnf remove porelo-build-github`. Writing one is in [writing a build reader](build-readers.md).

On a host, a project without its own `builds` declaration can use the builds named in the host's current
project file. This applies to `guarded` and `online` tasks with an upstream branch. If this account's vault lacks
the configured credential, work can still start without a build reader. The daemon's `CanStart` keeps
`ready: true` and returns one entry in `missing`, with `for: "builds"`, the configured vault key in `credential`,
the forge whose builds it reads in `forge` (as the project's `builds.forge` names it),
`storeCommand: ["porelo", "host", "forge-token"]`, and `afterStore: "ask-again"`. Run that command on the host,
then ask `CanStart` again. The list is empty when the entry is present, off a host, or when no project is chosen;
a shut vault answers `VAULT_LOCKED` with `missing` empty. Show unknown `for` and `afterStore` values as they come.

A task that started without that credential carries the same fields in its optional `buildsCredential`, with
`afterStore: "restart"`. Storing the credential alone does not start its build reader: restart the task.
`buildProblem` keeps the sentence for clients that show text. These fields describe the recorded start and do not
read the vault again during a task listing. Older task records with only a sentence carry no typed credential.
A person's `porelo build status` query on the host gives the same sentence in its unknown verdict's `detail`,
without a structured credential field.

## Asking what a branch or a commit built

Ask the same forge reader without starting a task:

```bash
porelo build status -p myproject -r backend --branch=main
porelo build status -p myproject -r backend --commit="$(git rev-parse HEAD)" --logs=all --json
```

The project must name `builds:` and be `online`. The default log policy is `failure`; `--logs=all` asks for every
job's log and `--logs=none` asks for none. Human output lists runs by trigger and id, with their jobs under them;
jobs without an unambiguous matching run are listed apart. A failed job's named failed tests replace its log,
one line per test, at most 50 lines of 200 code points each. Its HTTPS page is shown only on the selected
repository upstream's host. JSON carries the run and job metadata; a failed job with named tests has an empty `log` field. In a checkout of a project, omit `-p` and `-r` to use the project
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

A task whose project's MCP endpoint allows `Done` can declare its own work complete with
`Done(task: CONTAINER)`. It still sends the room the completion message its instructions ask for.
Porelo records completion on the host, outside the task's mailbox mount. Once the task stops or its
observed activity is `IDLE`, its state is `done`, with no waiting reason. New output reports working
again while keeping the declaration; an ended run keeps its `ENDED` activity. A later start clears
completion. See [task status](commands.md) for the activity values.

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
push to the task's branch, and hands in what it did. Where no build comes while this task runs,
because the push waits at the gate for a person or the project follows no builds, Porelo says so
at once, in the same file:

- `build-<commit>.txt`, `<commit>` the first 12 characters of the commit: lines of
  `key: value` - `commit`, `verdict`, one `run` line per workflow run, one `job` line per
  job, `since`, and `detail` when there is more to say.
- `verdict` is `queued` or `running` while the build goes on, then `success`, `failure` or
  `cancelled`. It is `unknown` when there is no build or Porelo cannot read it, and `detail`
  says why, such as "held at the gate for a person".
- A `run` line names a workflow of the commit and its state when the file was written:
  `queued` or `in_progress` while it goes on, then what became of it. Not every forge names
  its runs.
- A `job` line names the job and what became of it, and the file holding the end of its log
  when one was handed in: `build-<commit>-<n>.log`. A failed job's log always is, when the
  forge has one.
- The file is replaced each time the verdict changes.

**After you push, read `build-<commit>.txt` until its verdict is final, before you call the work
done.** The final verdicts are `success`, `failure`, `cancelled` and `unknown`. On `failure`, read
the log each failed `job` line names, fix the cause, and push again. With `unknown` no build comes
while this task runs, and your work is done once it is pushed: do not wait for one.

## A file the task hands back

A task started with `--result NAME` (the daemon's `Start` takes `result`) finds `/run/porelo/result/` writable, and
its guide ends with a part naming the file:

```
# The file this task hands back

When this task ends, Porelo takes **`NAME`** from `/run/porelo/result/` and hands it to whoever
started the task: when you declare your work done, and when your run ends. Write it there,
whole, as UTF-8 text of at most 1 MiB, readable by others (mode 644). A link, a directory,
anything larger or anything that is not text is not taken. Only this one file is.
```

The name is one file's: no directory part, not `.` or `..`, no control character, at most 255 bytes of UTF-8;
anything else is refused before anything is made (exit 64). A task brought back keeps the name it was made with.

The host takes the file at the task's end: when the task declares `Done`, when a prompt run ends, when a listing
finds its agent ended or its container stopped, and when the task is removed. It moves the file out of the agent's
reach before it looks at it, so a link or a pipe is never followed or opened, and puts it back afterwards. What it
took is kept on this machine, owner-only and outside every task mount, as one of four states: `handed` (with its
text), `missing`, `too-large` (with the size the file's attributes give) or `unreadable` (a link, a directory, a file
the host may not read, or one that is not UTF-8 text or holds a control character other than tab, line feed and
carriage return). A file handed back is kept as it was; another state is taken again at the next end.

What was taken outlives the container and the task's removal, so a person or an interface on another computer
reads it through the daemon's `Result` afterwards. The next start of the task forgets it, and so does the next
creation of its name; `porelo prune` removes one a removed task left.

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
  from: "ubuntu:24.04"
  run: |
    RUN apt-get update && apt-get install -y --no-install-recommends ripgrep jq \
        && rm -rf /var/lib/apt/lists/*
```

or, for anything longer, in a file beside it:

```yaml
image:
  from: "ubuntu:24.04"
  run_file: "tooling.dockerinclude"
```

Use one or the other; Porelo refuses both together. Either holds `RUN`, `ARG` and `ENV` lines only, and
`COPY --from=<image>@sha256:<digest>` from an image pinned by its digest.

- **Your lines run as root**, before the image switches to the `agent` user, because installing packages is
  what they are almost always for.
- **They run after the agent layer**, so the agent's CLI is already there.
- `porelo task start --dry-run` shows what would be built without building it. The generated
  `Containerfile` is kept in `$XDG_DATA_HOME/porelo/build/<project>/` for you to read. It also lists the hosts the
  task may reach, each with who granted it, and a host the agent named with `--agent` or the project refuses as
  "refused on purpose", never as reachable - as the running task enforces it.

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

**A 401 on an anonymous fetch is not the vault's.** A `git clone` or `pip install` from a public forge inside a task
carries no credential of Porelo's, so Porelo's broker never sees it and cannot have refused it. A 401 there comes from
the forge or from the tool's own client. Look at the tool first, for example its HTTP version (`git -c
http.version=HTTP/1.1 clone …`), not at the vault.

## Porelo in your own MCP client

`porelo-mcp stdio` offers Porelo's API as MCP tools to an MCP client you run on this machine, such as Claude Code, over
your own daemon socket. Which methods it offers comes from the daemon (`McpMethods`):

- **Reading methods** are offered by default. They change nothing and hand out no secret, such as `List`, `Tail` or
  `Events`. `CanStart`, `FirstStart` and `Node` are not among them, since each can write something.
- **A method that changes something** is offered only when you name it in `$XDG_CONFIG_HOME/porelo/mcp-writing`.
  - One method per line, by its name alone, such as `Start`. A full name such as `org.fuin.porelo.Tasks1.Start`
    matches nothing.
  - `#` starts a comment.
  - A name the interface does not have is left out.
  - `porelo-mcp` reads the list when it starts.
- **A method that stands for your own judgement**, such as `Approve` or `Decide`, may be on that list. `porelo-mcp` then
  says at its start which of them the client may call.
- **Never offered**, whatever the list says:
  - a method that stops every task (`Panic`);
  - one that removes what cannot be had back (`Clear`, `VaultClear`, `Prune`, `RemoveProject`, `Remove`,
    `DeleteBackup`, `RestoreBackup`, `CredentialRemove`, `CredentialReplace`, `RevokeKeyslot`);
  - one that hands out a secret (`ReadHostVaultShare`, and `ReadHeld`, whose refused message holds what was refused);
  - one that changes where a credential is sent (`WriteDestination`, `CredentialDeclare`).
- **The list is read only from your own regular file**, at most 16 KiB. A link, a pipe or another account's file is
  refused, and the client is then offered no writing method.

A list that lets the client start and stop tasks:

```
# what my own client may do
Start
Stop
```
