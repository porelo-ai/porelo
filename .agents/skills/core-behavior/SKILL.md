---
name: core-behavior
description: How porelo's parts behave: containers, firewall, credentials, tasks, messages, the daemon, clearance, CLI output. Use when changing any of these parts.
---

# core-behavior

## How the parts behave

### Containers and podman

- **A rootless container's agent user is a subordinate uid** (1001 on `ubuntu:24.04`), so a socket the container must
  reach is world-writable inside a `0700` directory.
- **A rootless container reaches the host at `169.254.1.2`**; `host.containers.internal` exists only inside it.
- **An agent's refused hosts come from its installed package's definition** (`refused_domains`), so what a dry run
  or a task refuses depends on the agent version installed: a test's expectation is measured with the version the
  test host has.
- **Under pasta a task's connection to its host goes to `169.254.1.2`, a link-local address**: the shield records a
  drop there and denies it without asking, since an Allow would open every port the host listens on. The host's
  public address is the task namespace's own, so a connection to it never leaves the task.
- **`XDG_DATA_HOME` is podman's storage**: a test redirects something narrower.
- **Give a container a literal loopback address, never `localhost`**, which Node resolves to `::1` first.
- **The gate binds loopback, mapped in by a `CONTAINERS_CONF_OVERRIDE` file that only `podman start` reads**
  (`LoopbackMapping`); `--network pasta:...` would replace pasta's defaults and leave an undeclared address open, and
  under slirp4netns `task start` binds every interface and says so.
- **A bind-mounted socket is bound to the inode that existed at start**, so helpers declare whether they must be up
  before the container or need it running, and start in that order.
- **`podman diff` lists what was installed in a container**, counted before removal; `/etc` and `/var` always show.
- **`podman cp <task>:/workspace` works on a container that cannot start.**
- **`podman cp container:/dir target` copies the directory into the target**, so a copy of its contents names `dir/.`.
- **`{{.Labels}}` prints Go's `map[a:b c:d]`**: read one label with `{{index .Labels "org.fuin.porelo.project"}}`.
- **A repository's image is `porelo/<project>_<repository>`, built in `build/<project>_<repository>`, never with
  `--`**: a project name may hold `--`, and `Project.projectOfImage` reads the project back out of the image's name.
- **The runtime's state is a phrase** (`Exited (143) Less than a second ago`), and `Age.compact` renders the instant.
- **A Containerfile here-document needs podman 5**: what an agent adds to an image is one `RUN`, continued with
  backslashes, in its definition's `installAsRoot`.
- **podman has no "rebuild from here"**: an `ARG` at the seam between the packages layer and the agent's layers,
  given a value podman has not seen, invalidates the cache from that line on, which is what `--rebuild AGENT` is.

### Firewall, names and SELinux

- **An nftables set holds addresses and a project names hosts**, so a grant and a withdrawal are two operations.
- **`nft add element` answers `File exists` for an address already there**, which `EgressPolicy.allow` treats as done.
- **The resolver re-reads its servers file on `SIGHUP`**, which is how a live widening reaches it.
- **`dnsmasq --nftset` is what makes a declared name reachable**; `doctor` probes for it, and `no-nftset` contains
  `nftset`.
- **SELinux runs `nft` confined, unable to read the operator's files, with the denial unaudited**, so `NftHook` feeds
  the ruleset on stdin.
- **The hooks and shield run `nsenter` and `nft` from a fixed root-owned directory, never through `PATH`**
  (`SystemProgram`); in rootless podman's namespace the host's root reads as the overflow uid, and a `/proc` file,
  which reports size 0, is read through a stream, since `Files.readString` truncates it in a native image.
- **SELinux checks `connectto` against the serving process**, so a socket a container reaches is labeled at
  `socket()` through `/proc/thread-self/attr/sockcreate` (`SocketContext.openUnixSocket()`); clearing it writes a
  NUL byte.
