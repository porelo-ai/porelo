# Decisions

The choices Sokar is built on, each with why it holds and, for an accepted risk, the exposure, why it stays and what
would change the answer.

| Subject | What holds | Link |
|---|---|---|
| **Tasks and containers** | | |
| A task is the container whose id Sokar recorded | never by name or label; a container with no record is acted on by nothing | [entry](#a-task-is-the-container-whose-id-sokar-recorded) |
| Waiting for a person is never inferred from silence | working and idle are observed from output; waiting only where something declares it; no status channel out of the container | [entry](#waiting-for-a-person-is-never-inferred-from-silence) |
| Nothing comes back by itself after a reboot | only `start` brings a task back; no surprise restarts, no vault prompt at boot | [entry](#nothing-comes-back-by-itself-after-a-reboot) |
| Only the last session is recorded, and a failed continuation is forgotten | a failed continuation is forgotten and the next start is fresh | [entry](#only-the-last-session-is-recorded-and-a-failed-continuation-is-forgotten) |
| A session id is an identifier, not a credential | it may go on a command line; it authorizes nothing | [entry](#a-session-id-is-an-identifier-not-a-credential) |
| An image's readiness is judged against the project file alone | `READY` ignores which agent the image was built for; a task with another agent rebuilds only those layers | [entry](#an-images-readiness-is-judged-against-the-project-file-alone) |
| A file handed to a task goes to /sokar/files, and only in | a root-owned directory of its own in every task; a base image with `/sokar` is refused; nothing comes out | [entry](#a-file-handed-to-a-task-goes-to-sokarfiles-and-only-in) |
| A task learns of handed-in files from Sokar, not from its prompt | one guide to all of Sokar in the task, and a line at the prompt when a file arrives | [entry](#a-task-learns-of-handed-in-files-from-sokar-not-from-its-prompt) |
| Every class pushes through its gate | no key, socket or route to the upstream in any container; an online task's gate passes its own branch on at once | [entry](#every-class-pushes-through-its-gate) |
| A build is read from the host, never by the task | an online task is handed its builds' verdicts and logs; no forge token, route or verb in it | [entry](#a-build-is-read-from-the-host-never-by-the-task) |
| Build readers are packages behind a versioned API | `sokar-build-api` and `org.fuin.sokar.Build1`; each forge in a repository of its own; nothing in `sokar` names one | [entry](#build-readers-are-packages-behind-a-versioned-api) |
| **The machine and the daemon** | | |
| A weaker machine is reported, not refused | `DEGRADED` exits zero and starts tasks; only `MISSING` fails | [entry](#a-weaker-machine-is-reported-not-refused) |
| Doctor diagnoses and never repairs | each check names the next action; nothing runs a fix as root | [entry](#doctor-diagnoses-and-never-repairs) |
| The daemon restarts itself after an update; the package does not | it sees its binary replaced and asks the account's systemd; tasks keep running | [entry](#the-daemon-restarts-itself-after-an-update-the-package-does-not) |
| **Egress, the shield and the review** | | |
| Port 53 to the upstream is the resolver's alone | matched by the resolver's uid 0 in the namespace; the agent's direct query is dropped and logged | [entry](#port-53-to-the-upstream-is-the-resolvers-alone) |
| Sokar does not claim to detect a prompt injection | it bounds what a convinced agent is worth; each guard says control or accident-catcher | [entry](#sokar-does-not-claim-to-detect-a-prompt-injection) |
| The provider channel is documented, not bounded and not alerted on | an open path by construction; stated, recorded, no alert | [entry](#the-provider-channel-is-documented-not-bounded-and-not-alerted-on) |
| What is dangerous by kind is a fixed list that ships with Sokar | never in the repository it judges, never per project | [entry](#what-is-dangerous-by-kind-is-a-fixed-list-that-ships-with-sokar) |
| The instruction is shown, never compared | the review shows what was asked and matches no file against it | [entry](#the-instruction-is-shown-never-compared) |
| **The vault and credentials** | | |
| A provider is a declaration; an agent is a package | providers are YAML data paired with agents by dialect; the vault is keyed by provider | [entry](#a-provider-is-a-declaration-an-agent-is-a-package) |
| A service that is not a model provider is a destination | its own kind and file, not a widened provider; a provider resolves as one too | [entry](#a-service-that-is-not-a-model-provider-is-a-destination) |
| The project declares credentials, and the run adds | a run adds but never withdraws or redirects; never in an agent's definition | [entry](#the-project-declares-credentials-and-the-run-adds) |
| The route is picked by the token | one broker, a route per credential; the token, not the path, decides where a key goes | [entry](#the-route-is-picked-by-the-token) |
| Where a key goes is declared, never inferred | `auth_header` or `auth_query` per service; only a consumer whose endpoint can be configured; no TLS interception | [entry](#where-a-key-goes-is-declared-never-inferred) |
| One credential type reaches one variable | `token_env` maps a type to one variable; changed in the reader when an agent needs two | [entry](#one-credential-type-reaches-one-variable) |
| A bought token is per task and kept in memory | never on disk, never shared; costs one exchange per task per hour | [entry](#a-bought-token-is-per-task-and-kept-in-memory) |
| A purchase that fails at start is a missing key | unattended and agent runs are refused before a container exists; a shell warns | [entry](#a-purchase-that-fails-at-start-is-a-missing-key) |
| Sokar does not replace a stored key at its provider | no `vault rotate`; per-provider code is what the split between agents and providers avoids | [entry](#sokar-does-not-replace-a-stored-key-at-its-provider) |
| A task's own tokens are kept in the vault | gate and phantom token under `task/<container>/`; a start after a reboot needs the vault | [entry](#a-tasks-own-tokens-are-kept-in-the-vault) |
| Clearing the vault is `clear`, not `delete` | `vault clear` lists, then acts on `--yes`, like the other clears; `remove` is for one entry | [entry](#clearing-the-vault-is-clear-not-delete) |
| A cached passphrase is reported gone only when it is gone | `vault lock` answers cleared, nothing cached or unknown, and exits non-zero on unknown | [entry](#a-cached-passphrase-is-reported-gone-only-when-it-is-gone) |
| The passphrase cache does not outlive a login, and a foreign keyring reads as empty | one mechanism, the kernel keyring; another user namespace would read it as empty | [entry](#the-passphrase-cache-does-not-outlive-a-login-and-a-foreign-keyring-reads-as-empty) |
| Secrets in the daemon's memory stay strings | accepted: whoever reads that memory reads the vault file and the keyring too | [entry](#secrets-in-the-daemons-memory-stay-strings) |
| **Projects and following** | | |
| A project file describes constraints, not instructions | no prompt or standing instruction belongs in `project.yml` | [entry](#a-project-file-describes-constraints-not-instructions) |
| A project exists by being followed | Sokar never writes a project file; follow is the only way in and does the whole check | [entry](#a-project-exists-by-being-followed) |
| A project is named, never pointed at | every command and call takes a project name; no path form exists | [entry](#a-project-is-named-never-pointed-at) |
| Configuration coming in is checked by its signature, as work going out is checked by a person | only commits signed by a key pinned out of band apply; git verifies; a rewrite waits for a person | [entry](#configuration-coming-in-is-checked-by-its-signature-as-work-going-out-is-checked-by-a-person) |
| A signing key is never read from the repository it verifies | the anchor comes from the person; a fingerprint only selects a key the person named | [entry](#a-signing-key-is-never-read-from-the-repository-it-verifies) |
| A project's signing key is handed on through the project file | a signers change counts only signed by a key in force before it; machine keys never change configuration | [entry](#a-projects-signing-key-is-handed-on-through-the-project-file) |
| A project may be followed without a signing key | `--unverified` is allowed per project, as a loudly reported state, not an error | [entry](#a-project-may-be-followed-without-a-signing-key) |
| Where a followed project's file comes from | only the verified clone; nothing in force means no task, never a fallback to a local file | [entry](#where-a-followed-projects-file-comes-from) |
| Clearing names what only a person's credentials can remove, and never attempts it | forge deploy keys and `machine-signers` lines are answered, not removed; the vault stays | [entry](#clearing-names-what-only-a-persons-credentials-can-remove-and-never-attempts-it) |
| **Messages** | | |
| A task holds no key and sees no address | one mailbox in the container; a host-side signing key, distinct from the login key | [entry](#a-task-holds-no-key-and-sees-no-address) |
| No model decides what a message may contain | rules accept or refuse, a person holds; a classifier may only ever hold | [entry](#no-model-decides-what-a-message-may-contain) |
| A project's message rules are set once, in its project file | by class (project, room, others), defaults allow, allow, deny; the host keeps only holding a peer | [entry](#a-projects-message-rules-are-set-once-in-its-project-file) |
| A person's words in the room reach every agent, unfiltered | everything said reaches every task; only the agents' outgoing messages are filtered; an answer goes where it was asked | [entry](#a-persons-words-in-the-room-reach-every-agent-unfiltered) |
| The mailbox guide is the same for every task of one Sokar version | built from enforced constants only; whom a task reaches is in `agent-card.json` | [entry](#the-mailbox-guide-is-the-same-for-every-task-of-one-sokar-version) |
| Each host keeps its own record; a shared ordered record is given up | hash-chained per host; no shared ordered record across machines | [entry](#each-host-keeps-its-own-record-a-shared-ordered-record-is-given-up) |
| Refused originals live as long as the task | owner-only, no timer; the exposure is accepted while tasks are short-lived | [entry](#refused-originals-live-as-long-as-the-task) |
| A project's conversation is one room, and the room is the boundary | tasks that must not read each other belong in two projects | [entry](#a-projects-conversation-is-one-room-and-the-room-is-the-boundary) |
| Membership is not authorization; the host acts and the signature decides | the host acts for every task; delivery needs a key of that project's peer and a task of that project | [entry](#membership-is-not-authorization-the-host-acts-and-the-signature-decides) |
| One account per task, deactivated with it | made at start, deactivated at removal; the signature, not the account, carries authorship | [entry](#one-account-per-task-deactivated-with-it) |
| Sokar knows transports; the transport knows Matrix | a generic lifecycle; no Matrix code or settings parsing in `sokar` | [entry](#sokar-knows-transports-the-transport-knows-matrix) |
| The homeserver is one endpoint Sokar names, and offline means loopback | the declared URL or the account's own; offline reaches loopback only | [entry](#the-homeserver-is-one-endpoint-sokar-names-and-offline-means-loopback) |
| A person joins by an account made for them | the login is printed once, no registration stays open, delivered only once their key is listed | [entry](#a-person-joins-by-an-account-made-for-them) |
| A machine on a shared server is admitted by a person | Sokar admits nobody; a machine not yet let in starts no task | [entry](#a-machine-on-a-shared-server-is-admitted-by-a-person) |
| Only a transport a peer names is polled | a transport no peer names is never polled; a failure is said once | [entry](#only-a-transport-a-peer-names-is-polled) |
| **The build and packages** | | |
| Sokar is split into modules by area, not into repositories | `app-<area>` modules with enforced dependencies; one build, one package, one contract on the wire | [entry](#sokar-is-split-into-modules-by-area-not-into-repositories) |
| The build tooling lives in its own repository; the acceptance kit stays | `sokar-buildtools` from Central at `sokar.buildtools.version`; the kit changes with the product | [entry](#the-build-tooling-lives-in-its-own-repository-the-acceptance-kit-stays) |
| Which machine types a leg rents | snapshots at the smallest disk; `cpx42` first for speed, three fallbacks; nothing enforces it | [entry](#which-machine-types-a-leg-rents) |
| **The documentation** | | |
| The issue index stays one table per section | a conflict there is resolved by keeping both lines; split, nobody could read it whole | [entry](#the-issue-index-stays-one-table-per-section) |

## A task is the container whose id Sokar recorded

Sokar records the id podman returns at create, in the task's state directory, before the container first starts, and
treats a container as a task only while its live id is that one. Every act on a task - and every context the CLI or
the daemon builds - goes by that id, and the OCI hooks compare the runtime's id with the record beside the sidecar
before giving a container a resolver and rules, or reaping helpers after it stops. Names and podman labels do not
decide it, since anything that runs as the account can give a container both: a container named like a task could
otherwise have tokens restored, the vault relay and the gate started in its namespace, its guide written, or another
task's helpers reaped. A container with no record is not a task, and nothing is adopted by name, since an adoption by
name is exactly the hole the record closes.

## A file handed to a task goes to /sokar/files, and only in

A file handed to a running task appears whole in `/sokar/files`, a directory that holds nothing else in every task
from its start, owned by root and readable by the agent, which cannot change or remove it. Not `/workspace`, where
the agent would commit it and the gate carry it out; not `/run/sokar/`, which is Sokar's machinery and holds the
credential socket. `/sokar` is Sokar's root: a base image that already has it is refused when the task's image is
built - which every preparation and every start of a changed image does - because a mixed directory would make
"everything in it was handed in" quietly false. The same holds in every security class, since it carries nothing
across a boundary that keeps tasks apart. Nothing takes a file out of a task: content leaving is what the gate is
for. Every hand-in is written down - who, when, which run, the name, the size and the sha256 - in the state
directory, so it outlives the task, and never the content, which is often the thing kept out of git on purpose.
The record says what Sokar did: a file copied in with `podman cp` is not in it.

## A task learns of handed-in files from Sokar, not from its prompt

Left to the person's prompt, an agent pushed and never looked at its red build unless somebody remembered to say so
(2026-10-07). Agent definitions have since come to declare both ways a running agent is told
something - `instructions`, the file it takes into its standing instructions, and `at_rest`, the screen at which a
line may be typed - and the mailbox used them first. Files and builds now use the same two:

- **One guide to all of Sokar in the task**, `/run/sokar/guide/README.md`: `/sokar/files` and what arrives there,
  the builds of a push among it, and the mailbox's text where the task has one - one file, since a definition has
  one `{file}`. The same for every task of one Sokar version, as the mailbox's guide is, so a provider's cache of
  the prompt holds. It is mounted from a directory of its own made with the task: a task made before has no such
  mount, and keeps its mailbox's guide.
- **A line at the prompt** when a file arrives, a verdict among them, and the agent rests there: Sokar's own,
  naming the files, typed under the same lock, the same quiet and the same `waiting` rule as a message's, and tried
  at every pass until it lands.

`/sokar/files` is still the same path in every task, so a prompt may still name it. **What would change the
answer:** an agent that takes its instructions some other way than a file, or tells a person's question from its
rest by nothing on its screen.


## Every class pushes through its gate

An `online` task once cloned from its upstream and pushed there itself, through an ssh-agent socket that answered
the forge with the real key. The key never left the vault, but for as long as the task ran the agent could use it for
everything the key may do: push to `main`, push with force, set tags, delete branches, and with a person's key reach
every other repository it opens. Holding a key and being able to use it unchecked differ little. So every class works
against the gate on the host, and the container holds no key, no socket of one and no route to the upstream. The
classes differ in what the gate does with a push: `guarded` keeps it until a person approves it, `online` passes the
task's own branch on at once, as `sokar/<task>`, with the key the host lends, and fails the agent's push when the
forge refuses. Anything but that branch is refused at the gate; a push with force to it goes through, since an agent
that rebases needs it. Before the agent fetches, the gate fetches from the upstream, at most once every few seconds,
so an agent fetching in a loop cannot make the host flood the forge with a person's key. A gate that reads the
upstream from the host can also follow a repository on the same machine, which no container could reach.

## A build is read from the host, never by the task

An `online` task learns what the build of its own push did without reaching the forge: a helper on the host follows
every commit it pushes, asks the forge with a token from the vault, and hands the verdict and the jobs' logs into
`/sokar/files`. The task asks for nothing and holds nothing it could ask with. A route through the broker with a
phantom token would have let the agent use the forge's own tools, but **a forge token cannot be scoped to the
question**: one that reads a repository's builds reads its code and issues too, and an agent that holds a verb uses
it - opening an issue, closing a pull request - with none of it passing the gate. So the surface is a verdict and
logs, and nothing else. It is `online` only: in `guarded` the forge builds nothing before a person approves at the
gate, and waiting for that is waiting for a person, not a build. A log is text an outsider can influence, as is
everything a task reads; delivering it adds no new kind of input, and Sokar keeps no copy of it - only which builds it
delivered, with each log's size and hash. **The exposure:** one token's rate limit is shared by every task that reads
with it; the helper waits a limit out and says so in the verdict rather than reading as a failed build.

## Build readers are packages behind a versioned API

What a forge does differently in kind - GitHub sends a job's log behind a redirect to storage, another forge as an
archive or a trace per step - is code, and that code is not Sokar's. A forge is read by a **build reader**: an
executable built on the published `sokar-build-api`, speaking `org.fuin.sokar.Build1` over a socket with a protocol
version Sokar checks, installed under `/usr/libexec/sokar/builds/<forge>` and found at run time, as an agent is. Each
lives in a repository of its own - `sokar-build-github` first - and nothing in `sokar` names a forge; the stub reader
the tests use is the only one in this tree. The contract fixes what every reader hands over, whatever its forge:
the verdict across a commit's runs, every job once it is final, and a job's log as plain text, its last 64 KiB.
**The exposure:** a reader runs on the host with the forge token in each call; it is a package the operator installs,
and trusted as one.
## Waiting for a person is never inferred from silence

A task's activity is observed from outside where it can be, and declared where it cannot. Working and idle are read
from the output the host already writes for every task (the agent's log grows continuously while it works), with no
knowledge of any agent. Waiting for a person is said only by whatever is asking, such as a clearance prompt the
machine itself raised, or read from the agent's screen against rules the agent's package declares; where an agent
declares none, the answer is "this agent cannot tell us", never "not waiting". A timeout is not used: a quiet task
can be finished, stuck or rate-limited, and guessing "waiting" is wrong in the direction that costs most. No channel
from inside the container carries a status to the host either: that would be a way out beside the vault socket and
the gate, written into by the agent, and a status value does not justify it. An agent that
stops at a first-run dialog inside a task is treated as a defect in that agent's package, caught by each agent
repository's acceptance step that the agent reaches work without being asked anything.

## Nothing comes back by itself after a reboot

A task that a reboot took down comes back only through `start` - at the terminal or over the socket - never on its
own; `task list` shows that the machine took it down. On a shared machine nothing restarts by surprise, and nobody is
prompted for the vault at boot with nobody there to answer.

## Only the last session is recorded, and a failed continuation is forgotten

A task records only the session its next start continues. A run asked to continue a session that failed and named no
session of its own has the recorded one forgotten, so the next start is fresh and says so; nothing runs twice without
being asked. `remove`, including `remove --rescue`, forgets it: the transcript was inside a container that no longer
exists.

## A session id is an identifier, not a credential

The id of an agent's session may be passed on a command line. It names a transcript on this machine and authorizes
nothing; what it leaks - that a task exists and roughly when it started - the container name leaks already. It is
recorded here so the rule keeping secrets off the command line is not re-argued against it.

## An image's readiness is judged against the project file alone

Whether a project's task image is ready (`ABSENT`, `READY`, `STALE` or `UNKNOWN`) is computed from a digest of the
project file's image-relevant fields, carried as a label on the image. **The exposure:** the image also contains the
agent's layers, so an image is really per project and agent, and `READY` overstates slightly when the next task
names a different agent than the image was built for. **Why it stays:** that task rebuilds the agent's layers
itself, which is correct and fast, so nothing runs with the wrong tooling. **What would change the answer:** an
interface needing to say "ready, built for claude", which would add the agent to the image's labels and to the
comparison.

## A weaker machine is reported, not refused

`sokar doctor` reports a machine that works less well than it should as `DEGRADED`, names what it costs and exits
zero, and nothing refuses to start a task on it. **The exposure:** on a machine using slirp4netns rather than pasta,
a task's gate binds every interface and is reachable from this machine's network; only the per-task token keeps it
shut. **Why it stays:** the machine does run tasks, and refusing it is a policy decision rather than something a
probe can settle. **What would change the answer:** a project or an operator needing to say "not without pasta",
which would make it a refusal at `task start` declared by the project or the machine.

## Doctor diagnoses and never repairs

Neither `sokar doctor` nor the daemon's `Doctor` offers to run a fix. Installing packages or writing under `/etc` is
root on the node, which is exactly the power Sokar is built around not having. A repair over the daemon's socket
could not reach the case most worth fixing anyway: a machine that is not ready usually has no daemon to ask. Every
failed check names the next action instead, for a person to take.

## The daemon restarts itself after an update; the package does not

The package starts nothing when it installs: a daemon is each account's own, and a root package script starting
another account's user service would be wrong about the session it runs in. So the daemon watches its own
executable; seen replaced twice (an update replaces the binary, then the unit file a moment later), it asks the
account's own systemd for `daemon-reload` and `restart --no-block` of the unit it runs in, read from its own control
group. Running tasks are not touched. A binary removed with nothing in its place is an uninstall, which nothing
restarts into, and a daemon started by hand is not restarted but says once that it was replaced; `sokar doctor`
names a daemon running another version than the command.

## Port 53 to the upstream is the resolver's alone

The resolver runs inside the task's network namespace, so its own upstream queries pass the same output chain as the
agent's traffic. The two port-53 rules to the upstream resolvers therefore match the socket's owner as well as the
destination (`meta skuid 0`): the resolver runs as the namespace's root, the agent as its own user, with no
capabilities and no new privileges. Matching the destination alone would let every process in the task ask a public
resolver directly, carrying data out in query names with no drop and no log line, and confirming whether an
undeclared host exists. Other ways were weighed and not taken: matching the resolver's cgroup (where a hook-started
resolver lands under rootless podman is not known), pinning its source port below 1024 (covers UDP only), and moving
the upstream query out of the namespace (the largest change, and the resolver would no longer be self-contained in
the task). **The exposure:** a process that runs as the namespace's root - a command an operator runs with
`podman exec --user root` - can still ask the upstream directly, and a name under a declared domain is still
forwarded upstream, so a declared domain whose authoritative server the agent controls can carry data in the names
asked; the resolver logs those. **What would change the answer:** anything the agent can reach running as the
namespace's root.

## Sokar does not claim to detect a prompt injection

No guard in Sokar is presented as detecting an agent that was convinced by something it read: there is no filter
that separates instructions from content in a text meant for something that follows instructions, and a confident
answer that is sometimes wrong is worse than an honest "this system does not do that". What Sokar bounds instead is
what a convinced agent is worth, and every guard says whether it is a control or an accident-catcher.

## The provider channel is documented, not bounded and not alerted on

Every task has a working route to its model provider, carrying request bodies up to the broker's limit, and anything
the agent knows can be written into a prompt. No egress set closes it, because closing it closes the task. It is
stated as an open path in the operator documentation and the reach document, with the record of what a task sent.
There is no alert: a false alarm on a working task is worse than none. **What would change the answer:** a way to
bound what a request says rather than whom it goes to.

## What is dangerous by kind is a fixed list that ships with Sokar

The review's list of files dangerous by kind - CI definitions, build scripts, dependency manifests and lockfiles,
what runs on checkout, a file made executable, a symbolic link - is Sokar's and fixed. A list inside the repository
could be edited by the very change it is meant to catch; a list per project outside it is one more file nobody
maintains.

## The instruction is shown, never compared

The review shows what the task was asked at the top - "none" when it was started without one, "not known" when the
task is gone - and matches nothing against it. A signal present half the time, and a guess when it is, would be
leaned on wrongly.

## A provider is a declaration; an agent is a package

Knowledge is split by owner. How a session starts, what a fresh container must be told, which command line runs a
prompt and how its output is read belong to the **agent**, which is code and ships as a package. The upstream
endpoint, the auth header and prefix, the dialects served and how a credential is obtained and renewed belong to the
**provider**, because they are the same for every agent reaching it; a provider is therefore a YAML file found by a
directory scan, with nothing to execute and no package of its own. Whether an endpoint can be redirected belongs to
both: the provider must offer it and the agent must honor it, so an agent declares the shape of endpoint it can use
(a socket or a URL) and Sokar satisfies it. Agents and providers are paired by dialect, never by name. The vault is
keyed by provider, since a credential belongs to whoever the account is with; storing it per agent would keep the
same key twice under names that do not say what it is for.

## A service that is not a model provider is a destination

A forge, a search endpoint or an MCP server is a `destination` - its own kind and its own file, saying where the
service is and where its key goes - not a widened provider definition. A provider is which models it serves and in
which wire format; a service has no wire format in that sense, and a field meaningless for half its instances is one
nobody can trust anywhere. A provider resolves as a destination too and keeps its own file, so nothing that reads
providers changes.

## The project declares credentials, and the run adds

`project.yml` names the credentials every task of the project gets; a repeatable `--credential` on the run adds
more, and cannot withdraw or redirect one the project declared. That a project's credential is usable by anyone who
can start a task in it is stated in the documentation rather than left to be discovered. The agent's definition
never names one: a credential belongs to the project, not to the agent.

## The route is picked by the token

One broker serves every credential of a task, with a route per credential. Each credential gets its own task-scoped
token, and the token - never the request's path - decides which credential is attached and which destination the
request goes to, so a key held for one service cannot be sent to another at all. In the container each appears as
`SOKAR_TOKEN_<NAME>` and `SOKAR_URL_<NAME>`, beside the single provider's variables, so a task with one credential
changes nothing.

## Where a key goes is declared, never inferred

A stored key is presented where its service expects it, and that place is the service's fact: a header whose name
the provider or destination declares (`auth_header`, `auth_prefix`), or a query parameter (`auth_query`). Sokar
guesses neither, since a wrong header name fails like a wrong key and is the first thing such integrations get
wrong. A consumer is supported only when its endpoint can be configured, so it can be pointed at the proxy: a tool
with its host compiled in is not, because reaching it would mean intercepting TLS with a certificate authority in the
image, which Sokar does not do. A key carried in the request body is not rewritten.

## One credential type reaches one variable

An agent definition's `token_env` maps a credential type to a single variable, so an agent that reads the same token
from two variables cannot be described. **Why it stays:** no supported agent needs it. **What would change the
answer:** an agent that does; the fix then goes in the reader of `token_env`, not in each definition.

## A bought token is per task and kept in memory

A token the broker buys with a stored secret (`client_credentials`) is bought per task, held in memory, and never
written to disk or shared between tasks. A live token resting outside the vault, and every task depending on one
cache, cost more than one exchange per task per hour. **What would change the answer:** a service whose rate limit
makes per-task exchanges fail.

## A purchase that fails at start is a missing key

The launch tries the purchase once before anything exists. An unattended or agent run is refused, as for a
credential the vault does not hold; a shell task warns and starts, since a person is there to fix it.

## Sokar does not replace a stored key at its provider

There is no `vault rotate` that issues a new key at a provider and revokes the old one. For a bought credential
every exchange already rotates the token the broker attaches; issuing and revoking a stored key through each
provider's own API would be per-provider code in Sokar, which the split between agents and providers exists to
avoid. **What would change the answer:** a provider-neutral way to issue and revoke keys.

## A task's own tokens are kept in the vault

A task's gate token and its phantom provider token are kept in the vault, hidden under `task/<container>/`:
`vault list`, credential choices and provider listings leave them out, they go when the task is removed, and entries
of tasks that no longer exist are pruned whenever the vault is next opened for a task. The cost is that the first
start after a reboot needs the vault unlocked even for a task with no credential of its own.

## Clearing the vault is `clear`, not `delete`

The vault is removed by `sokar vault clear`, in the same shape as `sokar clear` and `sokar project clear`: it lists
what would go and acts only on `--yes`. `remove` stays the verb for one entry (`vault remove NAME`). A fourth verb
for the same act would be one more to learn for nothing it says differently.

## A cached passphrase is reported gone only when it is gone

Clearing the cached vault passphrase has three outcomes, not two: cleared, nothing cached, and unknown. Only "no such
key", "expired" and "revoked" count as absence, since a retry cannot change any of them and there is nothing for an
operator to act on; every other keyring failure, and a key that was found and could not be unlinked, is unknown, and
`vault lock` then exits non-zero so a script does not believe it locked anything. The read path keeps one verdict
for every failure: it falls through to asking for the passphrase and says nothing, because a cache that could not
answer costs nothing when the answer is being asked for anyway. The two paths share a search and not a verdict,
because on the forget path a failure rendered as absence is a false security claim.

## The passphrase cache does not outlive a login, and a foreign keyring reads as empty

The passphrase is cached in one place, the kernel keyring, with a prompt behind it; there is no chain of further
stores (a credentials service, a passphrase command). **The exposure:** a deployment that needs the cache to outlive
a login has no answer. And the kernel user keyring is per user namespace, so a process in a different one finds an
empty keyring and reports "nothing cached" while the passphrase is still cached in the operator's: the false
statement above, by a route the three outcomes do not catch. **Why it stays:** every reader is the CLI or the daemon
running as the operator, in the operator's namespace; the hooks do not touch the vault, and the shipped
`sokard.service` does not change the namespace. **What would change the answer:** a unit file with
`PrivateUsers=yes`, or a reader under a container runtime, which would need the passphrase carried by a path rather
than the keyring; or a deployment that needs the cache across logins, which would make a chain of stores worth its
explanation.

## Secrets in the daemon's memory stay strings

A credential read from the vault is a `String` in the daemon and the broker: `VaultEntry.value()`, every caller that
reads it, and the HTTP header the broker sends. It is not turned into bytes that could be wiped. The vault document
and the passphrase are the exceptions, held in buffers that are overwritten when the work is done. **The exposure:**
a `String` cannot be cleared; it lives until the collector reaches it, and a heap dump or a core dump shows it. Even
bytes would not be erased for certain, since a moving collector leaves the old copy behind. **Why it stays:** reading
another process's memory takes the same uid that already reads the vault file and the passphrase in the kernel
keyring, so wiping the copies in memory keeps nobody out who is not in already. A core dump is the copy that is
written somewhere without anybody asking; core dumps of the processes that hold a secret are turned off with
`prctl(PR_SET_DUMPABLE, 0)` (`ProcessHardening.disableDumping`). That call has no caller yet, and neither
`sokard.service` nor the code sets a core size limit, so until it is made a core dump of those processes is
possible. **What would change the answer:** a process that holds secrets running under a uid of its own, so that
reading its memory is no longer the same as reading the vault; a core dump that cannot be turned off; or an HTTP
client that takes a header as bytes, which would make the change much cheaper than the thirty-odd callers it touches
today.

## A project file describes constraints, not instructions

Everything in `project.yml` bounds what work in the project may do; nothing in it tells an agent what to do. A prompt
or a standing instruction is the opposite kind of thing: standing instructions live in the repository the agent works
on and are not Sokar's business.

## A project exists by being followed

The only way a project comes to be on a machine is that the machine follows its repository; Sokar writes no
`project.yml` and offers no call, command or wizard that creates one. A project is created by a commit, in an editor,
and a second way to have one is a way to have a project the machine never verified. There is no separate
check-before-writing call either: the machine checks what it is handed when it is handed it, at `follow` (and
`follow --dry-run`, which records nothing) and at every reconciliation, and a check beforehand would be a second
place the same question could be answered differently. That check is the whole check: the name's shape, the security
class, a base image named at all, an `online` project without an upstream, and an egress set this machine does not
have, all refused at follow rather than at the first task start, which is minutes later and reads as a broken build.
The cost is a real first step for somebody trying Sokar (write a file, commit it, follow it), answered by `follow`
working against a local repository with no forge, and by the built-in project `default`.

## A project is named, never pointed at

Every command and every daemon call that takes a project takes its name, as `sokar project list` prints it, and no
command accepts a path to a project file, not even beside the name: two ways to say which project is how one
project's file comes to live in several places (a working directory, a configuration directory, the followed clone),
of which only one is verified. A name lets an interface that cannot see the machine's filesystem drive a task from
what `Projects()` told it, and the daemon and the CLI refuse the same input with the same words because one resolver
answers both. An unknown name is refused with the names the machine has; on `CanStart` it is an answer
(`NO_PROJECT_FILE`), not an error, since asking about a project that turns out not to exist is a fair question. Where
a running task already names its project (`shield egress --task`, `talk pass`, `talk hold`), the project is taken
from the task rather than asked for again.

## Configuration coming in is checked by its signature, as work going out is checked by a person

A followed project's repository decides what runs on every machine following it - the image, the limits and the
hosts a task may reach - so whoever can push there could otherwise open the firewall on all of them at the next
fetch. A machine therefore applies only a commit signed by a key pinned out of band (`--signed-by`), and keeps what it
verified last when a commit is refused or the repository cannot be reached. The signature is checked by git
(`verify-commit` against the pinned keys in `allowed_signers` format), not reimplemented, since a commit's signature
covers the commit object and a second canonicalization would be a second implementation of what git already does
exactly. A history that moves under the machine is refused until a person says `--accept-rewrite`, since a rebase and
a replayed older signed file look the same from the machine.

## A signing key is never read from the repository it verifies

The key a followed project's configuration is checked against comes from the person, at `follow --signed-by`: never
from a file, a commit or a branch of that repository, since an anchor that travels with what it authenticates
authenticates nothing. A fingerprint is accepted in its place, and the key's bytes are then taken from the refused
commit's own signature, but only when their fingerprint is the one the person typed: the person still says which key
it must be.

## A project's signing key is handed on through the project file

After the first key, named once out of band at `project follow --signed-by`, the project's own `project.signers` says
whose signed commits change it. A commit that changes the list counts only when it is signed by a key in force before
it, and a key never vouches for the commit that adds it, so a change of key is two commits: the old key names both,
then the new key alone retires the old one. Pinning a new key on every following machine by hand, one by one, would
make every machine refuse the next commit after a key change. The machines' message keys stay apart in
`machine-signers` and never change a project's configuration, so a machine that is taken over has no power over the
others.

## A project may be followed without a signing key

`sokar project follow --unverified` applies what a project's repository says with no signature checked. **The
exposure:** without a signature the rule is *whoever may push to this repository decides what tasks here may reach*,
rather than *whoever holds the signing key*. The second set is much smaller: it excludes every CI token with write
access, every leaked colleague's credential and the forge itself. And an agent working on the project's own
repository edits the very file this verifies, so "an agent may propose configuration and never put it in force"
holds without a key only as long as the gate's review catches it. **Why it stays:** access to a git repository is
already authenticated, and common practice applies what comes out of an authenticated clone without checking
signatures; refusing every unsigned follow would make every fixture and every first try sign commits. It is
therefore a state, not an error, kept per project so that one unverified project never quietens another, and shown
wherever the project is: `project following`, `doctor` (degraded) and `Projects().following.unverified`. **What
would change the answer:** a deployment where the people who can push to a project repository are not the people
who decide what tasks may reach, or evidence of configuration slipping past the gate's review.

## Where a followed project's file comes from

A task of a followed project reads `project.yml` from the clone this machine verified, never from the working
directory or any other copy: a file elsewhere is not preferred, merged or warned about and then used, because two
sources is how a machine comes to run something nobody chose. A followed project with nothing in force (refused,
unreachable, nothing pinned) starts no task, and the local file is not a fallback: falling back would run exactly
what the machine declined to apply and make a refusal look as though it had no effect. The commit a task's file was
verified at is recorded with the task as a container label, because "what was this task running under" is asked
after something went wrong, when the project has moved on.

## Clearing names what only a person's credentials can remove, and never attempts it

`sokar clear` and `sokar project clear` remove everything this machine holds for a project or the account, in one
confirmed step, but a deploy key at a forge and this machine's line in a project's `machine-signers` are answered,
never removed: the machine holds no forge token and not the person's signing key, and must not. The interface removes
them with the person's sign-in and one signed commit; the command line prints them. The vault, the person's keys and
the installed packages stay: clearing is not uninstalling.

## A task holds no key and sees no address

Inside the container there is one mailbox directory and nothing else for messages: no socket, no credential, no
network. A recipient is a peer name; what it resolves to is on the host. Every message is signed on the host with the
key of the account running Sokar, because a key a task can reach is a key it can copy; the signature says the
installation vouched, not that the agent typed it. That key is not the user's login key: one lets a machine connect,
the other says what it vouches for, and revoking one must not invalidate the other.

## No model decides what a message may contain

The message sluice is rules in a tool of its own, with no model: it accepts or refuses, and a person holds. A
classifier may be added and may then only hold a message, never release one. A hosted model would be a place every
conversation leaks to, and one reading every project's messages a bridge between them.

## A project's message rules are set once, in its project file

How closely messages are watched is the project's, set once in `project.yml` under `mail.rules` by class of peer -
the project's own tasks, its conversation (the room and its people), every other peer - and a peer may name its own
mode. Defaults: `project: allow`, `room: allow`, `others: deny`. A message the filter passes goes at once; only what
the filter flags waits for a person (a refusal in blocking mode, "would have refused it" in reporting mode). A mode
decided per developer and per peer on each host would make the same project behave differently on every machine and
ask a question nobody can answer well. The host keeps only the brake: holding a peer's messages, per project and
peer, where no task can change it. A mode set on the host is not read; `talk hold --mode` and `Moderate` with a mode
are refused and say where a mode is set. Kinds of message in the rules and a brake per task are not wanted.

## A person's words in the room reach every agent, unfiltered

Communication with a project's agents is through messaging. Everything said in the project's conversation reaches
every task of the project on the machine; `metadata.to` says whom a message is meant for, not who reads it. A
person's messages are not filtered - only what the agents send is - because the filter guards what leaves a task, and
a person writing to their own agents is not that. They are taken only from somebody who joined the project's
conversation, arrive as a `ROLE_USER` message from that person, and are counted and recorded like any arrival. A
direct chat with one agent goes to that task alone. An answer goes where it was asked: `metadata.via` (`room` or
`direct`) is copied into the answer.

## The mailbox guide is the same for every task of one Sokar version

The text that tells an agent how its mailbox works (`/run/sokar/mail/README.md`) is built only from the constants the
host and the filter enforce, never from the task, its project or its peers. It ends the agent's system prompt, and a
provider caches a prompt by its beginning, so a text that varied per task would cost every task its cache. What does
vary - whom the task can reach - is a separate file, `/run/sokar/mail/agent-card.json`, names only and never an
address, rewritten at every pass, which the guide says to read before writing.

## Each host keeps its own record; a shared ordered record is given up

What happened to each message is a hash-chained log per host, tamper-evident locally and verifiable without a
network. A record shared and ordered across machines is given up: a room does not hash its parent, so each host's log
proves what it saw and nothing to anybody else. The head hash can be published later without a redesign. **What
would change the answer:** somebody other than the two peers needing to verify what passed and in which order.

## Refused originals live as long as the task

A message the filter refused is kept, readable only by the account running Sokar, for as long as the task whose
mailbox it belongs to, with no timer. **The exposure:** that directory holds secrets because the filter worked, so a
long-lived task accumulates every refused secret behind file permissions and nothing else - the same protection its
workspace has. **Why it stays:** one lifetime for everything a task owns is simpler to reason about. **What would
change the answer:** tasks living for months, or more refused messages than anybody can read.

## A project's conversation is one room, and the room is the boundary

A transport that keeps a conversation gives each project one: for Matrix one room, where every task of the project
posts and the people of the project are too. The room is the confidentiality boundary; two tasks that must not read
each other's messages belong in two projects. A project without such a transport is standalone, and its tasks do not
message each other; there is no built-in transport.

## Membership is not authorization; the host acts and the signature decides

No task touches the homeserver: its account's token stays in the host's vault and only the host-side transport posts
and polls for it. The host joins a task's account to its own project's room and no other. What arrives is delivered
only if it is signed by a key listed for a peer of that project and names one of that project's tasks in
`metadata.to`; anything else, including a message crossing from another project's room on the same server, is held
and the operator told. Matrix peers are `external`, so inbound content goes through the filter.

## One account per task, deactivated with it

Each task gets an account of its own when it starts, made with a random password that is thrown away, and only its
access token lives in the vault until the task is removed; then the account is deactivated. Authorship is carried by
the signature, not by the account. On the account's own homeserver the provisioning account registers first and so is
its administrator; on a central one, which has no administrator Sokar holds, each machine acts only on the accounts it
made and keeps each task's password beside its token.

## Sokar knows transports; the transport knows Matrix

No Matrix client, room alias, registration flow, admin command or homeserver unit is in `sokar`. Sokar knows
transports, conversations, identities and opaque secrets, through a generic lifecycle (`setup`, `enroll`, `retire`,
`join`, `settings`) any transport may offer, so a second transport adds nothing to `sokar`. Project settings under
`mail.transports.<scheme>` are passed to the transport verbatim and never read by Sokar.

## The homeserver is one endpoint Sokar names, and offline means loopback

The homeserver is the project's declared URL, or the account's own on loopback: a user unit on a port Sokar picks and
keeps in the account's state, with no root, so two users never share a server's administrator. The host's egress for
a project's messages is what the transport's `setup` reports in `reaches`, and nothing else. An `offline` project's
conversation may reach nothing but loopback: Sokar passes `--loopback-only`, so the transport refuses before it
contacts anything else, and checks `reaches` again before a task exists.

## A person joins by an account made for them

`sokar talk join` has the provisioning account make an account on the project's homeserver, invite it into the room
and print the login once; the person uses any Matrix client. No registration stays open. Their messages are
delivered only once their key is listed as a peer, as for anyone.

## A machine on a shared server is admitted by a person

On a central homeserver a person makes the room, names it in the settings and lets each machine's account in from
their own client; Sokar admits nobody. Until a machine is let in, it starts no task of the project and says whom to
let in and where. Each machine holds its own provisioning account, so one can be revoked alone.

## Only a transport a peer names is polled

A daemon polls a transport only if a peer of one of the account's projects names its scheme, and says a
misconfiguration (77, 78) once per cause, not every cycle.

## Sokar is split into modules by area, not into repositories

So that more than one agent can work in `sokar` at once, `app` is modules by area (`app-<area>`), each registering its
own daemon methods, describing them in its own part of the one contract `org.fuin.sokar.Tasks1`, and adding file
locations in its own paths class; the modules' dependencies are the boundaries, so a reference the wrong way does not
compile. `sokar` stays one build, one package, one push: it is one process, one socket contract and one installation,
and one repository per command group would only move the bottleneck into ordering pushes. The areas keep one Java
package across jars, so no access had to be widened and no class renamed; a sub-package per area is a later step if
it ever pays. The contract stays one interface on the wire, written in parts. **The exposure:** `app-base` and
`app-model` are about a third of the code and still shared, so two agents still meet there; a change there is said
first, which makes the meeting visible rather than making it go away.

## The build tooling lives in its own repository; the acceptance kit stays

`sokar-machines`, the release tool and the FFM, CPU and package checks live in `sokar-buildtools` on their own
version line, and `sokar` takes them from Central at one property, `sokar.buildtools.version`. They are a separate
world with a command line as their contract. The acceptance kit stays in `sokar`, because its steps change with the
product: moved out, most product features would need a kit release first. The cost: a change that needs both is two
pushes in order, the tooling first.

## Which machine types a leg rents

A leg's snapshot is taken at the smallest disk on offer (40 GB), so every type in `Spec.DEFAULT_TYPES` can boot it
and the availability fallback can fire: types sell out within minutes of each other, and a list of one would stop
every leg on a single shortage. A full leg peaks at about 10% of that disk. The list is `cpx42`, `cx43`, `cpx32`,
`cx33`: `cpx42` first because the CI runner blocks while the rented machine builds, so a slower machine costs CI
minutes, the scarce resource. `cx23` boots the image and passes, and is left out because two cores stretch a leg to
about 25 minutes. **The exposure:** nothing enforces the small build; a `--type` written into a snapshot build, or
into one of the workflows that rent a machine, for speed would read in review as a performance fix and silently
shrink the fallback to one type. **What would change the answer:** a guard on those call sites, or a measurement of
`cx43`'s build time (same cores as `cpx42` at about a quarter of the price), which would make it a candidate for
first place as a change about money.

## The issue index stays one table per section

`issues/base/README.md` is not split per area. Two agents adding a requirement each meet in one line of it, and that
conflict is resolved by keeping both lines; splitting the index would trade that for a document nobody can read as a
whole.

## A stop of every task keeps its tokens

`sokar task stop --all` (and `sokar panic`) stops every task as `task stop` stops one, and so keeps each task's tokens in
the vault: a stopped task is brought back with its work, and a resume needs them. Ending what a token grants is a step
of its own - `sokar vault lock`, and `sokar vault remove` of an entry, which revokes at the service where it can. Folding
revocation into the stop would make the one command reached for in doubt destroy what a calm look afterwards would
keep.

## Sokar offers what it would tell a person to type

Where something is missing and a refusal would name the command that makes it good, Sokar asks at a terminal and does
it, through one helper (`Offer`), alike everywhere:

1. **The remedy is the command's own code,** run as that command or called as its function - never a copy of it.
2. **The precondition is checked again afterwards,** never taken on trust; a remedy that did not help ends in the
   refusal it replaced.
3. **A destructive step** - discarding work, deleting, leaving an account at a service, changing a person's own file -
   names what it touches first, defaults to no, and is never taken by `--yes`.
4. **Without a terminal, or with `--no-input`,** nothing is asked and the refusal is word for word as before; a script,
   the daemon and the helpers see no change.
5. **Several gaps at once** are asked one after another, and the command goes on once at the end.

What stays a message: where nobody can answer, a review or trust decision that must not be made in passing, and a plain
report.

## A credential is entered only in a terminal

No method of the daemon's contract takes a secret: a credential reaches the vault by `vault put`, `vault login` or an
import, typed or pasted into a terminal at the node - over ssh, or in the terminal an interface opens there - and read
without echo. A contract that never carries a value is a property anyone can check by reading the interface
description; a method that carried one would make it a property every later log line, error and streamed reply has to
keep. A forced-command ssh account cannot open such a terminal and is not served: on such a machine an administrator
stores the credential through another account.

## A credential with no expiry is stored and named

A credential that carries no end is accepted, never refused: some providers issue nothing else. The store says it has
no expiry and the list of credentials marks it, because a long-lived key is the one secret whose leak does not decay,
and the person who could replace it with a shorter-lived one should see that every time they look.

## A developer's build labels itself

On an SELinux machine only binaries labelled `sokar_exec_t` may bind Sokar's sockets. A binary from a build tree is
labelled by the build itself, with `chcon`, after each build, where SELinux is enabled and the policy loaded. A file
context for a build path would name in the shipped policy a path only one person has.

## An MCP server reaches a task only over HTTP, through the gateway on the host

1. The gateway can attach a server's key, read its tool lists and calls and journal them only where the traffic crosses
   the host; a server over stdio has no such crossing.
2. A server over stdio is never started on the host, where it would run outside the task's firewall, limits and
   namespace with the person's rights. Where a project has one at all, it runs inside the task's container as the
   agent's own, and is named as not covered.
3. When a server's tool list changes, only the new or changed tools are held until a person accepts them; the unchanged
   ones stay usable. Holding the whole server would stop a task over a change it may never use and teach people to
   accept without reading.

## Nested containers never in a guarded project

A container started inside a task opens user namespaces and more of the kernel to the agent. A `guarded` project
promises the least is opened, so it may never set it; and where the assessment finds the guarantees hold only on a
task's own kernel, nested containers are built only there.

## A devcontainer file's features are not fetched

A feature is a script from a registry, run as root during the image build and chosen by whoever edits the repository's
devcontainer file. Sokar takes the file's image, or builds its Dockerfile from the reviewed default branch, and names
each feature as not installed, so nothing runs at build time that the project file does not name.

## Several attempts share one credential

The attempts of one start may share one credential and so its rate limit. Sokar does not split or queue them; a
reached limit is said per attempt, naming the credential, so a stalled attempt is not read as a weaker one. Five
attempts per start is the default ceiling. N attempts use up a subscription's limit about N times as fast; the person
chose N.

## A reviewer task reviews only its own project

A task in the role reviewer reads the waiting work of other tasks of its own project only. Reading another project's
work would carry code across the line Sokar keeps between projects everywhere else.

## An approve lands as a merge carrying provenance; a fast-forward is a project's choice

The merge the host makes at an approve records the models, agent and provider that made the work, read from the
broker, never from the agent, with the reviewed commit untouched as its second parent. A branch protected for linear
history refuses a merge, so a project may choose a fast-forward; it then gives up the provenance in its history, and
the documentation says so.

## The owner approves alone unless a project asks for a second person

On a node the person who started a task is the one who approves. A project that needs separation of duties sets four
eyes, and its approve then needs another of its signers' signature over the reviewed commit. Without the setting a
person working alone is not slowed down.

## A time limit does not cut off a person who is watching

An unattended run ends at its project's limit; a start may shorten that limit, never lengthen it. A person attached to
the task holds the stop back: at the limit they are warned, and the run is stopped once they detach. A person at the
session is the oversight the limit stands in for.

## Account-wide settings live in one file

Settings that belong to the account rather than to a project - the period after which unused access is reported and
whether it is locked, how long `task.log` is kept, the largest bundle a client may upload, where the event log is
copied - go into `$XDG_CONFIG_HOME/sokar/config.yml`. A missing file means every default, an unknown key is refused by
name, and one page lists every key with its default.

## Unused access is reported, and locked only on request

A device slot or deploy key unused for 45 days is named by `doctor`, with the remedy. Locking it is a setting, off by
default: a lock surprises whoever comes back after a long absence, while a report leaves the decision with the person.

## The event log is anchored in the user's journal and outlives a clearing

An account's own file cannot be made tamper-proof against that account, so each line's hash also goes to the user's
systemd journal. Where the settings name a destination, each line is also sent off the machine; nothing leaves without
that setting. The log is the account's history, not a project's, so `sokar clear` of the account keeps it.

## What carries an agent's words is kept for a period

`task.log` holds the agent's own words and so may hold personal and confidential data. It is cut 30 days after it was
written, even while its task lives, and so is a session's recording; the account's settings may change the period.

## A task's spending is capped in tokens, not in money

Every provider's answer carries a token count, so a token ceiling holds for every provider the broker forwards to; a
price does not, and one computed from a price list on the host goes stale. The ceiling is enforced in the task's
broker, which the agent cannot bypass. A ceiling in money may follow, only for providers that report each answer's
cost, as an addition to the token ceiling.

## An alert either only tells or stops the task; it never freezes it

A paused task holds its session while its tokens' lifetimes run on, so a freeze contains nothing a stop does not and
leaves a half-open state to clean up. Each project chooses whether an alert only tells a person, the default, or also
stops the task and revokes its tokens.

## A false secret finding is acknowledged on the host, never in the repository

The agent writes the repository, so an acknowledgement it could write is one it could use to silence a finding. The
list is kept beside the project on the host, owner-only, each entry with a hash of the matched text so it lapses when
that text changes. The scan covers the tree a task checks out, not its history, and the documentation says so.

## A new dependency with a finding waits for a person

A name that is not in its registry, could not be checked, or is close to a well-known one is the shape a hallucinated
or squatted package takes; a mark in the review is not enough, because an approve can be given without reading it. The
approve asks for each such dependency and, without a terminal, is refused until the dependency is named, bound to the
reviewed commit. Maven Central and npm come first.

## A release waits for the misuse suite, and a found hole is never skipped

The guarantees are attacked as an agent would attack them - escaping the container, carrying data out through DNS,
allowed domains and the broker, bypassing the approval - and a release is made only when every such scenario ran and
passed. A scenario that finds a hole holds the release until the hole is closed; it is never tagged or filtered out to
let a release go.

## The data protection page follows the federal ISDS concept template

An organisation that writes an information security and data protection concept fills the Swiss federal template
chapter by chapter; a page in the same chapters lets it take Sokar's part without rearranging it. Each chapter says
what Sokar provides and what the organisation still regulates.

## Every Sokar repository gets the changelog check from its parent

The rule that a change brings a changelog entry holds for every repository, so the check is bound once in
`sokar-parent`, which every Maven repository of Sokar inherits, and configured where only Sokar reads it. A repository
whose product is not built with Maven runs the same check as a step of its own in its workflow.

## A leg runs only the unit tests that need its machine

A rented machine exists to deploy the product and run the acceptance suite against it. Unit tests run on the build
host, except those whose condition only the target has, such as SELinux; those run on the leg that has it and fail it
when skipped there. A leg is not made a second build host.

## A kernel of its own for a task is measured before it is decided

The second wall is worth something against kernel bugs, but its cost falls on the ruleset, the resolver, the hooks and
the sockets. Adoption is decided on measured answers: whether a kernel of its own works, whether the ruleset still
fails closed, and what it costs. Snapshot and restore of a task, its running agent with it, is a goal such a runtime
may make reachable.

## A storage class Sokar cannot prove is shown as user-scoped

What protected an unlock is shown as worth more only when Sokar can prove it, by a slot the node enrolled itself or by
evidence such as a TPM2 attestation the node checks. A claim a client merely sends is recorded, but the command line
and the interface show it exactly like a user-scoped slot: a label that reads stronger than it is does more harm than
an honest weaker one.

## A digest pins an image; a tag-only image in a followed project needs a person's consent once

A digest names exactly one image, so a signed project file that carries one pins its content; podman's signature
policy is an option a project may add. A followed project that names its image by tag only does not start until a
person allows it once; after that the digest is kept and a change of it is said at every start, so a moved tag never
changes what runs silently.

## One key signs everything a machine installs from Sokar

Packages, the setup script and the Debian repository's metadata are signed with one key, so a machine trusts one
fingerprint. Its private half is held by the project's owner only and reaches CI only as a repository secret. The
repositories that publish to the same package repositories switch signing on with the same release, because a setup
that checks signatures refuses every repository that does not sign yet.

## A view of a task is guarded by Sokar's token, not by VNC's password

A task's screen is offered only to a project that switches it on in `project.yml`, and even then only through Sokar's
relay, which takes a strong one-time token before a byte reaches the VNC server. Loopback alone is no access control on
a machine with other accounts, and VNC's own password is too weak to rely on.
