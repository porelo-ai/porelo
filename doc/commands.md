# Porelo Commands

Every command, to find its name. `porelo <command> --help` has every option.
[For a given job](#for-a-given-job) starts from what you want to do instead; the keys of a project
file are in [the project file](project-file.md).

| Group | What it does |
|---|---|
| [`task`](#task) | Runs and inspects agent tasks. |
| [`shield`](#shield) | Inspects and changes what a task may reach. |
| [`vault`](#vault) | Manages stored credentials. |
| [`credentials`](#credentials) | Records which credential each destination is connected to with. |
| [`gate`](#gate) | Reviews and forwards what an agent pushed. |
| [`talk`](#talk) | Shows and moves the messages a task exchanges with other tasks. |
| [`agents`](#agents) | Lists the agents installed on this machine. |
| [`providers`](#providers) | Lists the model providers, and what the vault holds for each. |
| [`project`](#project) | Follows project repositories, and lists the projects here. |
| [`setup`](#setup) | Makes this account ready: OCI hooks, daemon, vault. |
| [`doctor`](#doctor) | Checks whether this machine can run a task. |
| [`prune`](#prune) | Shows what nothing owns any more; removes it with `--yes`. |
| [`panic`](#panic) | Stops every task and helper, removing nothing. |
| [`daemon`](#daemon) | Reaches the daemon on this machine. |
| [`completion`](#completion) | Prints the TAB completion script for a shell. |

## For a given job

TASK is a name as `porelo task list` shows it; leave it out and the command lists the names it would
take. PROJECT is a name from `porelo project list`, never a path.

**Set up this account.** `porelo setup`, then `porelo doctor`; `porelo agents` lists the installed
agents. Preparing the machine itself is in [running Porelo](running.md#preparing-a-machine).

**Get a credential in.** `porelo vault login claude` runs the agent's own login in a throwaway
container and needs nothing installed. `porelo vault import claude` copies what an install already
signed in here holds; a second login would replace what that install uses. `vault login` on an agent already
signed in here offers that at a terminal - import (the default), log in again, or cancel - and refuses with both
commands named without one. The other way round, `vault import` with nothing to import offers the agent's own
login, and `vault unlock` with no vault offers `vault init`, each run as that command on this terminal. At a
task's start, a credential stored under the agent's name is offered to be moved to its provider's without being
entered again, and a copy older than the agent's is offered to be refreshed. `porelo vault put NAME`
stores a value you already have; `porelo providers` says which names to use.

**Open and close the vault.** A command that needs a shut vault asks for the passphrase at the
terminal and carries on: for that command alone, or, for `task start`, `run` and `resume`, the way
`porelo vault unlock` keeps it open, since the task needs it afterwards. Without a terminal it refuses
and names `porelo vault unlock`. `porelo vault unlock --for 30m` caches the passphrase for a while (there
is no default limit), `porelo vault lock` drops it, `porelo vault passphrase` changes it. To start
over, `porelo vault clear --yes` removes it, and `porelo vault init` makes a new one.

**Start work.** In a checkout, `porelo task start` works in the project that names its repository,
else in `default`, and names the task after the repository. In `default` the checkout is the source,
both ways: its committed history goes into the task, local commits included, and the approved work
comes back into it; its remote is yours to pull from and push to, and Porelo never reaches it. A project
that names the repository takes it from its remote and sends approved work there instead. Elsewhere, name both:
`porelo task start -p PROJECT -r REPOSITORY`. The same verb creates a task, brings back a stopped one
with its workspace, and refuses a running one. Leaving the session keeps the container; `--rm` does
not. For another project's settings, `porelo project follow NAME URL --signed-by KEY`.

**Find and re-enter a task.** `porelo task list`, `porelo task status TASK`, `porelo task attach TASK`,
`porelo task logs TASK gate.log -f` to follow one log.

**When the source moves on.** `porelo task refresh TASK` brings what the checkout (or the remote) gained
into the task's gate and tells its agent, which brings it into its workspace with `git fetch porelo`.
Starting a stopped task does the same on its own.

**End a task.** `porelo task stop TASK` keeps everything. `porelo task remove TASK` destroys the
workspace, which lives in the container; `--rescue` first pushes unpushed work to the gate.
`porelo panic` stops everything and removes nothing.

**Bring a task's work back into your checkout.** In the checkout the task was started from,
`porelo approve` shows what waits at the gate and, after yes, puts it on the branch `porelo/TASK` there -
never on the branch you have checked out, and never to a remote. With several tasks it asks which, or
takes `porelo approve TASK`; `--yes` answers for a script.

**Review what an agent pushed.** Inside a task, `git push` goes to the gate. Outside:
`porelo gate pending`, then `porelo gate review`, `porelo gate checkout`, `porelo gate approve` or
`porelo gate reject`, each with `-p PROJECT NAME`.

**Change what a task may reach.** `porelo shield egress --add-set maven` or
`--add-domain example.com --dry-run`; `porelo shield sets` lists the sets. While it runs,
`porelo task clearance TASK prompt` asks about each blocked connection.

| Symptom | Try |
|---|---|
| a command refuses and names no task | `porelo task list`, or leave the name out |
| the agent cannot authenticate | `porelo vault list`, then `porelo doctor` |
| is there work in there I would lose? | `porelo task status TASK`, while it still runs |
| a task starts and then does nothing | `porelo task logs TASK`: something it needs is probably blocked |
| a push never arrives | `porelo gate pending`: it waits for review |
| "hooks are not registered" | `porelo setup`; starting a task does it too |
| everything at once | `porelo panic`, which removes nothing |

## task

| Command | What it does |
|---|---|
| `porelo task start [TASK]` | Creates a task, or brings back a stopped one with its workspace. |
| `porelo task list` | Lists the tasks, with how long each has been in its state. |
| `porelo task status TASK [--json]` | Everything known about one task, including unpushed work; `--json` answers a program, `absent` for none. |
| `porelo task logs TASK [LOG]` | Shows what the task's helpers on this machine wrote. |
| `porelo task logs TASK --zip [FILE] [--with-agent-output]` | Keeps the task's logs, its status, files and talk log in one archive readable by you only, with `SHA256SUMS`. |
| `porelo task attach TASK` | Opens a running task's session. Ctrl+b then d leaves it without ending it. |
| `porelo task stop TASK` | Stops a task and its helpers. Container and workspace stay. |
| `porelo task remove TASK` | Removes a stopped task: container, state and workspace. |
| `porelo task label TASK [CAPTION]` | Gives a task a caption. Not a rename. |
| `porelo task give TASK FILE [--as NAME]` | Hands a file on this machine to a running task, read-only in its `/porelo/files`. |
| `porelo task take-back TASK NAME` | Takes a handed-in file out of a running task's `/porelo/files` again. |
| `porelo task files TASK` | Every file handed to a task, also after it is gone: who, when, size and sha256. |
| `porelo task watch-builds TASK ...` | Run by an online task's launch, not by hand: follows what the task pushes and hands each build's verdict into `/porelo/files`. |
| `porelo task prepare -p PROJECT [-r REPOSITORY]` | Builds the project's task image without starting a task; with `-r`, the image a task on that repository gets, chosen as a start chooses it. |
| `porelo task clearance TASK MODE` | Sets what happens to a blocked connection: `prompt`, `allow`, `deny` or `off`. |

**`task start`**
- `-p PROJECT`: without it, the project that names the checkout you stand in, else `default`.
- `-r REPOSITORY`: which of the project's repositories to work on (see [names](#completion)).
- Without a name, the task is named after its repository, or the next free name (`my-first-2`).
- By default you get the agent's session. `--attach shell` gives a shell, `-P`/`--prompt` runs the
  agent unattended, `--detach` hands over nothing, `--now` returns before the image is built.
- `--rm` removes the container when you leave. By default it is kept.
- `--set NAME=VALUE`, `--set NAME`, `--set-file FILE` and `--set-env` hand in the
  [values the project declares](running.md#values-a-task-is-started-with), checked before anything is made.
- **A running task is gone into**, as `attach` does: at a terminal, `start` on it starts its agent again where the
  session is gone, continuing its conversation. An option that takes effect only when a task is created or brought
  up (`--prompt`, `--model`, `--agent`, `--attach`, `--detach`, `--rm` and the like) is refused, naming each, since it
  would be dropped: stop the task first. Without a terminal, or with `--no-input`, `start` says the task is running
  and exits 65, so a script never ends up in a session.
- **What is missing is offered, not only named.** At a terminal a start asks what it would otherwise tell you
  to type: at a host this machine never met, it shows the keys the host offers and asks whether to trust its
  key (no by default, compare it with what the host's owner publishes). Before anything is made, the start
  reads the repository's upstream with the credential the gate will use. If the host refuses it, has no such
  repository or cannot be reached, the start says which, with the command that fixes it, and makes nothing. Where
  nothing is stored for a refused ssh host, it asks for the path of a key to store, and reads the upstream again.
  With a mirror already there, it says why and starts from what the mirror holds. A credential the agent lacks is
  offered the same way: the command the refusal would name - `vault authorize`, `vault login` or `vault put`,
  which asks a key without echo - runs on this terminal, and the start goes on once the credential is there. A
  start with nothing stored for its agent says that the credential is missing, and never names a stored kind that
  is not there; a kind asked for with `--credential-type` that the agent's provider cannot use is refused before
  the start, in words that do not say "stored".
  With several agents installed and none named, it lists them and asks which, by number; without a terminal it
  asks for `--agent` as before. Without `-r`, it lists the repositories the project works in and asks which,
  before the task is named after it. `--no-input` asks nothing and refuses as without a terminal; `--yes`
  takes a harmless remedy without asking and never a trust decision.
- After a reboot, `task list` shows which tasks went down and `porelo task start --restarted` brings
  them all back. Nothing comes back by itself. A task started with a prompt and left to run is not among
  them: it stays stopped, listed as interrupted by the restart, with its session kept, and its next
  `porelo task start` with a prompt continues the conversation. The first start after a reboot needs
  the vault open, even for a task with no credential of its own, because the task's gate token is kept
  there; at a terminal it asks for the passphrase.
- A task whose agent names its sessions continues the conversation it was having, after a stop or a
  reboot alike, and the start says whether it continued or began fresh. An agent that names no
  session, or a continuation that failed, begins fresh and says so; nothing runs twice unasked. The
  `--model` a task was started with is kept for `task attach` and later starts, and starting a stopped
  task with another `--model` is refused, naming what it was started with.

A start names each step as it takes it. The CLI adds `step`, `waiting` and `failed` lines to its
usual output; a failure includes the dependency's last error line when available. With `more` and
`steps: true`, the daemon streams the same steps; streamed calls without `steps` receive lines only.
A nonstreamed final reply returns every step as `steps`.
For a new task, `phase` appears from its container on; the conversation's wait comes before it and
is in `Start`'s stream only.
A waiting phase includes ` - waiting on <waitingOn>` after the step's text, and the phase clears
when the start ends.
Typed error replies (`EarlierWorkWaits`, `UpstreamUnreadable`) carry no `steps`.

| Step | What it does |
|---|---|
| `SOURCE` | Reading the project and its source |
| `CHECKS` | Checking the machine, the egress and the upstream |
| `CREDENTIALS` | Opening the vault and the task's credentials |
| `WORKSPACE` | Bringing the gate's mirror up to its upstream, when a gate is enabled |
| `CONVERSATION` | Joining the project's conversation on each transport scheme |
| `IMAGE` | Preparing and building the task's image |
| `CONTAINER` | Creating and starting the container |
| `HELPERS` | Starting the task's gate, credential proxy and helpers |
| `AGENT` | Running the agent, when a prompt is given |

HELPERS ends DONE even when a helper printed a warning; the warning remains in the lines.

| State | What it says |
|---|---|
| `STARTED` | The step began; the CLI prints `step` and its text |
| `WAITING` | An outside dependency is being called; the CLI prints `waiting` and its name |
| `DONE` | The step completed; the CLI adds no line |
| `FAILED` | The step failed; the CLI prints `failed`, its text and its cause |

**`task remove`** refuses when the task is running (`stop` it first, or `--force`), holds commits
that never reached the gate (`--rescue` pushes them to the gate, `--force` discards them), or its
contents are unknown. A task never removes itself when it ends. At a terminal it offers instead: to stop a
running task first; to push the work it holds to the gate, discard it, or keep the task; to start a stopped task
so its work can be pushed; and to discard unseen what nobody recorded - no by default, and never with `--yes`,
which otherwise stops or starts the task as removing it needs. `--no-input` asks nothing.

**Elsewhere, what a refusal would send you to type is asked at a terminal**, by the same rules: `prune` and `clear`
list what would go and then ask whether to remove it, no by default, where they said to run them again with `--yes`;
`gate approve` onto a branch that already holds earlier work offers the next free one (`-2`, `-3` …), never a force;
`project unfollow` offers to stop the project's running tasks, and to destroy unreviewed work only on an explicit yes
after naming it. Each takes `--no-input`; without a terminal each refuses as before. A start refused because earlier
work of its name waits at the gate offers the next free name (`NAME-2` …), and one with no agent to run, or no gate for
the agent to hand work back through, offers a shell in the task instead; on a yes the start goes on that way.
Where an option names one of several - the provider for an agent that names no default, the agent for
`vault authorize` when several sign in, the project for `shield egress`, `talk pass` and `talk hold` - a terminal is
shown them by number and asked for one, instead of being sent back to add the option.
A task started before the machine restarted cannot come back; `task resume` and `task attach` on it offer to copy
its workspace to `./recovered` (`recovered-2` … where that is taken), and then to remove the task, no by default.
Where clearing stops, `clear` offers to stop the tasks that run with a token from the vault, and then to clear what
only `--force` clears, naming it, no by default; `vault remove` of a token only its service can revoke offers to forget
it here all the same, no by default; `credentials forget` offers to remove the secret it leaves in the vault, as
`vault remove` does.
A missing secret is asked and stored where a refusal named `porelo vault put`: `credentials check` asks an ssh key's
path, or has `vault put` ask a token without echo, then checks again; `vault login` of an agent with no login of its
own, and `vault authorize` of a provider reached with a key, store the key with `vault put` at once.
`gate protect` in a repository whose pre-push hook is somebody else's offers to append the check to it, no by default;
`--remove` then takes out only those lines. `gate restore` where a mirror is already offers to move it aside under a
dated name, never over another, and restores.
Without asking: `vault lock` and `vault passphrase` ask a keyring that did not answer again, three times, before they
say so; and at a terminal, a passphrase kept in the keyring that no longer opens the vault is dropped and asked again.

**`task logs`** shows the files of the gate, broker, relay, resolver and clearance watcher, not the
agent's output. `events.jsonl` is what the firewall blocked: look there when a task starts and then
does nothing. The logs **do not survive a restart**: `--zip` keeps them, with what `task status`, `task files` and
`talk log` say about the task, in `porelo-logs-<task>-<UTC time>.zip` here or in the file you name. It is readable by
you only, never overwrites a file, and `sha256sum -c SHA256SUMS` checks it. What the agent itself printed goes in only
with `--with-agent-output`. Unpushed work is only known while the task runs, or from the note `task stop` leaves.

**`task status`** says what was recorded when the task was created: its agent and provider, its mode
(`shell`, `agent` or `unattended`, kept when a stopped task is brought back), the branch its work goes
to, and for an unattended task the prompt it was given, kept after it has finished. `state` is the
container runtime's own words; `activity` says what the work is doing:

| Activity | Meaning |
|---|---|
| `working` | producing output |
| `idle` | up, producing nothing, and not waiting for anybody as far as anything can tell |
| `waiting` | waiting for a person, said by whatever asked (a clearance prompt, say); `waiting` names what about |
| `ended` | up, and its agent's run has ended: nothing more will happen in it on its own |
| `dead` | the container is not up |
| `unknown` | nothing on this side can see what it does, such as a task with a terminal attached |

A quiet task is not a waiting one: waiting is never guessed from how long nothing happened. An ended
run is said on a line of its own, `ended`: `finished at <time>`, or `with an error at <time>`, whose
(the provider's, with its HTTP status, or the agent's) and its text. Work the agent wrote and never
pushed is named there with the command that saves it, `porelo task remove TASK --rescue`; an agent that
cannot read its own end says nothing. The `screen` line is separate, a reading of the agent's own
screen against rules its package declares, and says when an agent declares none. `since` is when the
current state began, as an age and an instant.

## shield

What a task may resolve and reach. See [the firewall](security.md) and [DNS](security.md).

| Command | What it does |
|---|---|
| `porelo shield egress` | Shows what a project may reach; adds or removes sets and hosts. |
| `porelo shield sets` | Lists the egress sets a project can name in `project.yml`. |
| `porelo shield subscribe` | Follows the blocked connections a running watcher reports. |
| `porelo shield read` | Reads blocked connections from the firewall log. Started per task. |
| `porelo shield watch` | Prompts for each blocked connection. Started per task. |
| `porelo shield dns` | The task's resolver for allowed domains. Started per task. |

## vault

Credentials, and the broker that lets a task use one without holding it. See
[authentication](credentials.md) and [vault keyslots](credentials.md).

| Command | What it does |
|---|---|
| `porelo vault init` | Creates an empty vault; asks for its passphrase twice, or takes one line with `--passphrase-stdin`. |
| `porelo vault login [AGENT]` | Runs the agent's own login in a throwaway container and stores the result. |
| `porelo vault import [AGENT]` | Copies a credential the agent already holds on this host. |
| `porelo vault put NAME` | Stores a value from standard input under the provider's name. |
| `porelo vault authorize NAME` | Grants an OAuth authorization once in any browser and keeps it. |
| `porelo vault list` | Lists the names held. Never the values. |
| `porelo vault remove NAME [--without-revoking]` | Removes an entry, revoking a granted authorization first. If revoking fails, the entry is kept unless `--without-revoking`. |
| `porelo vault unlock` | Caches the passphrase in the kernel's user keyring, where the daemon finds it too; `--for` bounds how long, `--passphrase-stdin` takes it from a program. |
| `porelo vault lock` | Drops the cached passphrase. |
| `porelo vault status [--json]` | Says whether the vault is open, shut or not there, by its exit code: 0, 3 or 4. |
| `porelo vault passphrase` | Re-encrypts the vault under a new passphrase. |
| `porelo vault devices` | Lists what can open the vault: the passphrase and each device. |
| `porelo vault revoke ID` | Removes a device's way in. The last way in is never removed. |
| `porelo vault clear [--yes] [--dry-run] [--force]` | Removes the vault, its backup and lock, and what the keyring caches of it, with what the transports keep in it. Lists it first; `--yes` clears it. |
| `porelo vault serve` | The credential proxy a task's requests go through. Started per task. |
| `porelo vault relay` | Forwards a port in a task's namespace to the proxy's socket. Started per task. |
| `porelo vault agent` | An ssh-agent that signs with a key from the vault, for a command on the host. No task is given one. |
| `porelo vault credential` | git's credential helper. Run by git. |

**`vault authorize`** entry kinds:
- `oauth-device` (settings `client_id`, `device_authorization_url`, `token_url`, `scopes`) shows a
  link and a code.
- `oauth-code` (settings `client_id`, `authorization_url`, `token_url`, `scopes`, `redirect_port`,
  default 9420) answers at `http://127.0.0.1:<redirect_port>/callback`. From another machine,
  forward that port with `ssh -L` first.
- Either takes `revocation_url`.
- Either may instead name only `resource_url`: the endpoints come from the service's metadata, and a client is
  registered there once where the entry has no `client_id`
  ([A service that publishes its own endpoints](credentials.md#a-service-that-publishes-its-own-endpoints)).

**`vault status`** is for a program as much as for a person: a playbook branches on its exit code, 0 when what this
account keeps (a cached passphrase or a device's share) opens the vault, 3 when the vault is there and nothing kept
opens it, 4 when there is no vault, and 70 when the file is not a vault at all. "Open" is proved by reading the vault,
so a cached passphrase that no longer opens it answers 3. `--json` prints `{"exists", "open", "path"}`; those names
are kept.

**`vault lock`** answers one of three things: the passphrase was cleared, nothing was cached, or
whether one is cached could not be established. The last exits 70 and says what to check: treat the
passphrase as still cached until the keyring answers. A cached passphrase that expired (`vault unlock
--for`) or whose keyring was revoked counts as not cached. The cache is looked for in the user
keyring first and the session keyring second, so a long-running process that outlived its login still
finds it. `vault passphrase` says so too when it cannot drop the cached passphrase, which is the wrong
one after the change.

**`vault clear`** removes the vault in one step, in the shape of [clear](#clear): without `--yes` it
lists what would go and removes nothing, and each line says `removed`, `would go`, `not removed` with
why, or `for you`. It names what the vault holds - credentials, grants, the accounts a transport keeps,
stopped tasks' tokens - and removes the vault file with every device's keyslot, its `.old` backup and
its `.lock`, and the passphrase and device shares cached in the kernel keyring. A deploy key made by
an earlier version is still forgotten here and named `for you` to remove at the forge.

A transport's account goes with it. First, for each project whose `mail.transports.<scheme>` names
settings of its own - a server elsewhere - the transport's `clear --project` runs while the vault still
holds that account's token; then the vault goes; then the transport's account-wide `clear` runs. For
the matrix transport with the account's own homeserver, that removes the homeserver whole: its unit,
its database, its port and its registration token, so a later start sets up a new one. A vault that
`PORELO_VAULT` names leaves the account-wide part alone, since that belongs to the account's own vault.
It refuses,
removing nothing of the vault:
- while a task runs with a token from the vault, even with `--force`: stop the task first. With the
  vault locked, every running task is named, since what it holds cannot be read;
- while the vault is locked, since what it holds cannot be named; `--force` clears it unread;
- when a transport could not clear a project's account; `--force` clears the vault all the same, and
  that account stays on its server.

Afterwards `porelo vault init` starts as on a new machine.

## approve

`porelo approve [TASK] [--yes]`, typed in the checkout a task was started from, brings that task's work
back into it. Everything else is known there: the checkout is the repository's source, the work goes
into it, and the branch is `porelo/TASK`.

- **It shows what comes** - the commits at the gate and the start of the diff - and asks. After yes the
  branch holds the work; the checked-out branch and the working copy are left as they are. After no,
  nothing changes and the work still waits.
- **It brings back what is at the gate**, never what is only in the container: the agent commits and
  pushes when its work is done.
- **What it ends with, for a script:** 0 when the work was brought back; 66 when nothing waits, or nothing
  of the task named - at the command line or typed at "which one?" - with the names that do wait; 64 when
  several tasks wait and none was named where nobody can be asked (`--yes`).
- **A `porelo/TASK` that exists moves only when the work fast-forwards it**; otherwise it says so before
  asking, and nothing moves. The branch checked out is never the target.
- **Nothing reaches a forge through it.** A repository whose source is a remote - a followed project,
  or one added to `default` by its address - sends its work there with `porelo gate approve`, and
  `approve` in a checkout of it says so and moves nothing.
- Nothing waiting, or no task started from this checkout: it says so and exits non-zero. When a task
  holds commits or changes it never pushed, it names them, so they are not mistaken for nothing.

## gate

Nothing an agent pushes reaches a real upstream without passing here.

| Command | What it does |
|---|---|
| `porelo gate pending` | Lists waiting pushes and how long each has waited. |
| `porelo gate review NAME` | Shows what a pending push would change. |
| `porelo gate checkout NAME` | Opens waiting work as a read copy. It can only go back to the gate. |
| `porelo gate approve NAME [--commit=ID] [--signed]` | Forwards a push. `--commit` names the commit you reviewed and refuses if the push moved since. `--signed` merges it as a commit signed with your own git key. |
| `porelo gate reject NAME [--reason] [--force]` | Discards a push and tells the task. `--reason` reads why from standard input. A push somebody pushed upstream by hand, which `gate pending` marks "already upstream", is refused without `--force`, since discarding it would record the opposite of what happened. |
| `porelo gate protect` | Installs a pre-push hook that catches unapproved agent work. |
| `porelo gate check` | The check that hook runs. |
| `porelo gate backup` | Writes the mirror to a verifiable bundle file. |
| `porelo gate restore` | Restores a mirror from a bundle; never overwrites one. |
| `porelo gate serve` | Serves the mirror for an agent to push to. Started per task. |

All but `pending`, `protect` and `check` take `-p PROJECT` and `-r REPOSITORY`.
`porelo gate pending --project default` lists waiting work of every repository in `default`.

## credentials

Which secret a destination uses, and where it lives. Nothing here prints a value.

| Command | What it does |
|---|---|
| `porelo credentials list` | Lists the credentials this machine connects out with. |
| `porelo credentials declare MATCH --kind=KIND` | Records a destination's credential: `--vault`, `--file`, `--env` or `--agent`. |
| `porelo credentials check URL [--no-connect]` | Says which credential a URL would use and, for git, reads the URL with it as a start does: a refused key, a missing repository or a host out of reach is named with its fix. `--no-connect` only says what would be used. |
| `porelo credentials forget MATCH` | Forgets a record. The value is left alone. |
| `porelo credentials keys` | Lists this account's ssh keys without reading them. |
| `porelo credentials trust-host HOST[:PORT] [--fingerprint=SHA256:...]` | Shows a host's keys and records the one you confirm. Name the port when its ssh is not on 22 - `forge.example.org:7999` - as the hint at a start does. |

## talk

A task's mailbox. Each command names the task by its container name.

| Command | What it does |
|---|---|
| `porelo talk peers --project=NAME` | Lists the peers a project's tasks may address. |
| `porelo talk held TASK` | Lists messages waiting for a person: held, refused by the filter, or unsendable. |
| `porelo talk read TASK ID` | Shows one such message in full, with control characters written out. |
| `porelo talk release TASK ID [--refuse] [--reason]` | Sends it on, even if the filter refused it; `--refuse` refuses it for good. The sender is told. |
| `porelo talk hold TASK PEER [--release] [-p PROJECT]` | Holds a peer's messages for every task of the project, or releases them. How closely a peer is watched is set in [`project.yml`](project-file.md) (`mail.rules`), not here. |
| `porelo talk say TASK PEER [--kind=KIND] [--context=ID]` | Writes your own message to a peer, from standard input. |
| `porelo talk tell TASK` | Writes your own words into the task's inbox, from standard input. |
| `porelo talk pass TASK` | Moves the messages along once, without waiting for the daemon. |
| `porelo talk log TASK` | What happened to each message, never its text. |
| `porelo talk verify TASK` | Names the first entry of the message record that does not check out. |
| `porelo talk key [--as=PRINCIPAL] [--publish]` | Prints this machine's signing key as an `allowed_signers` line. |
| `porelo talk join PROJECT PERSON [--reset]` | Makes a person an account in the project's conversation and shows the login once. `--reset` sets a new password. |

A message is never edited. Read it before you release it. A message released despite the filter is
recorded as such, and the receiving peer may refuse it again.

## agents

`porelo agents [--verbose] [--supply-chain] [--json] [--directory=<path>]` lists the installed agents.
`--verbose` adds what each needs to reach; `--supply-chain` shows what its install pins; `--json` answers a
program, as [A host a playbook brings up](hosts.md) says.

## project

```
porelo project follow NAME URL|FILE [--signed-by=<key> | --unverified] [--dry-run] [--accept-rewrite]
porelo project following [--json]
porelo project inventory --machine-file FILE
porelo project refresh [NAME]
porelo project list
porelo project unfollow NAME [--dry-run] [--force]
porelo project clear NAME [--yes] [--dry-run] [--force]
porelo project default [list | add ADDRESS [--name=NAME] | remove NAME]
porelo project enroll PROJECT [--remove] [--as=PRINCIPAL]
```

- **`follow`** takes the project's configuration from its repository, checked against the
  `--signed-by` key. `--unverified` skips the check, and Porelo says so wherever the project is shown.
  From an address (`https://`, `file://`, `git@host:`), it is fetched again in the background. From a
  file - a bundle of the repository, or a directory on this machine - it is read once and never fetched;
  following it again from a newer file is how its settings change. An offline project is followed only
  from a file, and a fetch from an address that turns a project offline is refused.
- **`following`** lists the follows; **`list`** (also `porelo projects`) lists the projects. `following --json`
  answers a program, as [A host a playbook brings up](hosts.md) says.
- **`inventory`** prints a host's second play's inventory from the person's machine file and the project file it
  names, checked against its key and recording nothing ([A host a playbook brings up](hosts.md)).
- **`refresh`** fetches a followed project's repository now, or every one's without a name, instead of
  waiting for the next round. It prints what each fetch found and exits non-zero when one failed. A
  project followed from a file is not fetched; it says `from_a_file` and how it changes.
- **`unfollow`** removes the mirror, image, build directory and tasks. The repository and upstream
  are untouched. It refuses while work waits at the gate or tasks run. `--force` removes it all the
  same and destroys that work; with `--force` a project this account does not follow is not an error,
  and whatever is left of it is swept, so it is the one command a cleanup script can run blind after
  a failure.
- **`clear`** clears the project from this machine in one step: its tasks with their workspaces and
  mailboxes, its mirrors and image, the follow, its conversation and what this machine kept for it,
  and any deploy keys made by an earlier version from the vault. Without `--yes` it only lists what
  would go. Each earlier key is still forgotten here and named for you to remove at the forge,
  alongside this machine's line in the project's `machine-signers`. See [clear](#clear).
- **`default`** is the project every machine has and nobody follows. It is `guarded`, reaches only
  the agent's provider, refuses blocked connections and has no conversation. `task start` without
  `-p`, in a checkout no followed project names, adds that checkout's `origin` to it (a token in the
  URL is kept but not shown). It cannot be unfollowed, and its settings cannot be changed: for other
  settings, make a project repository and follow it.
- Approved work in `default` reaches `origin` with your own ssh agent or key, or a token stored with
  `porelo credentials`; never handed into the task. The daemon has no ssh agent, so through it only a
  key without a passphrase works. Trust an ssh host first with `porelo credentials trust-host`; until then the
  task does not start.
- **`enroll`** proposes this machine's message key as a signer of the project, as
  `enroll-<machine>` in the gate. Approve it with `porelo gate approve enroll-<machine> --signed`.
  `--remove` undoes it.

## providers

`porelo providers` lists the model providers declared here (by agent packages or files under
`~/.local/share/porelo/providers`), which agents use each, and what the vault holds for it. It never
shows a value, and says when the vault is locked.

## setup

```
porelo setup [--hooks-only] [--uninstall]
```

Makes this account ready. Each step runs only if needed, so running it again is safe:

- registers the OCI hooks in your podman configuration (a task start does this too);
- starts the daemon (`systemctl --user enable --now porelod`), or says what to run instead;
- creates the vault, **only at a terminal**, since a person must type the passphrase.

It ends by saying whether the account is ready. `--hooks-only` registers only the hooks;
`--uninstall` removes them.

## doctor

`porelo doctor` reports paths, the daemon's hardening and whether this machine can run a task. Each
dependency is checked by name: podman, the hook registration, podman's rootless network, dnsmasq's
`nftset` support, `nft`, `git`, `nsenter`, the SELinux policy and the kernel keyring, beside the vault,
the daemon and each followed project. Each line has one of four states:

| State | Meaning |
|---|---|
| `OK` | present and working |
| `DEGRADED` | works, less well than it should; the line says what it costs |
| `UNKNOWN` | the check could not tell, and says so rather than assume the good case |
| `MISSING` | a task would fail, or run without something it needs |

Every state but `OK` names the one thing to do. On a terminal the states are coloured - `MISSING` red, `DEGRADED`
and `UNKNOWN` yellow, `OK` green - beside the same words; piped, redirected or with `NO_COLOR` set there is no colour,
and `--color=always|never` decides it for one run. `--json` says the same as one JSON object, for a program:
`findings` in the text's order, each with `check`, `state`, `detail` and `remedy` (`null` when `OK`); `notes` for what
is installed and not used and for the failure log, each with `kind`, `file` and `detail`; `paths`; `process`; and
`version`. It carries no colour whatever `--color` says, and exits as the text does. Only `MISSING` fails the command (exit 69); a degraded
machine runs tasks. A machine using slirp4netns rather than pasta, for example, is degraded: the gate
then binds every interface and is reachable from this machine's network, with the per-task token what
keeps it shut. A binary hidden by another copy is named with both halves:

```
not used /usr/libexec/porelo/agents/porelo-agent-<name>
         hidden by /home/<user>/.local/share/porelo/agents/porelo-agent-<name>
```

An agent's description in `agents.d/` hidden by the account's own is said the same way, and one that names no
usable executable as `not taken <file>` with the reason.

`daemon dumpable` is about the daemon, the process that holds the vault's secrets, not about `doctor` itself: `no`
when its entries under `/proc` belong to root, which is how the kernel shows a process that cannot be core-dumped or
traced by its own user; `yes` with the restart that fixes it; `not running`; or `unknown`. `--json` carries it as
`process.daemon` with `pid` and `dumpable`.

The `vault` line says whether a vault exists yet. The `daemon` line says whether `porelod` runs, and
whether it is the same version as this command: after an update it restarts itself, and the line says
when one did not (see [After an update](running.md#after-an-update)). The daemon answers the same
checks, and a `ready` flag by the same rule, so a machine is never ready to one and not to the other.
It runs external programs, so it takes a moment and is not something to poll.

## prune

```
porelo prune [--yes] [--including-work]
```

Lists what nothing owns any more, and removes it only with `--yes`: unfollowed projects whose project
file is gone, leftovers of tasks whose container is gone (after 15 minutes unchanged), and images or
records named after projects that no longer exist. Running tasks and followed projects are never
touched.

**Unreviewed work is kept**: an unreviewed push, a stopped task's workspace, a held or unsent
message. `--including-work` removes those too. The clearance journal is always kept.

## clear

```
porelo clear [--yes] [--dry-run] [--force]
```

Clears this account in one step, for when you are done with a machine: every project as
`project clear` does, the tasks of `default`, then what each transport keeps for the account (a
homeserver it runs, say). Without `--yes` it lists all of it and removes nothing. The vault, your keys
and the installed packages stay: clearing is not uninstalling. `porelo vault clear` removes the vault.

Each line says `removed`, `would go`, `not removed` with why, or `for you`: a deploy key made by an
earlier version to remove at its forge, or this machine's line to take out of a project's
`machine-signers`. Each earlier key is still forgotten here and named once. Removing it at the forge
needs the forge's administration right, which your token for git does not need; changing
`machine-signers` needs your signed commit. Clearing attempts neither. It refuses while work waits
at the gate or tasks run; `--force` clears those too. Run again, it says there is nothing to clear.
With the vault locked, the earlier keys it cannot read are said to be unreadable, never answered as
none.

A deploy key made before 0.5.0, while Porelo was called Sokar, is titled `sokar <host> <project>/<repository>` at
its forge. Porelo reads nothing of Sokar's, so it never lists such a key: it is yours to remove.

## panic

`porelo panic [--dry-run]`, or `porelo task stop --all`, stops every running task and helper. Each stop of everything
is one line in `stops.log` in the state directory - when, who, from where (`cli` or the daemon), what stopped and what
survived - written even when nothing ran; the daemon's `Panic` answers that line as `journalled`. Nothing is removed; everything can be
started again.

A stop ends 0 only when no task it stopped still has its container running: a container counts as stopped only when
the runtime answers a stopped state for the id Porelo recorded, and anything else - stopping, paused, unknown, a failed
question - counts as still running. `task stop`, `task stop --all`, `panic`, `task remove`, `clear` and `project
unfollow` name each survivor on standard error as `porelo: still running: …` and end 70, and a removal that is not
confirmed keeps every record of the task, so a later stop or removal still finds it.

## daemon

`porelo daemon connect` bridges standard input and output to the daemon's socket, for use as an ssh
`ProxyCommand`. The daemon is `porelod`; see [the daemon](running.md#the-daemon).

## completion

```
porelo completion bash > ~/.local/share/bash-completion/completions/porelo
porelo completion zsh  > ~/.zfunc/_porelo     # with ~/.zfunc in $fpath before compinit
```

The deb and rpm packages already install completion; this is for a copied binary or a non-standard
completion directory. TAB offers the names each command can use (running tasks for `attach`, all
tasks for `stop`, projects for `unfollow`). `task start` completes only its options. Pressing TAB
never changes anything.

**Names.** No command asks for a missing name; a missing or unknown one is refused with the names
that would work.
- `--project` takes a name from `porelo project list`, never a path.
- A task works on one repository. In a checkout without `-p`, it is the checkout's repository. With
  `-p`, name it with `--repository`, even if the project has only one. The project's own repository
  (holding `project.yml`) is named after the project and can be chosen for planning work.
- A task name is lowercase letters, digits and hyphens, starts and ends with a letter or digit, is not
  only digits, and `porelo-<project>-<task>` is at most 64 characters. A container name such as
  `porelo-myproject-backend` is taken as the task `backend`.