- **`ausearch -m AVC -ts recent` can miss what `/var/log/audit/audit.log` holds**: grep the file.
- **`podman unshare` runs as `container_runtime_t`**, which needs its own SELinux grant.
- **A socket forward connects as `sshd_session_t`**, OpenSSH's split session process, which selinux-policy 44 refuses
  on the daemon's socket with nothing audited, even with the dontaudit rules off; `porelo_socket` grants `sshd_t` and
  `sshd_session_t` connect only, each in an `optional` block. The loaded module's version cannot be read, so `doctor`
  asks `/sys/fs/selinux/access` what it grants.
- **Hook descriptors count only where podman reads them**: each `hooks_dir` replaces the last, so a later drop-in
  switches Porelo's off; `HookInstaller.registration` tells the states apart, `doctor` exits 69, and a task refuses.
- **A package upgrade does not re-run `porelo setup`**: `Registration.STALE` compares contents, and a task start
  repairs `MISSING` and `STALE` and asks podman again.
- **A local build shadows a packaged one** - hooks and agents alike - and `doctor` names what is in use.

### Credentials and the vault

- **`java.net.http.HttpClient` exposes HTTP/2 pseudo-headers**, so `VaultProxy.forwardable` drops every header
  starting with a colon.
- **An agent needs its base-URL variable as well as its socket variable**, or it uses its compiled-in endpoint; DNS
  lookups for the provider are not a bypass, the proxy's request log is the evidence.
- **An agent that takes only a URL gets `porelo vault relay`** on `127.0.0.1` in the task's namespace, forwarding to
  the broker, which keeps the host's DNS, egress and credential.
- **A provider is data, a YAML file under `/usr/share/porelo/providers`; an agent is code.** The dialect's path
  belongs to the endpoint, never to the agent; an agent whose client adds that path to its base URL itself declares
  `provider.adds_path: true` and gets the broker's bare address in `base_url_env`.
- **A passphrase is verified before it is cached**, and `vault init` asks twice; `KernelKeyring.available()` probes
  the keyring once with `request_key`, not only whether `libkeyutils` loads, because podman's default seccomp allows
  `keyctl` reads and refuses `request_key` and `add_key`.
- **`vault lock` does not reach a running task**, whose broker holds its credential until it stops, and says so.
- **A sign-in is stored whole** - refresh token (hidden as `grant/<name>`), its end, token endpoint, client id - or
  it dies within hours; its token URL comes from the agent's extractor, never from the credentials file.
- **Every OAuth endpoint is `https`, or plain `http` on a literal loopback address only** (`Grants.secure`), which is
  also all a test's `vault login --credentials-from ... --token-url` may name.
- **The broker renews a minute before the end, inside one vault update under its lock**; from a task, a
  `grant_type=refresh_token` request is refused and an answer carrying a token field is withheld.
- **The broker streams `text/event-stream` as soon as the first bytes arrive**, without waiting to fill its 8 KB
  inspection buffer, and passes every method and `Mcp-Session-Id` both ways so an MCP session holds.
- **An imported sign-in is never renewed**, since renewing the copy ends the developer's own session.
- **Without a terminal there is no passphrase prompt**: `porelo setup` leaves the vault, says so and exits 0.
- **An agent's permission prompts are switched off by Porelo** through `AgentDefinition.sandboxedCommand()` on both
  start paths; a consent dialog a flag causes is answered by a settings file the agent declares, and the API-key
  billing dialog is refused, never answered.
- **An agent's first-run files are placed by `TaskLaunch.placeAgentFiles`**, with an empty token when there is no
  credential.
- **A task's route tokens are kept in the vault with the agent's, under `task/<container>/route/`**, and put back
  when the task starts again, so a restart does not change the tokens the container holds.
- **The minting-grant guard in `VaultProxy` matches `refresh_token` and `client_credentials` alike**: it refuses the
  container asking, not the broker's own purchase, made on the host side of the socket; a test fails if the pattern
  narrows back to `refresh_token`.
- **`TokenPurchase` is synchronized, which is the single flight**, and its failure messages never echo what the
  authorization server sent back, which may carry the secret.
