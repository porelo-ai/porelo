# Base Requirements

What the product must do below the interface: the CLI, the daemon and the guarantees they make.
One file per requirement, each carrying its own acceptance criteria so it can be judged done or
not done.

Grouped by state, in the order the rules in [AGENTS.md](../../AGENTS.md) give, and within each group ordered by what to
do next; the number is only the file's identity. A group with nothing in it is left out. Why a requirement exists is
in its own file; a row says what it covers and, from Implementing on, how far it is.

**Open questions** counts the items in the file's own *Open questions* section: what only the operator decides -
product, scope, an account, money, risk. It is what waits on the operator, and only *Analyzed* has the column: from Estimated on an issue has no
open question. What can be found out by reading, measuring or asking another agent stands under *To be checked* and
is not counted.

**Blocked by** names the issue that has to land first. An issue in another repository that waits for a row here says so
from its side, as *blocked by*.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="b120"></a>[B120](B120-A-Wake-Waits-For-Rest-That-Holds.md) | S | - | Two looks at the screen 1.5 s apart before a wake is typed, Enter once more for a line left standing; the agent repositories' wake scenarios green on both legs with `v0.5.0`. |

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="b158"></a>[B158](B158-A-Refusal-That-Knows-The-Next-Command-Offers-It.md) | M | - | A refusal offers its remedy command; built for vault login, a start's credential refusals and resume's shut vault; a resume test is left. |
| <a id="b137"></a>[B137](B137-An-Offline-Project-Never-Connects.md) | S | - | An offline project never connects; left: an acceptance round trip that follows a bundle, restores, backs up and refuses a `file://` follow. |
| <a id="b102"></a>[B102](B102-A-Vault-Cleared-In-One-Step.md) | S | - | `vault clear` is on `main`; left: a scenario in `vault-commands.feature` and the walk with a homeserver on a rented machine. |
| <a id="b76"></a>[B76](B76-The-Code-Checked-Against-The-Skills-It-Was-Written-Without.md) | L | - | Every finding fixed or declined and every module read; `apps/*` and `daemon` read again whole; left: one rule for the kit's bounds across the agent repositories. |
| <a id="b125"></a>[B125](B125-What-A-Daemon-Costs-And-On-Which-Threads.md) | M | - | Idle measured on the two-CPU VM, no loops for exited tasks or tasks without an account, and never-started containers kept as tasks; left: the idle measurement again and the run on virtual threads. |
| <a id="b79"></a>[B79](B79-A-Daemon-That-Answers-Nothing.md) | S | - | Platform threads and the thread dump are built; left: runs on the two-CPU VM after SIGKILL, both thread kinds, reading a dump on a hang. |
| <a id="b13"></a>[B13](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md) | M | - | Guard and checkout on `main`; pending marks work already upstream and reject refuses it without `--force` (`Tasks1` gains an optional field and `force`); left: the hand-push scenario. |
| <a id="b49"></a>[B49](B49-What-The-Build-Trusts-To-Run-Beside-Its-Secrets.md) | S | - | Pins, Dependabot and `check-actions` are on `main`; left: a checksum-pinned JFrog CLI step, the hand check of `Publish`, the first Dependabot review. |
| <a id="b31"></a>[B31](B31-An-Authorization-A-Person-Grants-Once.md) | S | - | Dynamic client registration built against a stand-in; left: a real GitHub grant and an hour-long stream with GitHub's remote MCP server on the VM. |
| <a id="b46"></a>[B46](B46-The-Conversation-A-Restart-Loses.md) | M | - | Session continuation, `--restarted` leaving an unattended task stopped with its session, a stub that remembers a word and the real-reboot `@restart` scenario are built, and it passed on the `fedora` VM; left: green in the Build's rented legs. |
| <a id="b134"></a>[B134](B134-A-Task-Terminal-As-The-Terminal-Outside.md) | M | - | The task's tmux matches the terminal outside; a kit step and a scenario per key are built and Pi is measured; left: the scenario's VM run, then the joint test. |
| <a id="b133"></a>[B133](B133-A-Shut-Vault-Opened-Where-It-Is-Needed.md) | S | - | A shut vault asks for its passphrase at a terminal instead of refusing; left: one scenario that starts a task on a shut vault. |
| <a id="b129"></a>[B129](B129-An-Agent-Found-By-Its-Description.md) | S | - | Agents found by an `agents.d/<name>.yaml` description; left: the stub packaged that way and the package check proving it. |
| <a id="b14"></a>[B14](B14-Talking-Between-Tasks.md) | XL | `porelo` [B86](README.md#b86) | Talking between tasks, built on one machine; splits into groups (M), trust confirmed once with fingerprints (M), two machines on one homeserver (L). |
| <a id="b06"></a>[B06](B06-Remote-Access.md) | M | - | Remote use over ssh; both shapes built; left: a daemon-socket check and a real `ssh localhost` hop in the acceptance suite. |
| <a id="b23"></a>[B23](B23-Secrets-In-This-Process-Memory.md) | M | - | The broker, the ssh agent and the daemon are not dumpable; the deploy asks the daemon what it runs (`Tasks1.Process`) and the port scenario counts its unit's sockets; keyring and vault-file buffers closed; a credential as bytes dropped, kept as an accepted risk; left: the daemon's scenarios on the VMs. |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="b154"></a>[B154](B154-A-Tamper-Evident-Event-Log-Per-Node.md) | L | - | One hash-chained event log per account, anchored in the user's journal, optionally copied off the machine, kept by `porelo clear`; written by approve, reject, clearance, hand-in, credentials and project changes. |
| <a id="b189"></a>[B189](B189-Values-Handed-To-A-Task-At-Its-Start.md) | L | - | Values a project declares in `parameters:`, handed to a start by `--set`, `--set-file` or `--set-env`, checked by `StartCheck`, kept in `parameters.json`, shown with their origin by `task status` and `Task`, set in the container and usable as `{name}` in settings. |
| <a id="b190"></a>[B190](B190-A-Tasks-Branch-Named-By-The-Projects-Scheme.md) | L | `porelo` [B189](README.md#b189) | `branch.template` and `branch.pattern` or `--branch` name a task's forge branch, kept in `task.json`; the gate passes on and keeps out of its mirror by them, an existing upstream branch refused at an online start, a guarded approval onto it by default and only within the pattern. |

## Analyzed

| # | Size | Blocked by | What it covers | Open questions |
|---|---|---|---|---|
| <a id="b175"></a>[B175](B175-The-Profile-That-May-Bind-The-Socket.md) | L | `porelo` [B135](README.md#b135) | On an AppArmor machine a task connects only to a socket one of Porelo's binaries bound, by a task profile that refuses any other peer, as the SELinux domain does on SELinux machines. | 2 |
| <a id="b176"></a>[B176](B176-Comment-Threads-That-Follow-A-New-Push.md) | M | `porelo` [B166](README.md#b166) | A thread on waiting work moves to a new push's commit where its lines are unchanged, else stays and says why; each commented commit pinned under a hidden ref; `Thread` gains `from` and `stays`. | 1 |
| <a id="b177"></a>[B177](B177-A-Cost-Ceiling-Per-Task.md) | M | `porelo` [B138](README.md#b138), [B191](README.md#b191) | A dollar ceiling per task from `limits.cost`, counted by the broker from the cost the provider reports, else at list price from the price table; a model with no price refuses the ceiling at the start or fail closed, never $0; under a subscription it caps list-price value. | - |
| <a id="b178"></a>[B178](B178-A-Credential-With-No-Expiry-Said-So.md) | M | - | Storing a credential with no end says so (`vault put`, `vault login`, `ImportCredential`), and `vault list` and `Credentials()` mark it with `noExpiry`. | 1 |
| <a id="b122"></a>[B122](B122-No-Direct-Chat-Between-Tasks.md) | M | - | Peers at an account accepted only for joined persons: checked at the start, in `talk peers`, in the dispatch and again at the send. | - |
| <a id="b173"></a>[B173](B173-An-Agents-Sign-In-Kept-Without-A-Person.md) | S | - | `vault login <agent> --credentials-from <dir>`: an agent's sign-in read through its extractor and kept renewable without a person; `--token-url` on loopback only, for a kit's stand-in token endpoint. | - |
| <a id="b121"></a>[B121](B121-A-Message-Record-Without-A-Chain.md) | M | `porelo` [B154](README.md#b154) | Drop `previous` and `hash` from `MessageRecord`, remove `porelo talk verify`, its rows and scenario, and update the docs and the decision. | - |
| <a id="b148"></a>[B148](B148-Token-Counting-Per-Task.md) | L | - | The broker reads four answer formats, streamed or not, into a per-task `usage/<task>.jsonl` kept after removal: agent, provider, models, five counts (`null` when not reported), tools, how it ended, a reported cost; asks for the streamed Chat Completions count; sums in `task status` and `Usage`. | - |
| <a id="b191"></a>[B191](B191-What-A-Task-Cost-At-List-Prices.md) | L | `porelo` [B148](README.md#b148) | Price files per provider with their date, a task, agent and model priced at list prices, unpriced requests counted, a declared subscription and what it saved, in `task status`, `porelo usage` and `Cost`. | - |
| <a id="b150"></a>[B150](B150-The-Whole-Diff-In-Review.md) | M | - | The daemon's `Review` fails above the 4 MiB message limit today: cut at a file boundary, said with `cut` in the contract; `files` is never cut. | - |
| <a id="b108"></a>[B108](B108-The-Narrowed-Message-Schema-Enforced.md) | M | - | One JSON Schema the filter enforces and the guide and docs are made from; the host does not check the shape again. | - |
| <a id="b118"></a>[B118](B118-An-Attached-Agent-Said-At-Rest-Or-Working.md) | M | - | A new `AT_REST` activity, judged from the screen reads the listing already makes with the wake's two-look rule; the stub agent gets an `at_rest`. | - |
| <a id="b119"></a>[B119](B119-A-Loop-Seen-In-An-Unattended-Tasks-Log.md) | S | - | The kit's `LoopWatch` also counts a declared failure marker in each task's `task.log`, from a per-step baseline. | - |
| <a id="b10"></a>[B10](B10-What-An-Egress-Set-Can-Express.md) | L | - | Egress addresses in `project.yml`, refused lookups shown, set fingerprints, `complete: false` for sets; four commits, and `Sets()` grows. | - |
| <a id="b34"></a>[B34](B34-What-The-Resolver-Can-Actually-Do.md) | S | - | The resolver's boolean becomes a named capability of two values with its path, shown in doctor, its JSON and setup's refusal. | - |
| <a id="b33"></a>[B33](B33-A-Tasks-Own-Fetches.md) | S | - | The noble 401 reproduced on the VM first; if it does, one noble-only Containerfile line and scenarios. | - |
| <a id="b26"></a>[B26](B26-What-This-Machine-Has-Been-Doing.md) | L | - | A durable per-account event journal with a cursor and caps, written from many places, `Events()` and a command; one log with [B154](README.md#b154)'s. | - |
| <a id="b135"></a>[B135](B135-The-SELinux-Module-Loaded-By-The-Package.md) | M | - | The packages load and remove the SELinux module in their maintainer scripts; the package check reads them; one run on an enforcing Fedora. | - |
| <a id="b151"></a>[B151](B151-The-Providers-A-Project-Allows.md) | M | - | `providers:` in `project.yml`; the start check refuses a provider not on the list, naming the allowed ones, for the CLI and the daemon. | - |
| <a id="b152"></a>[B152](B152-Say-What-Offline-Still-Reaches.md) | S | - | Wording on how-it-works, project-file, faq, reach, corporate-security and index; a documents test checks that each `offline` description names the provider. | - |
| <a id="b101"></a>[B101](B101-A-Mode-A-Start-Asks-For-By-Name.md) | M | - | Named modes and a thinking level in the agent definition, `--mode` and `--thinking` at start, both listed in `agents`. | - |
| <a id="b86"></a>[B86](B86-Messages-Over-Matrix-The-Porelo-Half.md) | M | `porelo-message-matrix` [MX12](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx12) | The transport account kept per homeserver, not per scheme; a registration token by `vault put`; docs; a walk on two machines. | - |
| <a id="b38"></a>[B38](B38-How-Far-Something-That-Got-Through-Can-Get.md) | L | `porelo` [B26](README.md#b26) | An origin field in the agent definition, applied by hand-in to every delivery, and a `podman diff` count kept at removal. | - |
| <a id="b56"></a>[B56](B56-Programs-Porelo-Runs-That-Could-Be-Calls.md) | S | - | The table of programs Porelo runs completed, and a source-scanning test that fails on an unlisted one; each replacement its own issue. | - |
| <a id="b116"></a>[B116](B116-A-Log-Read-Back-In-Windows.md) | M | - | `Tail` gets `before` to read back in windows, and a cap per line reported in `cut`. | - |
| <a id="b165"></a>[B165](B165-A-Port-Of-A-Task-On-Loopback-Only.md) | M | - | `porelo task port`: a two-half relay, in the task's namespace and on host loopback only, over a `0700` socket, with `ports.log`, docs and a scenario. | - |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="b192"></a>[B192](B192-An-Image-Chosen-Per-Repository.md) | - | `porelo` [B170](README.md#b170) | A task started on one repository of a project gets that repository's own image, built from its devcontainer file. |
| <a id="b193"></a>[B193](B193-A-Dependency-Cache-Shared-Across-Tasks.md) | - | - | Downloaded dependencies cached once for a project's tasks and handed in read-only; what a task installs stays its own. |
| <a id="b194"></a>[B194](B194-A-File-Handed-In-At-Every-Start.md) | - | - | A host file such as `.AGENTS.md` handed into an agent's task read-only at every start; a changed copy written on the host only after approval. |
| <a id="b203"></a>[B203](B203-Task-Names-Checked-By-What-The-Commands-Do.md) | - | - | The test that every task and talk command takes both spellings of a task runs the commands through picocli's tree instead of matching their source text. |
| <a id="b201"></a>[B201](B201-A-Start-Now-Built-Or-Removed.md) | - | - | `task start --now` is accepted and does nothing: built or removed, after finding what it was meant for. |
| <a id="b202"></a>[B202](B202-Credential-Names-In-A-Project-File-Checked.md) | - | - | A credential's name in a project file is checked when read, since it becomes a token file's and a variable's name; what an existing file does is decided. |
| <a id="b198"></a>[B198](B198-Settings-Inherited-Down-An-Organisations-Tree.md) | - | - | An organisation file pinned by the machine's administrator passes settings down signed teams to projects by repository address; `fixed` and `narrow-only` locks no file below can undo, every file verified through the chain of keys, `project show --resolved`; folds in porelo-project [PJ16](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj16). |
| <a id="b199"></a>[B199](B199-A-Transport-Reaches-Only-What-It-Names.md) | - | - | A transport's adapter reaches only the hosts its `reaches` names, as `doc/transports.md` promises; today nothing builds a rule from them. |
| <a id="b200"></a>[B200](B200-A-Varlink-Call-Has-A-Read-Deadline.md) | - | - | A call through `VarlinkClient` to a side that accepted and never answers ends within a stated time with a named error; no contract changes. |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="b179"></a>[B179](B179-A-Code-Beside-Why-A-Release-Cannot-Go.md) | - | - | A code beside `detail` when a release is answered `NOT_DELIVERABLE`, so a client can say what to do per case. |

## Estimated

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="b184"></a>[B184](B184-Only-What-Porelo-Delivered-Counts-As-A-Message.md) | M | - | A message in the inbox counts only when the account running Porelo owns it (root inside); the host reads the inbox without following links and names a planted file; the guide and the agent kit check the owner. |
| <a id="b185"></a>[B185](B185-A-Bound-On-What-A-Task-Writes-To-This-Disk.md) | M | - | The daemon measures a task's box and writable layer and pauses it over `limits.disk` (10 GiB), `limits.mailbox` (256 MiB) or under `limits.disk_free` (2 GiB), each settable in `project.yml`, and says how to go on. |
| <a id="b95"></a>[B95](B95-A-Container-To-Try-Waiting-Work-In.md) | XL | - | `gate try` and a streaming `Try` in a container without gate or vault; the IDE split into an issue of its own. |
| <a id="b123"></a>[B123](B123-Message-Transports-Behind-A-Published-API.md) | XL | - | `porelo-transport-api` and its contract `Transport1`; splits into the library, the stub, the host client, the Matrix move and the end of the command-line contract. |
| <a id="b15"></a>[B15](B15-Handing-Artifacts-Between-Tasks.md) | XL | - | Artifacts over the message transport; splits into the design redone, a gate blob threshold, transport verbs, a drop directory, delivery by hand-in, withdrawal. |
| <a id="b166"></a>[B166](B166-Line-Comments-Sent-Back-To-The-Agent.md) | L | - | Comments as the sixth kind `comment` of the narrowed schema; `Comment`, `Comments`, `ResolveThread` in the gate's contract; a store, delivery at reject, replies. |
| <a id="b171"></a>[B171](B171-Forge-Review-Comments-To-And-From-A-Task.md) | L | `porelo` [B166](README.md#b166) | A `Review1` reader contract, a pull request matched by the gate's branch, a reviewer list, a queue for what goes out, a write token, rate limits. |
| <a id="b172"></a>[B172](B172-A-Task-That-Reviews.md) | M | `porelo` [B166](README.md#b166) | `role` in the task profile, `--role reviewer --review` on its own project's other tasks only, a gate that shows the reviewed ref and refuses every push, the comment's origin. |
| <a id="b160"></a>[B160](B160-A-Project-Followed-From-A-File-Over-The-Socket.md) | M | - | Follow from a file over the socket: a path against home, a bundle uploaded in parts (1 GiB), `Followed` gains `fromFile` and `uploaded`; [F104](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f104) waits on it. |
| <a id="b138"></a>[B138](B138-A-Token-Ceiling-Per-Task.md) | M | `porelo` [B148](README.md#b148) | A token ceiling per task in `Limits` and `--token-limit`, enforced by the broker with a status agents do not retry, `task limit` to raise it. |
| <a id="b139"></a>[B139](B139-An-Alert-On-What-Looks-Wrong.md) | L | - | Baselines first, then alerts on body size, clearance attempts and odd DNS names, to the desktop and `Watch`; per project alert only or stop with the token revoked. |
| <a id="b140"></a>[B140](B140-A-Secret-Scan-Of-The-Workspace-At-Start.md) | M | - | A host-side `git grep` of the checked-out tree for known credential forms at start, warn or refuse per project, false findings acknowledged in a host-side list the agent cannot write. |
| <a id="b141"></a>[B141](B141-New-Dependencies-Checked-Before-Review.md) | XL | - | Review names new Maven and npm dependencies and checks registry existence, age and look-alike names; a finding holds the approve until a person confirms it; splits into names (M), registry check and hold (L), look-alike (S). |
| <a id="b144"></a>[B144](B144-AI-Provenance-In-The-Commit.md) | L | `porelo` [B148](README.md#b148) | A merge commit at every approve, with AI-Model/Agent/Provider/Task trailers read from the broker's usage record since the last approve; a project may choose a fast-forward without them. |
| <a id="b149"></a>[B149](B149-A-Local-Provider-Template.md) | M | - | http only for loopback upstreams, a broker route with no credential, and two templates: `providers/local.yaml` on `127.0.0.1` and `providers/organisation.yaml` over https; the task reaches the host port through the broker. |
| <a id="b136"></a>[B136](B136-An-Approve-Bound-To-What-Was-Reviewed.md) | M | - | `gate review` names the full commit; a plain `gate approve` asks (default no) or refuses with exit 70; the daemon refuses an empty commit; enrolment alike. |
| <a id="b98"></a>[B98](B98-The-Image-A-Signature-Names.md) | M | - | A warning on a tag-only `base_image`, a followed tag-only project refused until allowed once, the base digest as an image label, a changed digest noted at start, an optional per-project signature policy, the default project pinned. |
| <a id="b99"></a>[B99](B99-Packages-Signed-And-Checked.md) | L | - | RPMs and the setup script signed in CI with the Debian repository's key, one shared `check-signed` in the release tools, a version pointer, `gpgcheck=1` everywhere from the next joint release. |
| <a id="b100"></a>[B100](B100-A-Task-Can-Be-Looked-At.md) | XL | - | A VNC view relayed by the daemon from a socket in the task, switched on per project, a strong token before anything reaches the VNC server; splits into task view, image tools and review view. |
| <a id="b117"></a>[B117](B117-What-Clearing-Leaves-For-A-Person.md) | M | - | The machine records the deploy keys it hands out, so `clear` names them after an unfollow; unreviewed gate refs and unpushed task work are listed before removal. |
| <a id="b51"></a>[B51](B51-The-Tests-That-Run-Nowhere.md) | M | - | The SELinux tests on the Fedora rented machine, skips that can never apply say where they run, and a report across legs. |
| <a id="b55"></a>[B55](B55-The-Changelog-Entry-A-Change-Has-To-Bring.md) | L | - | A check that a change brings a changelog entry, proposed to logchange first, else a plugin of our own, bound in `porelo-parent` for every Maven repository and a step of its own in `porelo-frontend`, keeping the old script's three rules. |
| <a id="b50"></a>[B50](B50-Whose-Business-The-Files-Porelo-Leaves-Behind-Are.md) | M | - | Data and state directories and `~/porelo/reviews` owner-only at creation, mirrors with `--shared=0600`, and a doctor probe that corrects older machines. |
| <a id="b40"></a>[B40](B40-The-Domain-That-May-Bind-The-Socket.md) | L | - | Porelo's binaries, the agent and build servers under `/usr/libexec/porelo` too, in their own SELinux domain, the only one that may create or bind `porelo_socket_t`. |
| <a id="b48"></a>[B48](B48-The-Overview-That-Stays-Open.md) | M | - | `task list --watch` (machine-wide, `--project` on both forms) on the daemon's `Watch` stream through the shared listing code, a hint line for waiting clearance questions, ages redrawn locally, the terminal reset on Ctrl-C. |
| <a id="b30"></a>[B30](B30-Credentials-The-Broker-Has-To-Fetch.md) | L | - | `client_credentials` end to end against a stand-in; then it rests until a service needs `private_key_jwt` or mTLS. |
| <a id="b142"></a>[B142](B142-A-Misuse-Test-Suite-As-A-Release-Condition.md) | L | - | `@misuse` scenarios of all three kinds (escape, exfiltration by DNS, allowed domains and the broker, approval bypass), each naming its page; a red one holds the release, none is ever skipped, and the release refuses legs that ran fewer than the suite holds. |
| <a id="b143"></a>[B143](B143-Porelo-In-A-Data-Protection-And-ISDS-Concept.md) | S | - | `doc/data-protection.md` in the chapters of the federal ISDS concept template: what Porelo provides and what the organisation regulates per chapter, what to back up; headings and paths checked by a documents test. |
| <a id="b145"></a>[B145](B145-Four-Eyes-At-The-Gate.md) | L | - | A project four-eyes setting, off by default (the owner approves alone): the starter recorded as a signing-key principal, approve needs another signer's signature, checked with `ssh-keygen -Y verify`. |
| <a id="b147"></a>[B147](B147-A-Wall-Clock-Limit-For-Unattended-Runs.md) | M | - | `limits.minutes` in the project file, which `--minutes` may only shorten; a deadline inside the container plus a real `task stop`, held back while a person is attached and taken when they detach; the reason in status; measurement first. |
| <a id="b153"></a>[B153](B153-Unused-Slots-And-Keys-Reported.md) | M | - | Doctor reports device slots and deploy keys unused for 45 days (a setting in a new account-wide `config.yml`); a deploy key's last use recorded where credentials are lent; locked only where the settings ask. |
| <a id="b155"></a>[B155](B155-The-Logs-Checked-And-Documented.md) | M | - | A `doc/logs.md` page for every log; tests show the broker and gate logs hold no secret and every log file is `rw-------`; `task.log` cut after 30 days (a setting). |
| <a id="b156"></a>[B156](B156-porelo-inventory.md) | M | `porelo` [B148](README.md#b148) | `porelo inventory` (text, `--json`, a daemon method) lists the account, agents, providers, credentials with the projects they serve, slots, deploy keys, last use and models. |
| <a id="b112"></a>[B112](B112-A-Nickname-For-Each-Agent.md) | M | - | The label becomes a one-word nickname, unique in its project: `@label`, `you` in the card, `--display` and `rename`. |
| <a id="b41"></a>[B41](B41-What-A-Packaged-Agent-Installs.md) | M | - | `--supply-chain` and the daemon's `Agent` list what a package ships, read from `/usr/share/porelo/sbom/<executable>.cdx.json` by one shared reader. |
| <a id="b59"></a>[B59](B59-A-Kernel-Of-Its-Own-For-A-Task.md) | XL | - | A kernel of its own for a task; splits into a krun/KVM experiment (M) that also measures snapshot and restore, a doctor probe (S), and an opt-in adoption (L) decided on the measurements and only if the ruleset fails closed. |
| <a id="b63"></a>[B63](B63-A-Share-That-Is-Not-Stored-At-All.md) | XL | - | A share not stored at all; splits into a token measurement (S), FIDO2 enrolment (L), TPM2 (L), and proving what protected an unlock, an unproven slot shown as USER_SCOPED (L). |
| <a id="b163"></a>[B163](B163-Several-Attempts-At-One-Assignment.md) | L | - | `--attempts N` (at most 5 by default) starts tasks from one commit as a group, with `gate review <group>` and `gate diff`, a reached rate limit said per attempt; approving one rejects the others. |
| <a id="b164"></a>[B164](B164-Rollback-Points-Inside-A-Task.md) | L | - | A helper keeps a host-side bundle of the files at each turn's end, the last 20 by default; `task rewind` restores files and, where declared, the session, offering to restart it. |
| <a id="b167"></a>[B167](B167-An-MCP-Gateway-On-The-Host.md) | L | - | An MCP gateway on the broker for servers over HTTP only: `kind: mcp`, JSON-RPC `tools/list` and `tools/call` read, tool lists compared per tool, `mcp.log`, a project `mcp:` list. |
| <a id="b168"></a>[B168](B168-A-Recording-Of-Each-Session.md) | M | - | A session recording by a read-only tmux control-mode client run from the host, a `.cast` outside the task (an unattended run's `task.log`), kept 30 days, at most 64 MiB, `task replay`. |
| <a id="b169"></a>[B169](B169-Containers-Inside-A-Task.md) | L | - | The nested-container assessment first, measured on both legs; then an opt-in `image.nested_containers`, never for `guarded`, and only on a task's own kernel if the assessment says so. |
| <a id="b170"></a>[B170](B170-A-Devcontainer-File-As-Image-Source.md) | M | - | `image.devcontainer`: a JSONC reader takes `image` or `build.dockerfile` and `containerEnv`, lists the rest as ignored; features are not fetched, and said. |
| <a id="b174"></a>[B174](B174-A-Running-Tasks-Commits-And-Their-Diff.md) | M | - | `TaskCommits` and `TaskDiff`: a running task's commits no remote holds and their diff, cut at a file boundary; a stopped task's list from its note. |
| <a id="b196"></a>[B196](B196-The-Fedora-Repository-Checked-By-Its-Key.md) | S | - | On Fedora the setup script writes the repository with `gpgcheck=0`; it is to be checked against the pinned package key, as on Debian and Ubuntu. |
| <a id="b197"></a>[B197](B197-A-Push-To-A-Slow-Forge-Has-A-Bound.md) | M | - | A push the gate passes on to a forge that is slow or down ends within `limits.pass_on` of the project file, two minutes unless set, with a named refusal that the forge did not answer. |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="b157"></a>B157 | Doctor Says Its Findings In Colour | Doctor colours its findings; `--color` and `NO_COLOR` work, and there is no colour when piped. |
| <a id="b126"></a>B126 | One Source For A Repository Both Ways | One source per repository both ways: a task clones from where its approved work goes. |
| <a id="b127"></a>B127 | An Online Task Pushes Through The Host | An online task's push goes through the host's gate as `porelo/<task>`, with no key or route to the upstream in the container. |
| <a id="b128"></a>B128 | A Gate A Task Cannot Exhaust | The gate's limits: four requests at once, a push spooled to disk, and a scope per gate. |
| <a id="b162"></a>B162 | Task List Names What It Leaves Out | Task list, status, logs and the daemon name the containers that look like tasks but have no recorded id, and adopt none. |
| <a id="b161"></a>B161 | Doctor As Data | `porelo doctor --json`, from the same probe list as the text. |
| <a id="b146"></a>B146 | One Stop Of Every Task On A Node | `task stop --all` and panic stop every task of the machine and journal it in `stops.log`; tokens stay in the vault. |
| <a id="b132"></a>B132 | A Subscription Granted With One Command | `vault authorize <provider>` makes the grant from the provider and the agent, and `task start` names and offers the command. |
| <a id="b131"></a>B131 | The Oldest System A Package Promises | Packages declare their glibc and zlib floor, `check-linkage` fails a build above it, the runners are pinned, and Debian 13 and Fedora 43 are in the package check. |
| <a id="b130"></a>B130 | Two Refusals By Name | `BranchExists` and `EarlierWorkWaits` as named refusals in the daemon, the CLI, the interface and the plugin. |
| <a id="b180"></a>B180 | Trust A Host On Its Own Port | `trust-host` asks for and records a host on the port its upstream uses, so a forge on its own SSH port can be trusted and started. |
| <a id="b182"></a>B182 | A Vault Entry A Declaration Names Is Used | `vault put` says `used for <match>`, where it warned that nothing would find an entry a declaration reads, and warns when the kind does not fit. |
| <a id="b181"></a>B181 | An Upstream Read Before A Start | A start reads the upstream as the gate's clone would before creating anything, names why it cannot with the fix, and offers that fix; `credentials check` connects. |
| <a id="b183"></a>B183 | A Tasks Logs Kept In One Archive | `task logs TASK --zip [FILE]`: the task's logs, its status, files and talk log as text, with checksums and owner-only; the agent's own output only on request. |
| <a id="b188"></a>B188 | No Search Domains Of The Host In A Task | `--dns-search=.` on every task with its own resolver: no search domains of the host, one lookup per name. |
| <a id="b187"></a>B187 | No Logging Warning From The Clearance Watcher | The binary sets SLF4J's own verbosity to errors only, so `clearance.log` no longer starts with three warnings. |
| <a id="b186"></a>B186 | One Line For One Dropped Connection | One line for one dropped connection: kernel timestamps, `queue-threshold 1` and the source port, and a retry within ten seconds is no second line. |
| <a id="b195"></a>B195 | Renamed Porelo Here | Everything this repository builds, ships and documents says Porelo, but for the released changelog entries: artifacts, varlink interfaces, programs, packages, paths, variables, nft and SELinux names and the message signature namespace; a documents test refuses the old word. |

## Rejected

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="b18"></a>B18 | M | - | `StoreCredential` is not built: a credential is entered only in a terminal, the interface's terminal included; the expiry note went to [B178](#b178). |

## Two things that dissolved rather than becoming requirements

Both are recorded where somebody will look rather than dropped.

**Provisioning a machine** is refused and could not have worked anyway - a machine that is not
ready has no daemon to ask - and it is argued in
[the decisions](../../doc/decisions.md#doctor-diagnoses-and-never-repairs). **Routing
credentials to projects** describes a relation that does not exist, and it is argued in
[B18](README.md#b18); restated correctly it is the agent roster, which
was already refused.

**And instructions for an agent are not Porelo's business at all.** They live in the repository,
checked in or not, and Porelo does not know what they are called - `CLAUDE.md`, `AGENTS.md`,
something an agent invents next year. It cannot merge them, because how an agent combines several
is that agent's rule and not ours. A team running more than one agent has to agree upfront how
instructions are stored in their repository; that agreement is theirs to make.

A hardcoded list of filenames would have been worse than nothing: for a feature whose only job is
to show what an agent was told, a name that goes out of date produces a confident *"no
instructions"* for a task that had them, and a blank that looks like an answer is the failure this
project keeps writing down. That is the fourth of the same shape, after the agent roster, hardware
access and key routing - each time the honest answer was *"that is not a thing this system has"*,
and saying so cost a paragraph and bought a screen that is not lying.

## To be checked

Two open questions are worth knowing before any of this is planned in detail, because each
changes what gets built rather than only how:

- Whether a person away from the machine can be reached at all ([B06](README.md#b06)).
  The transport itself is settled - the socket survives an ssh forward, measured - so what is left
  is the half that decides how much of the interface's remote use and notifications is real, and how
  long a clearance prompt should wait for somebody who is not there.
- Whether the guarantees can be re-derived at all on a second platform (`porelo-project` [PJ25](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj25)). It decides whether that project offers the same
  product or a weaker one wearing the same name, and it constrains what may be added to the
  daemon's contract.
