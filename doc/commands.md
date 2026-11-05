# Porelo Commands

Every command, to find its name. `porelo <command> --help` has every option.
[For a given job](#for-a-given-job) starts from what you want to do instead; the keys of a project
file are in [the project file](project-file.md).

| Group | What it does |
|---|---|
| [`task`](#task) | Runs and inspects agent tasks. |
| [`build`](#build) | Asks a project's forge what a branch or a commit built. |
| [`shield`](#shield) | Inspects and changes what a task may reach. |
| [`vault`](#vault) | Manages stored credentials. |
| [`host`](#host) | Opens a host vault with the person's local SSH key. |
| [`credentials`](#credentials) | Records which credential each destination is connected to with. |
| [`card`](#card) | Checks a person's card; makes and checks a host's. |
| [`org`](#org) | Answers the organization: its people, projects and hosts. |
| [`gate`](#gate) | Reviews and forwards what an agent pushed. |
| [`commit`](#commit) | Judges the commits a push brings to a host's forge. |
| [`talk`](#talk) | Shows and moves the messages a task exchanges with other tasks. |
| [`agents`](#agents) | Lists the agents installed on this machine. |
| [`providers`](#providers) | Lists the model providers, and what the vault holds for each. |
| [`project`](#project) | Follows project repositories, and lists the projects here. |
| [`setup`](#setup) | Makes this account ready: OCI hooks, daemon, vault. |
| [`doctor`](#doctor) | Checks whether this machine can run a task. |
| [`prune`](#prune) | Shows what nothing owns any more; removes it with `--yes`. |
| [`tighten`](#tighten) | Makes what Porelo left behind readable by this account alone, and says what it changed. |
| [`panic`](#panic) | Stops every task and helper, removing nothing. |
| [`keep`](#keep) | Copies the usage records into a directory, or puts them back from one. |
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

A share-only vault never prompts for a passphrase: commands name `porelo host unlock <host>`.
`porelo vault init --share-stdin` creates one with only an `ssh-agent` slot, and
`porelo vault unlock --share-stdin [--json]` opens it. Both take exactly 32 binary bytes then end of input,
refuse a terminal and exclude passphrase sources. The daemon holds a host share in locked native memory until lock,
daemon exit or reboot; omit `--for`. From the person's machine, use
`porelo host unlock example.org --host-key SHA256:... --login alice` with the verified host fingerprint.
A rejected share answers exit 77 and, with `--json`, `{"vault":"other-key"}`; successful JSON unlock answers
`{"vault":"open"}`. Malformed files and content that fails authentication after opening a slot answer 70.
`host unlock` initializes a new vault when the remote unlock answers exit 66 (`{"vault":"absent"}`). On exit 77
it asks for a yes on the terminal; `--yes` authorizes clearing and recreating the vault with this key in scripts.
Without consent it exits 77 and keeps every entry. A reset requires every sign-in again.
The remote executable defaults to `porelo`; use `--remote-porelo <path>` for an account installation, naming the `porelo` in that account's local `bin` folder.
`vault unlock` itself never resets a vault. See [credentials](credentials.md).

**Start work.** In a checkout, `porelo task start` works in the project that names its repository,
else in `default`, and names the task after the repository. A first start in a checkout with no project asks
the installed [plugins](ecosystem-plugins.md) what it needs, shows the packages and tools found and each host it
would open, and at your yes writes `project.yml` where you stand, for you to commit, and starts from it. In `default` the checkout is the source,
both ways: its committed history goes into the task, local commits included, and the approved work
comes back into it; its remote is yours to pull from and push to, and Porelo never reaches it. A project
that names the repository takes it from its remote and sends approved work there instead. Elsewhere, name both:
`porelo task start -p PROJECT -r REPOSITORY`. The same verb creates a task, brings back a stopped one
with its workspace, and refuses a running one. Leaving the session keeps the container; `--rm` does
not. A project your organization names needs nothing first: `porelo project list` shows it.

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

## build

| Command | What it does |
|---|---|
| `porelo build status -p PROJECT [-r REPOSITORY] --branch=BRANCH` | Reads the branch's head, its build verdict, jobs and failed jobs' logs. |
| `porelo build status -p PROJECT [-r REPOSITORY] --commit=SHA` | Reads the build of a full 40- or 64-character hexadecimal object name. |

Choose exactly one of `--branch` and `--commit`. `-p`/`--project` and `-r`/`--repository` take keys from the
project file. Without `-p`, this checkout's origin must match a project of this machine; the query never adds the checkout
to `default`. Without `-r`, the matched repository is used, or the project's own when it is the only repository;
otherwise the command names the keys to choose from.

`--logs=failure` is the default; `--logs=all` reads every job's log, and `--logs=none` requests no logs.
`--json` writes one object with `project`, `repository`, `branch` (null for a commit query), `commit`, `verdict`,
`detail` and `jobs`; each job has `name`, `result` and `log` as text. Human output escapes every control character
except newline and tab as `\xNN`, and every format character - a bidirectional override, an invisible joiner -
as `\uNNNN`, both in lower-case hexadecimal, so a log cannot rewrite or reorder what the terminal shows.
A carriage return right before a newline is read as the line end.

The project must declare `builds:` and `security_class: online`; see [the project file](project-file.md).
The reader and its API come from that declaration and the credential comes from the vault on the host.
At a terminal a shut vault asks for its passphrase; a script must unlock it first. The token is never printed.

| Exit | Meaning |
|---|---|
| 0 | A `success`, `queued`, `running` or `unknown` build answer. |
| 1 | A `failure` or `cancelled` build answer. |
| 2 | The project is unknown or no project matches the checkout. |
| 64 | Invalid options, an abbreviated commit, or a repository that needs to be chosen. |
| 69 | The reader is not installed, the branch is absent, or the forge refuses the query; the message names the next action. |
| 70 | The reader gave no answer or another unexpected failure occurred; the trace is in `failures.log`. |
| 77 | The vault is shut or the credential entry is missing. |
| 78 | The project has no `builds:` configuration, including `default`. |

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
| `porelo task prepare -p PROJECT [-r REPOSITORY]` | Builds the project's task image without starting a task; with `-r`, the image a task on that repository gets, chosen as a start chooses it, but for an agent found signed in, which it does not take. |
| `porelo task clearance TASK MODE` | Sets what happens to a blocked connection: `prompt`, `allow`, `deny` or `off`. |
| `porelo task start TASK --host LOGIN@HOST --project PROJECT ...` | Starts the task on that host over SSH, detached, its key certified here by your own agent, which is never forwarded. Paths named are the host's; `--set-file`, `--set-env` and `--restarted` are refused. |
| `porelo task certify TASK [--host LOGIN@HOST --project PROJECT]` | Certifies a running task's key anew for another week, by your agent; with `--host`, the task of that project on that host, by its own name. |
| `porelo task certificate FINGERPRINT [--json]` | Says which task a key signed for, who certified it and while, after the task is gone too; on a host, from the host's own record. |
| `porelo task check-certificate --task TASK --login LOGIN --keys FILE [--krl FILE] [--json]` | Run by a host's helper, not by hand: checks the task certificate on standard input before the host records it, and writes nothing ([the host's contract](hosts.md#a-task-certificate-judged-for-the-host)). |

**`task start`**
- `-p PROJECT`: without it, the project that names the checkout you stand in, else `default`.
- With no project named, at a terminal, a checkout with no project is asked about: what the plugins found, each host
  it would open, and anything they could not read, such as a JDK a parent POM sets that is not there yet, which a yes
  leaves out. The answer defaults to yes, and to no when a host is named only by the checkout and is in no
  [egress set](#shield). A No keeps nothing and asks again next time. `--yes`, `--no-input`, `--dry-run` or no terminal
  say what was found, write nothing and go on in `default`. `-p` and `--host` ask no plugin. No plugin, or nothing
  recognized, goes on in `default` and says so.
- A `project.yml` in the checkout that this machine has not approved gets the same yes or no, showing a summary and
  then the whole file and each file it names. It is a yes to that text, with no comparison to what a plugin says.
- A yes keeps this machine's own copy of the file and what it names, under `$XDG_CONFIG_HOME/porelo/projects/NAME/`, and
  every start, `-p NAME` too, reads that copy, whatever the checkout now holds. A new file also goes into the checkout,
  for you to commit.
- **Same file, same project:** a checkout with the same upstream and the same hash, the file and each file it names, uses
  that project with no question: a second clone, or a git worktree. A checkout with no usable `origin` has its own
  path as upstream, so a second such checkout is not the same project. A changed file from the same upstream asks
  again, and a yes replaces the copy; a no, `--yes` or no terminal go on in `default`. Another upstream, or a name that is
  `default` or another local project's, is refused and goes on in `default`, saying so, as does a link, a named file
  outside the checkout, a file over 1 MiB, a `project.name` that is no text, or one that does not read. In a repository of
  an organization's project such a file decides nothing.
- The question covers `project.yml` and the files it names. A repository's `porelo.yml` is not part of it and is read as
  [its decision](decisions.md#a-repositorys-image-comes-from-poreloyml-chosen-key-by-key-and-its-build-keeps-the-hosts-network)
  says, also after a no in `default` and after a yes.
- Same upstream means same host, port and path, by ssh or https alike; a path on this machine is never a remote address.
  A reapproval keeps what you put beside the copy, such as `projects/NAME/<repository>/.porelo.yml`.
- `-r REPOSITORY`: which of the project's repositories to work on (see [names](#completion)).
- Without a name, the task is named after its repository, or the next free name (`my-first-2`); a repository
  named `people` or `person` gives `work`, and a leading `porelo-` is left out (`porelo-pi` gives `pi`).
- By default you get the agent's session. `--attach shell` gives a shell, `-P`/`--prompt` runs the
  agent unattended, `--detach` hands over nothing, `--now` returns before the image is built.
- `--rm` removes the container when you leave. By default it is kept. A start that fails leaves nothing running
  either way: a container it made stays stopped for a look, starting it again brings back the helpers it had, and
  `porelo task remove` ends it without `--force`. A container of the task's name that the start did not make is left
  alone.
- `--set NAME=VALUE`, `--set NAME`, `--set-file FILE` and `--set-env` hand in the
  [values the project declares](running.md#values-a-task-is-started-with), checked before anything is made.
- `--branch NAME` names the task's branch at the forge outright, instead of the one the project's
  [`branch` block](project-file.md#branch) makes; it must match its pattern too. The start shows the branch, and
  `task status` keeps showing it as `forge`.
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
  Without `--agent`, the start takes the agent the project file names for the repository
  (`repositories.<repository>.agent`) and says so in its plan; one that is not installed refuses the start. With
  neither, it asks each installed agent what it finds of itself and takes the one found signed in, with the provider
  it is signed in to unless `--provider` says otherwise, and names the choice and where it was found. Signed in to
  several, it takes the one the vault already holds, otherwise the first the agent names. An agent that
  cannot say is never read as not signed in. Otherwise, with several installed, it lists them, those found signed in
  first, and asks which, by number; without a terminal it asks for `--agent` as before. An agent found signed in
  whose credential the vault lacks is offered `vault import`, or named it without a terminal. A task's commits are authored by the name its agent goes by, `Agent <nickname>` or
  `Agent <repository>`, at `agent@localhost`. Without `-r`, it lists the repositories the project works in and asks which,
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
  task with another `--model` is refused, naming what it was started with. So is its
  [`--subagents`](project-file.md#limits) number: a continuation hands its agent the number the task was
  started with, takes `--subagents` with that number and refuses another with exit code 64.

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
`project remove` offers to stop the project's running tasks, and to destroy unreviewed work only on an explicit yes
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

`task status` also reads provider-reported token usage, summed by answering model, agent, local day
and local hour. Each of input, cache read, cache write, output and reasoning has its reported total
and the number of requests with a missing count. A wholly unreported total is unknown, never zero.
Hours include their UTC offset. `--json` adds, where a record exists, the same structured `usage` value returned by the daemon's
`Usage(task)` method: `state` (`available`, `absent` or `unreadable`), `totals`, `models`, `agents`,
`days` and `hours`. Each group has `name` and `totals`; totals have `requests` and five fields (`input`,
`cacheRead`, `cacheWrite`, `output`, `reasoning`), each with nullable `tokens` and `missing`.
A removed task's latest retained usage remains readable by its full task name; `task status` prints it
and still exits 69, since there is no such task. A killed broker's half line costs one request's counts,
not the record's. `List` and `Watch` keep their existing task shape and do not read usage records.

**What a task's image was built with.** Every image a start or a prepare builds records its settings in the
label `org.fuin.porelo.settings`, and the daemon's `ImageSettings(task)` answers them for the image that task's
container was made from, whatever the project's file says now and whichever image its name has come to name:
`baseImage`, `packages`, `tools`, `lines`, `agent`, `agentVersion`, `securityClass` and `recorded`, when the image
was built. Each comes with its `source`: `project.yml`, `porelo` for Porelo's own settings (the project `default`'s,
and the tools of every image), `agent package`, or the file a repository's own image was read from. `packages` is
what the install commands in `lines` name, read and never run, so what a line fetches some other way is only in
`lines`. `lines` holds an `ENV` line by its names and `<withheld>` for each value, and a URL's user and password as
`https://<withheld>@host`; a secret written into a snippet any other way is shown as written, so a snippet holds none.
A record over 64 KiB loses lines from the end and says so in its last line. An image built before Porelo recorded
this answers `known` false with nothing filled in, and so does a build that records nothing, whatever its base image
carries; it is not read from the project's file instead. An image built before this from a base image that itself
carries the label answers with that claim. The first build after that adds one cached layer, and a task made from the
earlier image says the image has changed since, as it does after any rebuild; nothing is rebuilt by it. Moving the
same settings to another file changes the image's id the same way.

**A task's own key.** Every task with a gate gets an Ed25519 key of its own, held outside its container,
and a certificate for it: valid seven days, for the one principal `task:<project>:<task>` (`default` for
a task without a project), signed by your key through your own agent. A project's task is certified by a
key of a person the [organization](project-file.md#how-the-file-reaches-a-machine) lists in it, a task without a project by the first
Ed25519 key in your agent. The task's commits are signed at its gate, so the agent never holds the key,
and the gate judges every commit that arrives by `ssh-keygen -Y verify` at its arrival, not by the date
it carries: a commit arriving after its certificate ended is refused, naming the end. Each verdict is
recorded as an `arrived` event. When your agent holds none of those keys, the task starts as before,
unsigned, and `certified` is `false` in `task list --json`; on a host whose persons start tasks
(`porelo-host-person`), it does not start at all. There, the host records the certificate, makes the
task's forge token, and pushes its branch, `porelo/<task>`, itself; the container holds no token.
Porelo runs the helper by its path through `sudo -n`, as
[the host's contract](hosts.md#a-task-certificate-judged-for-the-host) says.

An interface starts a task on a host through the daemon the same way, with the agent at the person's computer and never
forwarded. `Start` with `certify: true` and `more` carries one reply `certify`, naming the key, the one principal, the key id,
the end and the keys that may certify. The interface signs there and answers by `Certify(start, certificate)` on a connection of
its own, or by `Certify(start, refused)` with its words, and the start checks the certificate as any other. A start waits five
minutes, or until its stream closes, and then ends as refused, leaving nothing. Each row of `List` and `Watch` says when the
certificate ends as `certifiedUntil`, for `porelo task certify --host` from that computer before then.

`lastWrote` in the JSON status and the daemon's `Task` is the modification time of `task.log`, an
ISO-8601 instant, or `""` without a readable log. Ask `Tail(last: 1)` for its last line when showing it.
`inboxWaiting` counts `.json` files in the host-side `inbox/new`, including for an ended or stopped task;
it is `0` without a readable mailbox. `Watch` changes for this count and for `activity`, while a change
of `lastWrote` alone does not resend the list. `idle` means quiet, not "waiting for a message".

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
| `porelo shield watch` | Prompts for each blocked connection. Started per task. A watcher following its task's events ends on its own once the container process it was given (`--pid`) is gone, or a later process took that id; the task's stop or poststop hook ends it before that. |
| `porelo shield dns` | The task's resolver for allowed domains. Started per task. |

## host

| Command | What it does |
|---|---|
| `porelo host unlock <host> --host-key SHA256:... --login <account>` | Checks the host's Ed25519 key and sends a locally derived share over SSH to unlock or initialize its vault. `--previous-key FP` changes keys while keeping entries; `--remote-porelo PATH` selects the remote executable (default `porelo`); `--yes` authorizes a reset for another key; `--json` reports the outcome. |
| `porelo host whoami [--json]` | Run in a host account, over the SSH a tunnel opens first: asks the account's own daemon at `$XDG_RUNTIME_DIR/porelod.sock` for its version (`GetInfo`, within 5 seconds; a socket file alone proves nothing) and answers `{"login", "uid", "return_socket"}`: the account's login and numeric uid and that socket, where the tunnel returns to. It reads and writes nothing else. Exit 0; 66 with `{"reason": "no-daemon", "words"}` on stdout (with `--json`) and the words on stderr when no daemon answers there. |

Run this on the person's machine with `SSH_AUTH_SOCK` pointing to their agent. With several Ed25519 keys,
`--key SHA256:...` chooses the person's identity. Host name or IPv4 address, host key fingerprint and login are
explicit; no host card is read. A missing agent exits 69; a mismatched host key refuses before asking the agent to
sign. The SSH session has no terminal or agent forwarding, pins the host key and disables connection reuse.
The share is standard input, never an argument; the signature stays local. A verified `absent` answer initializes
the remote vault. A verified `other-key` answer exits 77 unless a terminal yes or `--yes` authorizes clearing and
recreating it; all sign-ins must then be made once more. A passphrase vault or any vault containing other slots
answers 65 with `{"vault":"not-a-host-vault"}` and is preserved even with `--yes`. The JSON answer and exit must
agree: `open`/0, `absent`/66, `other-key`/77 or `not-a-host-vault`/65, as one JSON line with no other stdout.
An unexpected answer exits 70, prints the remote stderr without control characters, and sends no further command.
Reset verifies `vault clear --yes --force --json` before sending the share again to initialize the vault.
Without local `--json`, the command prints words.

When both keys are available, add `--key NEW_FP --previous-key OLD_FP`. Porelo derives both shares locally and sends
exactly 64 bytes, old then new, to `vault rekey --share-stdin --json`. The new key replaces the previous opening
share while every entry is kept. A previous share that does not open the vault exits 77 without changing it;
`--yes` does not turn a failed rekey into a reset. See [credentials](credentials.md).

## vault

Credentials, and the broker that lets a task use one without holding it. See
[authentication](credentials.md) and [vault keyslots](credentials.md).

| Command | What it does |
|---|---|
| `porelo vault init` | Creates an empty vault; asks for its passphrase twice, takes one line with `--passphrase-stdin`, or creates a share-only vault with `--share-stdin`. |
| `porelo vault rekey --share-stdin [--json]` | Reads exactly 64 binary bytes, old share then new share and end of input; changes a host key while keeping entries. Refuses a terminal and non-host vaults. |
| `porelo vault login [AGENT]` | Runs the agent's own login in a throwaway container and stores the result. An agent whose `login.flow` is `loopback` is refused over SSH, before anything is built or the vault asked, since the redirect would land on this machine; a refusal elsewhere never names this login for it. |
| `porelo vault renew NAME` | Marks a sign-in due; the next task's first request renews it on the host. A running task keeps its token until it ends. |
| `porelo vault import [AGENT] [--provider NAME]` | Copies a credential the agent already holds on this host, with `--provider` the sign-in for that provider, stored under its name. |
| `porelo vault put NAME` | Stores a value from standard input under the provider's name. |
| `porelo vault authorize NAME` | Grants an OAuth authorization once in any browser and keeps it. |
| `porelo vault list` | Lists the names held. Never the values. |
| `porelo vault remove NAME [--without-revoking]` | Removes an entry, revoking a granted authorization first. If revoking fails, the entry is kept unless `--without-revoking`. |
| `porelo vault unlock` | Caches a passphrase in the kernel's user keyring, with `--for` optional; `--passphrase-stdin` takes a line. `--share-stdin` takes 32 binary bytes; a share-only vault's share goes into the daemon's locked memory until lock or exit. |
| `porelo vault lock` | Drops the cached passphrase and share. |
| `porelo vault status [--json]` | Says whether the vault is open, shut or not there, by its exit code: 0, 3 or 4. |
| `porelo vault passphrase` | Changes an existing passphrase slot; refuses a share-only vault. |
| `porelo vault devices` | Lists what can open the vault: the passphrase and each device. |
| `porelo vault revoke ID` | Removes a device's way in. The last way in is never removed. |
| `porelo vault clear [--yes] [--dry-run] [--force] [--json]` | Removes the vault, its backup and lock, the daemon-owned share and what the keyring caches of it, with what the transports keep in it. Lists it first; `--yes` clears it. |
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

**`vault lock`** releases the daemon-owned host share as well as the kernel's cached passphrase and device shares.
It never reads the vault file to forget, even when it has been replaced by malformed content. An unreachable
socket counts as nothing held only after establishing that no porelod is running for this uid; otherwise lock and
clear refuse and name the expected socket path.
It answers whether something was cleared, nothing was cached, or
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
its `.lock`, the daemon-owned host share, and the passphrase and device shares cached in the kernel keyring. A deploy key made by
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

`--json` answers one object for a program that decides on `--force`: `items`, each with `kind`, `what`,
`status`, `why`, `transport` and `project` (the last two set on a transport's account of a project),
`refused`, and `kept`, why the vault was kept - `tasks`, `locked` or `transports`, `""` when it was
not. It exits 1 when anything was not removed, as without it.

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
- **Nothing reaches a forge through it.** A repository whose source is a remote - a project of an organization,
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
| `porelo gate approve NAME [--branch=NAME] [--commit=ID] [--signed]` | Forwards a push, onto `main` unless `--branch` names another; in a project with a [`branch` block](project-file.md#branch), onto the task's own branch, and a named one must match the block's pattern. `--commit` names the commit you reviewed and refuses if the push moved since. `--signed` merges it as a commit signed with your own git key. |
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
| `porelo credentials trust-host HOST[:PORT] [--fingerprint=SHA256:...] [--json]` | Shows a host's keys and records the one you confirm. Name the port when its ssh is not on 22 - `forge.example.org:7999` - as the hint at a start does. `--json` answers `{"host", "keys": [{"type", "fingerprint", "key"}], "known"}`, recording nothing, or with `--fingerprint` `{"host", "recorded": {"type", "fingerprint"}}`. A host that offers nothing, or no key with that fingerprint, is exit 70 with the words on standard error. |

## card

A person's card says who they are and names the one key they sign, log in and certify with. It is a
YAML body - `login`, `name`, optional `email`, `key` (`ssh-ed25519`), `created`, `expires`, and `predecessor` when the
card changes the key - followed by one or two armored SSH signatures in the `porelo` namespace over the body's exact
bytes. The first is the card's own key; a change of key is signed by the old key as well, so neither a stranger nor
the old key alone can hand an identity on. `ssh-keygen -Y verify -n porelo` checks each signature over the body too.
The email is shown as the person wrote it; nobody checks it.

| Command | What it does |
|---|---|
| `porelo card verify CARD --pinned SHA256:... [--earlier CARD]... [--krl FILE] [--json]` | Accepts the card when its key is the pinned one, or a chain of changes of key, each signed by both keys, leads back to it through the earlier cards given. A chain through a key on the revocation list (`ssh-keygen -k`) is refused; the card's own key on it is answered `revoked`. Exit 0, or 65 naming why; 66 when a file cannot be read, the revocation list included. Needs no vault, home or network. |

`--json` answers `{"login", "name", "email", "ssh", "expires", "revoked", "chain"}`: `email` is `""` when the card
has none, `ssh` the card's key, `chain` the fingerprints from the pinned key to the card's.

A host card says where a host is reached and what listens there. It is a YAML body - `host` (a lower-case DNS label),
`address`, `ssh_port`, `ssh_host_key` (an OpenSSH public key line, `ssh-ed25519 AAAA...`), and `services`: `forge`
with `web_port` and `git_port`, `matrix` with `port`, every port a plain number from 1 to 65535, and a service the
card does not offer left out - followed by one armored SSH signature in the `porelo` namespace, the host admin's. The
body names no signer: whoever reads the card names the key they trust for the host admin.
`address` is a host name or an IP address written one way only: IPv4 as four decimal parts with no leading zero,
IPv6 without a zone, brackets or IPv4 part; a name whose last label is digits or `0x` hex is refused, since a resolver
reads it as an address. An RSA `ssh_host_key` has at least 2048 bits.

| Command | What it does |
|---|---|
| `porelo card make-host --host NAME --address ADDRESS --ssh-port PORT --ssh-host-key FILE --forge-web-port PORT\|none --forge-git-port PORT\|none --matrix-port PORT\|none (--key FILE \| --agent-key FINGERPRINT\|PUBFILE)` | Writes a host card to standard output, signed with the host admin's key: run on their own machine, since nothing on the host holds it. `--ssh-port` is the host's own SSH port; the host serves the forge and the homeserver on sockets only, so `--forge-web-port`, `--forge-git-port` and `--matrix-port` are the local ends a person's tunnel forwards to them (3000, 3001 and 8448 by default in the interface), all three required, each a port or `none` for a service the host does not offer; a forge has both its ports or neither, and anything else is wrong use (64). `--ssh-host-key` is the host's `.pub` file, its comment dropped; `--key` an unencrypted Ed25519 OpenSSH private key file; `--agent-key` instead signs through the ssh-agent `SSH_AUTH_SOCK` names, with the Ed25519 key of that `SHA256:` fingerprint or of that `.pub` file, reading and writing no private key file. Either way the card is the same bytes. Each key file is read only as a regular file of at most 16 KiB, never through a symbolic link. Exit 0; 64 unless exactly one of `--key` and `--agent-key` is given; 65 naming why, and with `--agent-key` naming the key's fingerprint when no agent answers, it does not hold the key, the key is not Ed25519 or the agent does not sign; 66 when a file cannot be read. |
| `porelo card verify-host CARD --signer SHA256:... [--json]` | Accepts the card when the key with that fingerprint signed it and nothing else; `services` holds only what the card offers, and the text says `no forge` or `no matrix` for the rest. Exit 0, or 65 naming why - a bad signature, another signer, a host name that is not a lower-case DNS label, a field missing or a port of a service it offers; 66 when the file cannot be read. Needs no vault, home or network. |

`--json` answers `{"host", "address", "ssh_port", "ssh_host_key", "services": {"forge": {"web_port", "git_port"},
"matrix": {"port"}}, "signed_by"}`, the ports as numbers, `signed_by` the signer's fingerprint.

## identity

The person's own card, kept at `$XDG_CONFIG_HOME/porelo/identity/card` with its key's line beside it as `key.pub`.
No private key is read, written or kept by Porelo: every signature is the agent's, the one `SSH_AUTH_SOCK` names, but
a backup key's, which `ssh-keygen` makes into a file and signs with, asking its passphrase on the terminal itself.
These verbs act on the organization `organization.yml` names, through its store. Contacts are `{"login", "name",
"email"}` from the accepted cards, `name` and `email` `null` where none says them. Every `--json` answer carries
`revision`, the state it rests on, `null` when nothing was accepted yet. A typed refusal is one line `{"reason", "words",
"revision", ...}` on stdout, `words` also on stderr, with the exit codes of `org`, and for a change `no-key` (69) when
the agent does not serve or does not sign with the key. Text passes the same printable filter as every answer; the
card's text keeps its line ends.

A change of key is kept as an operation at `$XDG_STATE_HOME/porelo/identity/ops/ID.json`, owner-only, before the
new card is filed, so a run stopped half way is read back by its id, never made again. The card is kept here as the
person's own once the store took it. Before each push the record is marked, and only an answer that says nothing was
pushed clears the mark: a refusal of the first try, whose record goes, or a fetch that failed, whose record stays
unmarked. A later state of the store clears nothing, since a forge may serve an older state again, so a marked record is
never deleted on the store's word. After `cannot-say` or `failed` the same id goes on with the same card; a local write
failing after the push is 70 `failed` with the `id`, and the same id finishes it. The same id again answers that change
with `again: true`, whatever key is named and although the store moved on. A new id with another key replaces a
pending change that reached nowhere, and is 65 `exists`, naming the id, for one whose card stands in the organization or
that is marked and hands on from the card kept here: go on under that id, or with `identity revoke --lost`, which retires
every record that hands on from the key it replaces, marked or not, and names their ids under `retired`.
The `identity` and `ops` directories are set `rwx------` whatever the umask, and refused when one of them, or a
directory between them and `$XDG_CONFIG_HOME/porelo` or `$XDG_STATE_HOME/porelo`, is a link or another account's;
this is checked before the card or a record is read, too (66).
A record is taken only when its `id` is its file name and its card verifies, is of the keys it names and of the
login whose card is kept here; else 66 `operation-damaged`. Every card made is checked as `card verify` checks it
before it is kept or filed. Backup owners are never named in `who_may`. No URL is printed or kept with its user part:
in each word holding an `@`, all up to its last `@` is left out but for a `scheme://` it begins with, so a mail address
keeps only its domain.

| Command | What it does |
|---|---|
| `porelo identity show [--json]` | Answers the card kept here and where it stands, public values only: `login`, `name`, `email` (`null` when none), `fingerprint`, `key`, `created`, `expires`, `days_left` (negative once past its end), `renew_due` (true from 7 days before its end), `predecessor` (its fingerprint or `null`), `card` (its text as kept), `agent` (`holds`, `not-held` when an agent answers without the key, `absent` when none answers) and `agent_holds_key`, `revoked` (the key is in `$XDG_CONFIG_HOME/porelo/revoked.krl`, or the organization says the card's key is revoked), `card_refused` (`null`, or why the card does not verify, as `card verify` says it), `organization` (none, or one `{address, filed, why, revision, stale, may_file, who_may}`: `filed` `done` when its accepted, trusted card is this one, `pending` when first seen or another key's card is accepted, `refused` with `why`, `absent` when it holds no card of this login; `stale` when the store was out of reach and this is the last state; `may_file` `false` with `who_may` for a card with no predecessor over another key's accepted card, a lost key's, which only an owner files), `forge` (`{login, key_listed, keys_url, account_url}`, `null` with no organization: `key_listed` asked of the forge, `null` when it could not be asked or the state is stale), `pending` (each operation not done as `{id, action, fingerprint}`, the new key's fingerprint, but a lost key's card an owner accepted; a record that does not read is left out) and `sole_without_backup` (each place the person alone holds without a backup key, as `{role: organization-owner\|host-admin, scope: {kind: organization\|host, name}}`). Exit 0; 65 `no-identity` when no card is kept here; 66 for a card or revocation list that cannot be read; 69 `cannot-say` when the store is out of reach and no state was read yet; 65 `refused-state`. |
| `porelo identity keys [--json]` | Lists the keys the agent serves, in its order, as `{"revision", "stale", "keys": [{fingerprint, key, comment, usable, reason, key_listed}]}`: `key` without its comment, `usable` when `identity create --key` would take it, else `reason` `key-not-ed25519`, `revoked` or `key-on-another-card` (with `login`: the organization holds an accepted card of that login with the key, any login but the kept card's). `key_listed` is asked of the forge for the kept card's login; `null` without a card, an organization, or on a stale state. Exit 0; 69 `agent-absent` when no agent answers; 66 for the kept card or the revocation list that cannot be read, a linked or foreign directory on the way to one included. |
| `porelo identity propose [--forge URL] [--json]` | Proposes login, name, optional email and an Ed25519 signing key from Git, the agent and the forge, with each source, missing fields, ambiguities, notes and an `ssh_add` line when needed. Writes nothing and asks no question. `ready` means login, name and a unique key in the agent are found. The forge defaults to the organization’s forge, then `https://github.com`. Exit 0 even when the forge cannot answer; 64 for misuse. |
| `porelo identity create --login LOGIN --name NAME [--email EMAIL] --key FINGERPRINT\|PUBFILE\|--keepassxc DATABASE [--entry GROUP/NAME] [--id ID] [--base REVISION] [--json]` | Makes the first card for a key the agent serves, named by its fingerprint or its `.pub` file, signed through the agent and valid 30 days from today (UTC). With an organization it is filed through its store and then kept, and answers `{"revision", "id", "again", "login", "fingerprint", "key", "card", "agent": "holds", "forge", "next": []}`. The store takes a first card only for a key the forge lists: 65 `not-allowed` otherwise, so the key goes on the forge first. With no organization, or one whose repository holds nothing yet, it is kept here, `revision` and `id` `null`, `next` `["setup"]`, or `["org create"]` when one is named; a repository that holds nothing although this machine read a state of it is 69 `cannot-say`. A card so kept, of the same login and key with `--key`, is filed as it is once one is named. The same `--id` with `--key`, the same login and name, and a card kept that the organization holds as it is or nobody was asked to file, answers as made, `again` `true`. Exit 65 with `identity-exists` (another card is kept), `key-not-ed25519`, `revoked` (with `who_may`, the owners) or `key-on-another-card` (with `login`); 69 `no-key` when no agent answers or it does not serve or sign with the key; with `--keepassxc` the refusals of a new key made into KeePassXC below, and a refusal of the store after it was made names its `entry` and `fingerprint`, to go on with `--key`. 64 for a login, name or email a card does not take, or both ways at once. |
| `porelo identity renew [--id ID] [--base REVISION] [--json]` | Gives the card a new term of 30 days from today (UTC), with the same key: signed through the agent and filed through the store, under the id given or one made as `ren-<UTC date>-<n>`. A card past its end may be renewed. Answers `{"revision", "id", "again", "of": "organization", "action": "renew", "fingerprint", "expires"}`. Exit 78 `no-organization`; 69 `cannot-say` when the store is out of reach and `no-key` when the agent does not serve the key; 65 `moved-on`, `exists`, `revoked` (with `who_may`), `no-identity`; 66 `operation-damaged`. What the key signed under its earlier card stays signed: a renewal moves the end of its term, and a lapse between two cards signs nothing. |
| `porelo identity rotate --key FINGERPRINT\|PUBFILE\|--keepassxc DATABASE [--entry GROUP/NAME] [--id ID] [--base REVISION] [--json]` | Changes the card's key to one the agent serves, named by its `SHA256:` fingerprint or its `.pub` file: a new card valid 30 days, signed by the new key and the old, both through the agent, and filed through the store, under the id given or one made as `rot-<UTC date>-<n>`. An owner's change is two commits in one push: the old key signs `owners.yml`, the new key the card. Answers `{"revision", "id", "again", "of": "organization", "action": "rotate", "fingerprint", "predecessor"}`, the fingerprints the new key's and the old one's. Exit 78 `no-organization`; 69 `cannot-say` when the store is out of reach and `no-key` when the agent serves either key not; 65 `moved-on`, `exists`, `revoked` (the old or new key; with `who_may`), `key-not-ed25519`, `key-on-another-card` (with `login`), `no-identity`; 64 when the key is the card's own or the use is wrong; 66 `operation-damaged`. No KeePassXC entry is made for a change refused before it could be signed. |
| `porelo identity revoke --lost --key FINGERPRINT\|PUBFILE\|--keepassxc DATABASE [--entry GROUP/NAME] [--dry-run] [--id ID] [--json]` | Replaces a lost or stolen key: a new card signed by the new key alone. It files nothing: the store takes no such card from the person. It answers `{"revision", "stale", "id", "again", "action": "revoke-lost", "fingerprint", "predecessor", "card", "plan": {"who_may", "accepted_with", "order"}, "retired"}`, `retired` the ids of the changes that handed on from the replaced key, whose records go, `who_may` the owners who may accept it with `porelo org person replace LOGIN --card FILE`, and the card is kept here. The order matters: **a backup key works only while the old key is still on the forge, so first the backup, with `identity backup-key --use`, then the key off the forge; after that only an owner's `org person replace` helps.** It is never refused for a pending change: a rotation that reached nowhere goes, unless it is marked, when its record stays; one that reached the organization has its card stand, and the new card is made against it. A store out of reach answers on its last state, `stale: true`; with none, 69 `cannot-say`; with no organization, `revision` `null` and no owners. `--dry-run` keeps nothing and answers `{"revision", "stale", "plan", "hosts", "sign_ins"}`: each host the login is on, its vault made again (`"vault": "reset"`), and the vault entries whose sign-ins are lost with it (entry names only; none are known here yet). The id is made as `lost-<UTC date>-<n>`. `--dry-run` with `--keepassxc` makes no key. |
| `--keepassxc DATABASE [--entry GROUP/NAME]` on `identity create`, `rotate` and `revoke --lost` | First reads what it needs, changing nothing and before any password: that this KeePassXC has its SSH agent extension, that its settings turn the agent integration on (settings not found are said and do not refuse), and that it runs. What is missing is refused as `key-store-not-ready`, each with its next step. It then makes the new Ed25519 key in memory and adds it to the KeePassXC database with `keepassxc-cli`: an entry (default `porelo-<login>-<UTC yyyyMMdd-HHmmss>` at the database's root) with the private key as the attachment `id_ed25519` and the agent settings as `KeeAgent.settings` (added to the agent when the database is opened or unlocked, removed when it is locked, never saved to a temporary file). Each attachment reaches `keepassxc-cli` on a pipe, read as `/dev/fd/3`: never a file and never an argument. `keepassxc-cli` asks for the master password on your terminal itself, once for the entry and once for each attachment; Porelo never sees it. Porelo then forgets the key and waits for the agent `SSH_AUTH_SOCK` names to serve it. KeePassXC loads an entry added while its database is open only once that database is unlocked again, so when the agent has not served the key after 3 seconds, Porelo locks every database the running KeePassXC has open (`keepassxc --lock`; the others stay locked until you unlock them), asks it to show the database, says so on your terminal and waits up to 60 seconds for you to unlock the database in KeePassXC's window; a KeePassXC that is not running is not started, and the answer is `key-not-served` at once. Then it makes and signs the card through the agent as for `--key`. The answer is the verb's, with `"entry"` and `"agent": "holds"`. Exit 64 `no-terminal` where standard input or standard error is no terminal this account can open, `terminal-not-yours` on another account's, under `su` or `sudo -u`, both before `keepassxc-cli` starts, `no-password` when a prompt waits 120 seconds, and for an entry path with an empty part or a leading `-`; 65 `key-not-in-agent` when no agent answers (nothing is made), `key-store-missing` without `keepassxc-cli`, `key-store-refused` when it refuses (a wrong password, an entry that exists, no database at that path; nothing is kept here), and `key-not-served` with `id`, `entry` and `fingerprint` when KeePassXC took the entry but its agent did not serve the key in time (the database not unlocked in time, or its SSH agent integration off; at once when KeePassXC is not running; the words say which): the entry is kept and no card made; ask again with that `--id`, which makes no second key, asks for no password and answers the first run's entry, or name the key with `--key FINGERPRINT` once the agent serves it. An entry made whose attachment is then refused is removed, to the database's recycle bin if it has one, where the private key stays until the bin is emptied; the answer says which, or that it is left for you to remove. KeePassXC from the deb `keepassxc-full`; the snap cannot reach the agent socket under `/run/user`. |
| `porelo identity backup-key (--organization \| --host HOST) (--out FILE \| --use FILE --card NEW_CARD) [--base REVISION] [--id ID] [--json]` | A second, offline key for an owner's place or a host admin's. `--out` runs `ssh-keygen -t ed25519 -f FILE` on the terminal, which asks the passphrase twice and writes the file with mode 0600; FILE and `FILE.pub` must not exist, unless the same `--id` asks again, when the public half is read from FILE. It is filed through the store, signed by the person's key through the agent, and answers `{"id", "scope": {"kind": "organization\|host", "name"}, "fingerprint", "file", "revision", "again", "of": "organization", "pushed": true}`. A file whose key was refused is removed; after `cannot-say` or `failed` it stays, and the same id files it. Every answer and refusal names the `id`, a made one (`bk-<UTC date>-<hex>`) on stderr too, so a retry can name it. Asked again under an id that made another change, a file whose key the place does not hold, or a card that does not stand, is 65 `exists`, the file left as it is; so is a card that stands by a change this backup key did not make. `--use` reads the public half from the file's clear part and replaces the person's key by `NEW_CARD`'s, a card of the same login, signed with the file through `ssh-keygen`, which asks the passphrase; for the organization's place the new key signs the card, so the agent must serve it. It answers `{"id", "scope", "revision", "again", "of", "pushed", "replaced", "by"}`. It works only while the old key is still on the forge. Exit 65 `not-allowed` (the person does not hold the place, or the file's key backs none of it), `exists` (a backup key is there already), `revoked`, `moved-on`, `file-exists`, `no-terminal`, `card-login`, `not-the-backup`, `wrong-passphrase`; 66 `not-found`; 69 `no-key` or `cannot-say`; 78 `no-organization`. A person may hold a backup key whether others hold the place or not. |

## org

[Who may do what](roles.md) in it. The organization of the new structure, from the repository `organization.yml` names; `porelo setup` writes that file. Each
command fetches and walks what is new first. One that cannot reach the store answers the last state it trusted, with
`"stale": true` and exit 0, or `cannot-say` (69) when it has none. No security check rests on a stale state. `--json` answers one
object in `snake_case`, with `revision`; a refusal is one line `{"reason", "words", "revision"}` and `words` on standard error too,
exits 64 `usage`, 65 `not-allowed` or `refused-state`, 66 `not-found`, 69 `cannot-say`, 70 `failed` and 78 `no-organization`.

Once `organization-admins.yml` exists, the roles below apply. Before it exists, owners do the Organization Admin's work;
project and machine admins keep their earlier rights to name admins. An owner who is not listed does none of the Organization
Admin's work. A person holding more than one role may exercise each of them.

| Command | What it does |
|---|---|
| `porelo org [--json]` | Answers the organization: `name`, `kept_in`, `vouched_by`, `revision`, `stale`, `me` (`null` for a person with no card), `owners`, `organization_admins` (a list of logins, or `null` before the list exists), `people`, `projects`, `hosts`, `warnings`, `refusals`, `sole_without_backup`. `me.organization_admin` is a boolean saying whether you do the Organization Admin's work. `vouched` is what the identity source says now, and `cannot-say` is never read as yes. |
| `porelo org card LOGIN [--json]` | Answers a person's card as filed, whole, with `state`. |
| `porelo org host-card HOST [--json]` | Answers a host's card as filed, whole. |
| `porelo org fetch --host HOST --settings FILE --ssh-key FILE --known-hosts FILE --pin FILE [--since REVISION] --json` | Run by a host as root, with no home, vault or person. Fetches with the host's read key alone, against the forge's pinned host key, judges by the same rules as a person's machine with the forge's public key list asked anonymously, and answers `revision`, `host_card`, `admins`, `persons` and `projects`. `refused-state` for anything older than `--since` or a change of the host's files not signed for the pin; `cannot-say` when it cannot be reached, never a stale state. A person's first card the forge cannot say of waits, and the answer stays at the earlier revision. |
| `porelo org signers --state FILE --project PROJECT --json` | Builds from the state `org fetch` answered a project's `signers` and `certifiers`, in allowed signers format: only the keys in force, each until its card ends, and a locked member's not at all. |
| `porelo org add-read-key --settings FILE --key FILE --json` | Adds a host's read-only deploy key to the organization's repository, with the host admin's token on standard input and nowhere else; the token is not kept. Asked again it answers `added: false`. A key the repository holds with write access is `not-allowed`. `revision` is the forge's word for the latest commit, or `null`; nothing is judged by it. |
| `porelo org create ADDRESS [--id ID] [--replace]` | Fills an organization's repository, which you make on the forge first, empty, and makes you its first owner and lists you as its first Organization Admin; this machine then answers from it. A machine holding another organization answers `exists`; `--replace` puts this one in its place, as `setup --replace` does, and only once it is filled. Your key must stay on the forge until a card is handed on, so keep a second owner or a backup key. |
| `porelo org create ADDRESS --check [--replace] [--json]` | Says what founding at this address needs and how each need stands, by the calls the founding makes, and answers 0 whatever it finds. It changes nothing on the forge and nothing here, except that an empty state directory may appear on a machine that had none. Each need in `needs` has a `name`, a `state`, `words` and a `next` step. A state is `ready`, `missing`, `cannot-tell` or `not-known-until-created`, and `ready` in the answer is false only for a `missing` one. The names are `this-machine`, `card`, `key`, `token`, `owner`, `key-listed`, `host-key` (an ssh address only), `repository`, `empty` and `push`. `key-listed` says whether the key is listed to log in or to sign with. Asking may ask for the vault's passphrase at a terminal; a vault left shut makes `token` `cannot-tell`. Whether the forge takes the push is known only by pushing. `followable` is false for an https address, which no machine can follow. |
| `porelo org owner add LOGIN`, `porelo org owner remove LOGIN` | Changes who owns the organization: an owner alone. A write, like every one below: `--base REVISION` refuses a store that moved on, `--id ID` names the change, and asked again it answers `again: true`, `--json` answers `revision`, `id`, `again` and `of`. After 69 or 70 keep the id, since the change may have been made. |
| `porelo org admin add LOGIN`, `porelo org admin remove LOGIN` | An owner adds or removes an Organization Admin. `exists` for one already listed, `not-found` for one not listed, and `not-allowed` for anyone but an owner. Removing the last leaves an empty list; owners no longer do that work until one is listed again. |
| `porelo org person revoke LOGIN`, `porelo org person replace LOGIN --card FILE` | Withdraws a card, by its person or an Organization Admin, which revokes its key so that coming back takes a new one; or puts a new card in place of a lost key's, by an Organization Admin. Before the list exists, an owner does that work. The only owner's card cannot be withdrawn: replace its key from a backup, or name another owner first. |
| `porelo org project add PROJECT --admin LOGIN [--create-repository [--review required\|optional]]`, `porelo org project remove PROJECT`, `porelo org project admin PROJECT LOGIN` | An Organization Admin adds a project with its first admin, who becomes a member; removes it; or names another first admin. With `--create-repository`, where you name yourself, the project's repository is created in the same step. It is two steps and not atomic: if the forge refuses, the project stands, the refusal says so, and the same `--id` finishes it. The answer then adds `address`. |
| `porelo org host add HOST --admin LOGIN`, `porelo org host remove HOST` | An Organization Admin adds a host with its first Machine Admin. An Organization Admin or that host's Machine Admin removes it. Before the list exists, owners add and remove hosts. |
| `porelo org host change HOST [--address A] [--ssh-port P] [--host-key K] [--forge-web-port P] [--forge-git-port P] [--matrix-port P]` | A Machine Admin files the host's card, signed through the agent; fields not named stay as they are. The Organization Admin role grants no right here. `none` as a service's port takes that service off the card. |
| `porelo org host admin add HOST LOGIN`, `porelo org host admin remove HOST LOGIN` | Once the list exists, an Organization Admin adds or removes a host's admins; a Machine Admin cannot. Before it exists, a Machine Admin does this. The last admin stays. A removed admin's backup keys go with them. |
| `porelo org host carry HOST PROJECT`, `porelo org host uncarry HOST PROJECT` | A Machine Admin changes which projects the host carries, in the organization alone. The Organization Admin role grants no right here. The host does the rest at its next follow, so success says nothing of the host yet. `carry` refuses a project no host could follow: `not-opened`, exit 66, for one never opened, and `refused-state`, exit 65, for one whose address stands refused; the words name the command that mends each. |
| `porelo project member add PROJECT LOGIN --role admin\|developer`, `porelo project member remove PROJECT LOGIN`, `porelo project member role PROJECT LOGIN admin\|developer`, `porelo project member lock PROJECT LOGIN`, `porelo project member unlock PROJECT LOGIN` | Once the list exists, a Project Admin adds, removes, changes, locks and unlocks developers. An Organization Admin does those acts on admins and changes roles to or from admin; the Project Admin role grants no right to change admins, and the Organization Admin role grants no right to change developers otherwise. Before the list exists, a project admin changes members and an owner may make or unmake admins. |
| `porelo project init CHECKOUT [--name NAME] [--json] [--project PROJECT --yes --base REVISION] [--id ID] [--central ADDRESS] [--upstream ADDRESS] [--review required\|optional]` | Reads a checkout through installed ecosystem plugins and prints a `project.yml` draft without changing the checkout or saving the proposal. Sources accompany each finding. Disagreements are left out, unknown and unplaced settings remain visible, and credentials are named only by ID with a `vault put` command. A user or token in origin is refused and omitted. A project on a host names its own forge with `--upstream`, where a task reads the repository, and the checkout's address becomes `central`, where the host takes it from. With `--project` alone it also answers the revision to approve; `--yes` and `--base` file one signed commit in that project's own repository. Comments do not survive filing; sources stay in the answer. A stale base answers `moved-on`, an existing name `exists`, and a key that is no admin's `not-allowed`. An `--upstream` on a host's forge, `http://127.0.0.1:<port>/`, proposes `security_class: online` and the project's own address on that forge, as only an online project's gate passes a task's work on; change it in the draft to choose otherwise. |
| `porelo project repository add PROJECT NAME --upstream ADDRESS [--central ADDRESS] [--review required\|optional]`, `porelo project repository change PROJECT NAME [--upstream ADDRESS] [--central ADDRESS] [--review required\|optional]`, `porelo project repository remove PROJECT NAME` | `change` replaces what it names and keeps all else the row says, in one commit, and writes nothing when the row already says that. It cannot take a `central` away. A Project Admin signs a commit to the project's own `project.yml`, which is written anew from what it says, so a comment in it does not survive. The Organization Admin role grants no right here. Its `revision`, and its `--base`, are the project repository's, and the answer says `"of": "project"`. |
| `porelo project sign PROJECT [--yes --base REVISION] [--id ID] [--json]` | A Project Admin signs the project's file anew; the Organization Admin role grants no right here. Used when the project's `project.yml` stands refused: signed by someone who is no admin now, changed by an unsigned commit, or older than this machine accepted. Without `--yes` it changes nothing and answers `project`, `revision`, `of`, `state`, `why`, `signer`, `file`, the file's text, and `blocked`, what a signature would not change: read it first, since anyone with write access may have written it and your signature takes on the whole of it. With `--yes`, `--base` must name the revision you read, or `moved-on`; it adds a line `# signed anew by ID` and signs, and every byte before it stays. A file that reads on this machine is signed too, since another may refuse it as older than it accepted. `refused-state` when the file does not read, names another project, is gone or has no room left for that line, which a signature would not change: an admin mends those and signs that commit. `not-allowed` for anyone but an admin; `usage` for `--yes` without `--base`, or `--base` or `--id` without `--yes`. |
| `porelo project review PROJECT [NAME] required\|optional` | A Project Admin says in the project's own file whether a merge needs another person's approval, in the repository `NAME` or, without one, in the project's own. `required` is what a file saying nothing means. A person working alone says `optional`. `not-found` for a repository the file does not name. |
| `porelo project verify --repository GIT-DIR --signers FILE [--json]` | Run by a host at each follow, with no network, home, vault or person. Reads `project.yml` in a copy of the project's repository, bare or not and owned by anyone, at `main`, and judges its newest change against the keys in `FILE`, the project's admins in allowed signers format, judged by day: a `valid-after` or `valid-before` holding a time of day is usage, 64. An address holding a web user, a password or a query is refused, 65, and not repeated. Answers `project`, `revision`, `project_review`, and `repositories`, each name with `review`, `upstream` and `central`, in the order written. `review` left out is `required`. A file not signed by a signer, or one that does not read, is `refused-state`, exit 65, naming why; a bad `--signers` is `usage`, exit 64. Nothing is written but a scratch directory under the temporary directory. |
| `porelo project create-repository PROJECT [--review required\|optional]` | A Project Admin creates the project's repository on the organization's forge, private and empty, with your token, signs its first `project.yml` and files its address, all in this one command. The Organization Admin role grants no right here. A run stopped anywhere is answered again under the same `--id`. The answer adds `address`. 77 when the vault is shut and may hold the token. |
| `porelo project address PROJECT ADDRESS` | A Project Admin changes the address of a project that is opened, or files again one a lost key filed, which a new reader refuses; `not-opened` otherwise. The Organization Admin role grants no right here. |
| `porelo project show PROJECT [--json]` | Answers a project for its members, `not-allowed` for anyone else, or one registered on this machine alone or `default`, from Porelo's settings, its organization fields `null`. A filed project's `repositories` are not read yet: `state` is `not-opened` or `cannot-say`, with `names` empty. Opened, `repositories` adds `file_signer`, the login of the admin whose signature makes the file read, `null` while it stands refused: an interface warns before removing, locking or demoting that person, as the file then stands refused until another admin runs `project sign`. `repositories.review` and each of `repositories.names[].review` say what the file sets, `required` or `optional`. |

## commit

| Command | What it does |
|---|---|
| `porelo commit verify --repository GIT-DIR --signers FILE --certifiers FILE [--krl FILE] [--certificates FILE] [--json]` | Run from a host forge's pre-receive hook, the hook's `<old> <new> <ref>` lines on standard input (at most 1 MiB), without a home, a vault or the network; every file it reads is named, and it writes nothing but the scratch files ssh-keygen reads - the keys, allowed-signers lines and signatures it is given - in an owner-only directory of their own under `TMPDIR` (else the system's temporary directory), removed before it answers. Each commit new to the repository - `git rev-list <new> --not --all`, merges included, a deleted ref bringing none - must carry an SSH signature in the `git` namespace that holds now, at its arrival, whatever time the commit names: a person's by a key of `--signers` (allowed-signers lines; a `valid-before` or `cert-authority` line signs nothing new), verified as git verifies it by each principal `ssh-keygen -Y find-principals` names for that key's lines in force and answered as the one that verified, so a line naming no principal (`!alice`, `""`) signs nothing, a task's through its certificate, issued by a key of `--certifiers` (the certifiers' `cert-authority` lines) for exactly one principal `task:<project>:<task>` whose task `task start` could make (its container name at most 64 characters) and valid now, or a task's plain key when `--certificates` holds a certificate for that key that would itself be accepted. A certifier's line counts on both paths only while it is in force at the arrival: no `valid-before`, a `valid-after` no later than the arrival, and a `namespaces` pattern, if any, that admits `git` as ssh-keygen matches it (`git,!git` does not). The lines are read as ssh-keygen reads them: a blank is a space or a tab only, a principal list may be quoted and then hold spaces, a `\"` inside a quoted value is a quote within it, and a time may end in `Z` or `UTC` in any case, a time without either read as UTC too; a day up to 31 and a second up to 61 roll over into the next month or minute (`20210229` is March 1), and any other field out of range, or a time not after 1970 began, makes the line unreadable. The signature is the commit's first `gpgsig` header, `gpgsig-sha256` in a SHA-256 repository, and what it covers is the commit without any header whose name starts with `gpgsig`, with its continuation lines, as git verifies it. `--certificates` has one line per certificate, the JSON record `porelo-host-person task --show` answers (its `certificate` read, the rest not believed) or the certificate line alone. `--krl` is checked against every key, certificate and certifier. Anything else - no signature, a GPG or X.509 one, any other key - is refused. The pushed objects are read through the hook's quarantine: `GIT_QUARANTINE_PATH`, `GIT_OBJECT_DIRECTORY` and `GIT_ALTERNATE_OBJECT_DIRECTORIES` are passed to git, and no other variable, configuration or hook of the account. Every commit is judged, so standard error names each refused one on a line of its own, `<commit> <reason>`; a push of more than 10 000 new commits, or input that is not such lines, is refused as a whole with one line `porelo: <reason>`. Exit 0 when every commit is accepted, 65 when any is refused, 64 for wrong use (an empty path too), 66 for a named file that is missing or cannot be read as what it must be (a record line that is no JSON too), 70 when git or ssh-keygen cannot be run, or the scratch files it reads cannot be written. |

`--json` answers `{"checked_at", "accepted", "commits": [{"commit", "ref", "verdict": "accepted"|"refused", "signer",
"reason"}], "reason"}` on standard output: `signer` is `null` when no key was attributed to anybody, else `kind`
(`person` or `task`), `fingerprint` (for a task its own key's), `principal`, `certificate` (`certificate` or `recorded`
for a task, `null` for a person) and `signed_by` (the certifier's fingerprint for a task); a commit's `reason` is `""`
when it is accepted, the top-level `reason` `""` unless the push was refused as a whole. Every text, a certificate's
principal too, is printable (a control or format character becomes `?`), one line, at most 16 KiB.

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
| `porelo talk pass TASK [--project NAME]` | Moves the messages along once, without waiting for the daemon. `TASK` that is no task's container name, or with `--project` no task name, is refused (64) before any mailbox is opened. With `--project`, `TASK` is that project's task, by its name or its container's, never another project's task of the same name, and is refused (66) when a container of that name, or the record a removed task's start left, belongs to another project; what arrives is judged by that project's certifiers, and its conversation, peers and holds apply, whatever the task's name says. A task of `default` believes the person who certified its own key only when that key is certified for that very task. |
| `porelo talk log TASK` | What happened to each message, never its text. |
| `porelo talk verify TASK` | Names the first entry of the message record that does not check out: a message changed after it arrived, or one signed by a key whose task certificate this machine and its host no longer keep. |
| `porelo talk machine` | Prints the name this account goes by on a transport, its login, as a transport that takes it is given it with `--login`, which a project admin lists to let it into a conversation. |
| `porelo talk join PROJECT PERSON [--reset] [--into-vault ENTRY] [--display NAME]` | Makes a person an account in the project's conversation and shows the login once. `--reset` sets a new password. `--into-vault` keeps the person's access token in the vault under `ENTRY` instead and shows no password, for an agent whose output is its transcript; it refuses before making the account when there is no open vault, and its line names the room's id. `--display` is the name the conversation shows for the person, which others address them by; the transport checks it, and one starting with `-` is refused. `PERSON` is a login, `[a-z][a-z0-9-]{0,31}`, or refused before any transport runs, as it is by `talk remove`. |
| `porelo talk members PROJECT` | Lists each person who joined the conversation and their account, or says nobody has joined. |
| `porelo talk remove PROJECT PERSON` | Removes a person through the transport, then drops them from the members list. Says why if their account stays; a failed removal leaves them listed. |
| `porelo talk admit PROJECT MACHINE --into FILE` | Lets another machine into the project's conversation on this machine's server, and writes what it needs - its secret among it - to a new file readable by you only. Refused for an offline project. |
| `porelo talk dismiss PROJECT MACHINE` | Takes another machine out of this machine's conversation server: out of every project's conversation there, not only PROJECT's, and its registration token revoked, so a machine that still holds another project here is cut off from it too. Refused for an offline project. |

A message is never edited. Read it before you release it. A message released despite the filter is
recorded as such, and the receiving peer may refuse it again.

## agents

`porelo agents [--verbose] [--supply-chain] [--json] [--directory=<path>]` lists the installed agents.
`--verbose` adds what each needs to reach; `--supply-chain` shows what its install pins; `--json` answers a
program, as [A host a playbook brings up](hosts.md) says.

## ecosystems

| Command | What it does |
|---|---|
| `porelo ecosystems [--json]` | Lists installed ecosystem plugins with `name`, `path`, and `refusal`, null when usable. The account's plugin wins over a packaged plugin with the same name, even when refused. |

## project

```
porelo project list [--json]
porelo project show PROJECT [--json]
porelo project remove NAME [--dry-run] [--force] [--json]
porelo project clear NAME [--yes] [--dry-run] [--force]
porelo project default [list | add ADDRESS [--name=NAME] | remove NAME]
```

- **`list`** lists the organization's projects you are in, then those of this machine alone, then `default`.
  `--json` answers `{"revision", "stale", "projects"}`, one row each: `project`, `source` (`organization`, `local` or
  `built-in`), `role`, `locked`, `host`, `opened`. On a host, a project the host carries for this person is listed from
  the host's file, with no organization named in the account, also before a first start there: the host names a person's
  projects in a file only that person may read, and does not list them otherwise, so that nobody learns the name of one
  they are not in. An organization named here and never read yet, and out of
  reach, answers 69 `cannot-say`, though a project of this machine alone would start. `Projects()` and `WatchProjects()` carry `lastUsed`: the latest
  recorded task start or message event here, an ISO-8601 instant, or `""` when none is recorded.
  They also name a project the host carries for this person before a first start there, with `carried` true and `file`
  empty, having fetched nothing. `TakeProject(project)` brings its file, when the person acts on that row: it answers `file`,
  or `reason` and `words`; on a host it replaces the person's forge token, so no list calls it.
  A project's conversation carries `messages.waitsOn` (`"vault"` while the vault is shut, or `""` otherwise)
  beside `messages.waits`, and `messages.vault`, the path of the vault file this daemon checked when
  `messages.waitsOn` is `"vault"`, or `""` otherwise; it identifies this account's vault on this machine.
- **`remove`** removes a project of this machine alone: its file or link, mirror, image, build directory and tasks.
  The repository and upstream are untouched. It refuses while work waits at the gate or tasks run. `--force` removes it
  all the same and destroys that work; with `--force` a project that is not here is not an error, and whatever is left
  of it is swept, so it is the one command a cleanup script can run blind after a failure. A project of the organization
  and `default` are refused: 65 `not-allowed`.
- **`clear`** clears the project from this machine in one step: its tasks with their workspaces and
  mailboxes, its mirrors and image, its placed file, its conversation and what this machine kept for it,
  and any deploy keys made by an earlier version from the vault. Without `--yes` it only lists what
  would go. Each earlier key is still forgotten here and named for you to remove at the forge,
  alongside this machine's line in a `machine-signers` an earlier version had the project list, which
  nothing reads any more. See [clear](#clear).
- **`default`** is the project every machine has. It is `guarded`, reaches only
  the agent's provider, refuses blocked connections and has no conversation. `task start` without
  `-p`, in a checkout no project names, adds that checkout's `origin` to it, never with its
  user part: of a URL all before its first `/` up to the last `@` there is left out but a plain ssh user, and all of
  it when `%40` follows that `@`; of `user@host:path`, split as git splits it at the first colon after its host,
  bracketed or not, a user that is no plain name, or without a user all up to the last `@` that a host and a colon
  follow; an origin without a credential is kept as given. It cannot be removed, and its settings cannot be changed: for other
  settings, make a project of your own.
- Approved work in `default` reaches `origin` with your own ssh agent or key, or a token stored with
  `porelo credentials`; never handed into the task. The daemon has no ssh agent, so through it only a
  key without a passphrase works. Trust an ssh host first with `porelo credentials trust-host`; until then the
  task does not start.

## providers

`porelo providers` lists the model providers declared here (by agent packages or files under
`$XDG_DATA_HOME/porelo/providers`), which agents use each, and what the vault holds for it. It never
shows a value, and says when the vault is locked.

## setup

```
porelo setup [--hooks-only] [--uninstall]
porelo setup --organization ADDRESS [--replace] [--json]
```

Makes this account ready. Each step runs only if needed, so running it again is safe:

- registers the OCI hooks in your podman configuration (a task start does this too);
- starts the daemon (`systemctl --user enable --now porelod`), or says what to run instead;
- creates the vault, **only at a terminal**, since a person must type the passphrase.

It ends by saying whether the account is ready. `--hooks-only` registers only the hooks;
`--uninstall` removes them.

With `--organization`, it points this machine at an organization once that answers. If your
ssh-agent lists the key of one person the forge vouches for, it keeps that person's card here, so
this machine can change things too; it only asks the agent to list, and signs nothing. Without such
a key the machine only reads. Run it again after your key changed on another machine.

## events

`porelo events [--since INSTANT]` shows this account's event log: every approval and rejection, every connection
allowed or denied while a task ran and every change of a task's clearance mode, every file handed in or taken back,
the credentials a start gave a task beyond its agent's own, every project file applied, and every stop of all tasks
at once. `porelo events --verify` checks the log's chain and every line against the hashes the user's systemd
journal holds, and exits 1 when a line was changed or removed afterwards; see
[the event log](security.md#the-event-log).

## doctor

`porelo doctor` reports paths, the daemon's hardening and whether this machine can run a task. Each
dependency is checked by name: podman, the hook registration, podman's rootless network, dnsmasq's
`nftset` support, `nft`, `git`, `nsenter`, the SELinux policy and the kernel keyring, beside the vault,
the daemon and each project, and what `--keepassxc` needs with an answering ssh-agent, none of which
makes a machine not ready. Each line has one of four states:

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
         hidden by $XDG_DATA_HOME/porelo/agents/porelo-agent-<name>
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

## tighten

```
porelo tighten
```

Every program of Porelo starts by making what it and its children create readable by this account alone, whatever the
umask. A machine that ran under an older Porelo still has its mirrors, registry and review checkouts at the old modes:
`porelo doctor` names them under `private files` and exits 1, though the machine can still run tasks, and this takes from each what anyone else had of it and says
what changed. Exit 0 when nothing is left, 1 when a path could not be changed, which that path's line names.

What a task reads through a mount (its mailbox's `box`, its guide) and an agent's tree staged for a build are open on
purpose, inside directories of this account's alone. They are neither named nor changed: a running task would lose them.
A file with a second name, as an object hard-linked from a person's own clone, gets an inode of its own first, so their
clone keeps its mode. A review shared with someone is a copy a person makes.

## prune

```
porelo prune [--yes] [--including-work]
```

Lists what nothing owns any more, and removes it only with `--yes`: projects no start placed whose project
file is gone, leftovers of tasks whose container is gone (after 15 minutes unchanged), and images or
records named after projects that no longer exist. Running tasks and placed projects are never
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
`machine-signers`, a file an earlier version had a project list and nothing reads any more. Each
earlier key is still forgotten here and named once. Removing it at the forge
needs the forge's administration right, which your token for git does not need; changing
`machine-signers` needs your signed commit. Clearing attempts neither. It refuses while work waits
at the gate or tasks run; `--force` clears those too. Run again, it says there is nothing to clear.
With the vault locked, the earlier keys it cannot read are said to be unreadable, never answered as
none.

A deploy key made before 0.5.0 carries the project's earlier name in its title at its forge. Porelo never lists such a
key: it is yours to remove.

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

A helper and every process it started get SIGTERM and up to 2 s to end. `task remove --force` then sends SIGKILL to
whatever of them still runs and waits up to 2 s more; without `--force` nothing is killed. A process that has exited
and only waits for its parent to reap it (a zombie) counts as stopped.

## keep

`porelo keep <dir>` copies the tasks' usage records, `usage/*.jsonl` in the state directory and nothing else, into
`<dir>/usage/`, up to each record's last complete line. It writes the copy into the directory it is given; taking it
elsewhere and when are the caller's. `porelo keep --restore <dir>` puts them back before a task starts, and is refused
with exit 69 while a task runs or when the runtime cannot say.

A longer record is never shortened, in either direction, and only complete lines are copied. Once a removed task's
record holds all of what was kept under the task's own name, that copy is dropped, so a restore gives no request twice. Two records that differ before the shorter one ends are named,
both stay as they are, and the others are still copied. Exit 0, or 70 naming what could not be read or written.

## daemon

`porelo daemon connect` bridges standard input and output to the daemon's socket, for use as an ssh
`ProxyCommand`. The daemon is `porelod`; see [the daemon](running.md#the-daemon).

## completion

```
porelo completion bash > $XDG_DATA_HOME/bash-completion/completions/porelo
porelo completion zsh  > <a-folder-in-your-fpath>/_porelo     # in $fpath before compinit
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
  (holding `project.yml`) is named after the project, and a task works there only in a project that lists no others.
- A task name is lowercase letters, digits and hyphens, starts and ends with a letter or digit, is not
  only digits, is neither `people` nor `person` (the names a message gives the room's people and a person's own
  note), does not begin with `porelo-`, the beginning of a container's name, and `porelo-<project>-<task>` is at
  most 64 characters. A container name such as
  `porelo-myproject-backend` is taken as the task `backend`.