- **Text Porelo did not write reaches a line only where it is known to hold no secret**: a service's error is repeated
  only as a code OAuth defines (`Grants.named`), a failing passphrase command by its exit code, and every value handed
  to a transport is withheld from its standard error, in its failure and its streamed lines alike. `porelo vault
  credential` answers a pipe, a socket, a file or `/dev/null`, never a terminal.

### Tasks and their helpers

- **A task's helpers are `porelo` re-invoked**, so their path comes from `PoreloBinary.path()`, never
  `ProcessHandle.current()`.
- **Take the helper census before stopping**: the poststop hook reaps the helpers and their pid files.
- **A running task answers "already up"** on resume, and a recorded helper still alive is not started twice.
- **No hook fires for a container that never started**: `TaskRunner.reapOrphans` stops its helpers.
- **A hook writes its failure to `hooks.log`**, since the runtime discards its stderr.
- **`/run/user/<uid>` is cleared when the last session ends**, so a task survives a logout only with lingering, and
  comes back after a reboot only from its saved record, through `task start --restarted`.
- **Project and class are podman labels as well as in `sidecar.json`**, and the container name is never parsed for
  them.
- **A task is the container whose id Porelo recorded**; every act goes to that id, never to a name.
- **The workspace stays inside the container**: a host directory would run the agent's `.git` hooks and config on
  the next host-side git command, so nothing reads a workspace from the host.
- **A stopping task writes what it holds unpushed into its state directory**, read back by a later removal; a task
  with no note is refused, not guessed at, and `task status` asks without destroying anything.
- **`readable: false` is not "holds nothing"**, and `WorkHeld` runs git in the container, so it is never asked per row.
- **A rescue that pushed nothing exits non-zero**; it commits first.
- **A non-zero exit or a signal keeps the container, stopped**, even with `--rm`; `Teardown`'s shutdown hook takes a
  lock so Ctrl-C cannot leave a task running without its helpers.
- **A shell's last exit status is not a verdict on the task.**
- **`porelo panic` stops every task and removes nothing.**
- **`task start` creates or resumes, and does not attach**; `task attach` is the separate step.
- **`tmux new-session -A -s porelo` is the session**, its scrollback pinned in `/etc/porelo/tmux.conf`; `porelo` is not
  in a task image, which carries `curl`, `ca-certificates`, `git`, `openssh-client` and `tmux`.
- **The workspace is checked out on an unborn HEAD**, never on an empty directory, with the branch asked of the
  mirror, and `remote.porelo.push = HEAD:$PORELO_TASK_REF` makes a bare `git push` reach the gate; the gate's git
  credential is scoped to its own URL (`http.<gate>.extraHeader`), never set on `http.extraHeader`, which git sends
  to every HTTPS host.
- **`ContainerName.isPorelo` and `isTask` differ**: a login container carries only a timestamp after the prefix, and
  one left over is swept by the next `vault login`.
- **Where an agent's session id is - an event field in a headless run, session files in a driven one - is declared by
  the agent package beside `supports_resume` and `resume_flag`**; the id is read from what the host already owns
  (`task.log`, the config directory Porelo writes) and kept in the task's durable state, never in `/run/user`.

### Messages

- **The host owns the mailbox's layout**: it makes every directory when it makes the task, and nothing that reads
  or carries a message - the filter, a transport - creates one; finding one missing, it refuses or defers.
- **Every hop of a message is a rename** between directories whose names are the states, and nothing between the
  outbox and the transport rewrites a message's bytes, because the signature is over them.
- **Which transport queue a message goes to is the host's decision after resolving `metadata.to` against the peer
  table, never the filter's**, and a transport runs on the host, never in a task and never in the sluice's process.

### The daemon and its contract

- **`porelod` speaks varlink on an owner-only socket and listens nowhere else**; remote access is an ssh forward or
  `porelo daemon connect`.
- **A socket file outlives its process**: a daemon is asked (`org.varlink.service.GetInfo`), never found by its file,
  and a second daemon on a live socket refuses and names the holder.
- **`porelo daemon connect` copies bytes and flushes on every read**; one invocation is one connection.
- **A stream that closes without a final reply was cut short**, never finished.
- **varlink carries no session**, so an interactive session is ssh with a pty, running Porelo's own verb.
- **One question, one implementation** (`TaskInventory`, `TaskControl`, `TaskLaunch`, `GateSupport`), which the CLI
  and the daemon only render.
- **A field added to a record that crosses varlink is two edits**, and `SetupContext.parameters()` is compared with the
  record by a test.
- **`Tasks1`'s `1` is the compatibility promise**: within it the interface only grows, a breaking change is `Tasks2`
  served beside it, and an added enum value is not breaking.
- **A parameter name is a varlink identifier** (`credentialType`, never `credential-type`).
- **A client derives nothing the daemon can send**: a clearance prompt carries its own `key`.
- **A reply gains a value only where the reader's sentence claims an act** (`Lock`'s `wasCached`).
- **A listing never reaches the network**: a fetch is its own method.
- **A tail sends 64 KB chunks 200 ms apart**, so a backlog drains at about 320 KB/s.
- **A registry two processes write is a directory of files**, one per entry.
- **The daemon hands out paths and takes back only those**, reporting a moved file as absent.
- **A refusal is a named value, never a sentence to parse**, and every interface renders that value rather than
  deciding again.
- **An agent's end is asked only of a log quiet for ten seconds, and once per size**: asking at every pass would
  start every agent for every running task twice a second.
- **A long-lived background pass runs on a platform thread of its own (`BackgroundPass`), and every `VarlinkServer`
  serves each connection on a platform thread**, so work pinned in a native call can never take the carriers a call
  on the socket needs; `BackgroundPassTest` holds the first.
- **`porelod` is built with `--enable-monitoring=threaddump`**, so `SIGQUIT` dumps its threads to the journal without
  stopping it; keep that flag.

### Clearance

- **Both ways into the clearance watcher broadcast** through `ClearanceService.publish`.
- **Decisions are appended to `~/.local/state/porelo/clearance/<container>.jsonl` and read back on start**, and a
  restored allow is put back into the firewall.
- **Every decision is broadcast with its `verdict`**, and an expired prompt is replaced by a second `Notify` with the
  first one's id, never closed.

### What the CLI says

- **A diagnostic names the next action**: `Probe` refuses any state but `OK` without one, and `UNKNOWN` is a state.
- **A doctor probe asks the machine through the context** (`context.runner()`, `context.podman()`), never a process
  builder of its own, so a machine state is faked in a unit test; `DoctorCommand.ready` is the one rule the exit code
  and the daemon's `ready` share.
- **Absence and failure are never rendered alike**: a lookup that could not answer is its own outcome, never "nothing
  there" (`KernelKeyring.forget` answers `UNKNOWN` beside `CLEARED` and `NOTHING_CACHED`); a read path that asks
  anyway may fold them, a path that claims an act may not.
- **A refusal never names a way out that is unavailable in the state it refuses in.**
- **Colour goes through picocli's `Ansi.AUTO`**, painting only work that exists nowhere else and a clearance of
  `off`.
- **An unhandled failure is one line and a path**: `CliErrors.failures(paths)` writes the trace to
  `$XDG_STATE_HOME/porelo/failures.log`, owner-only, with the command's name and never its arguments.
- **A shell in a directory a `clean` removed is caught by `PoreloCli`** in one line.
- **`task list` shows what `TaskInventory` computes**, and `TaskInventory.isLog` is the one allow-list of what a log
  is.
- **A name's shape is no evidence that it names something**: a task exists as a container or by its logs.
- **Counts cross the boundary and the sentence stays where it is read** (`Held.phrase()`); a note of an unknown
  version is refused.
- **Porelo never changes an existing `project.yml`**: a change answers the entry a person writes, built through the
  model and `ProjectFileWriter.entry`; only a new file is written.
- **A process wrapper streams a long command's lines as they arrive**, so a killed one leaves them.
- **A verb that ends something answers for everything the machine lists**, whoever filed it.
