# FAQ

## Can an agent reach Maven Central, npm or PyPI while it works?

**Yes, if the project declares it:**

```yaml
egress:
  sets: [maven, git-hosting]
  domains: ["nexus.corp.example"]     # a private mirror, if you have one
```

`porelo shield sets` lists the sets. Use `domains` for a host no set covers.

A task may reach only what comes from these four places, printed when the task starts:

| Source | Example |
|---|---|
| the agent's own `allowed_domains` | `platform.claude.com` |
| the provider it uses | `api.anthropic.com` |
| the project's own `egress` | `repo.maven.apache.org` |

- **No `egress` section means nothing extra.** There is no default.
- **Ports 80 and 443 only.** Declaring `git-hosting` does not open ssh, so no `git push` around the
  gate.
- **An `offline` project cannot declare egress**, and says so.
- **`os-packages-fedora` is incomplete**: `dnf` fetches from mirrors that change by region and day,
  and each one raises a clearance prompt. Pin a baseurl in your image snippet. Debian and Ubuntu are
  covered.

**Declaring a forge weakens the gate.** In a `guarded` project the gate holds because the container
has no credential for the upstream, not because the upstream is unreachable. An agent that finds a
usable token in the work tree can push anyway. Porelo warns about this at task start.

**Seed the image too.** The image build is not behind the firewall, so you can pre-fetch
dependencies there. It makes builds fast and is the only option for an `offline` project:

```yaml
image:
  base_image: "ubuntu:24.04"
  snippet: |
    RUN apt-get update && apt-get install -y --no-install-recommends maven \
        && rm -rf /var/lib/apt/lists/*
    COPY --chown=agent:agent pom.xml /workspace/pom.xml
    USER agent
    RUN mvn -B -f /workspace/pom.xml dependency:go-offline
```

In an `offline` project, build offline so a missing artifact is named clearly: `mvn -o`,
`npm ci --offline`, `pip install --no-index`, `go build -mod=vendor`, `cargo build --offline`.

## Why does an undeclared host fail silently instead of prompting me?

Because the name never resolves. The task's resolver answers only declared domains, so no
connection is tried and there is nothing to prompt about. You see a DNS error:

```
Could not resolve host: repo.maven.apache.org
```

The clearance prompt is for a name that did resolve to an address not yet allowed, such as a bare
IP address. See [DNS](security.md).

## Why does the `dnsmasq nftset` line in `porelo doctor` matter?

Without nftset support, a declared domain resolves but nothing connects, and no error says why. If
`porelo doctor` shows anything but `yes`, install dnsmasq 2.87 or later with nftset support before
your first task.

## Can I use a proxy or a mirror instead?

- **A mirror on the network: yes.** Put it in `egress.domains` and point the build at it
  (`settings.xml`, `.npmrc`). Drop the `maven` set if all traffic must go through the mirror.
- **A mirror on your own machine: no.** A task can reach only the gate on the host, not
  `localhost`. Give the mirror a name the container can resolve, or run it elsewhere.
- **At image build time: either works**, since the build is not behind the firewall. Use this for
  an air-gapped machine.

## How is Porelo protected against tool poisoning (an MCP server's rug pull)?

**Tool poisoning** hides instructions in a tool's description or answers. **A rug pull** changes a
tool after it was approved. Porelo does not read tool definitions, but it limits what a poisoned tool
can make an agent do:

1. **No real key in the container.** The task's token is worthless elsewhere; the broker on the
   host adds the real key. Requests that would mint a new token are refused, and answers carrying a
   credential are withheld. The refusal reads a request body as text, so a compressed body is not
   looked into.
2. **Deny by default.** A task reaches only its agent, its provider and the project's `egress`; no
   class reaches the upstream from the container. In `default`, blocked connections are refused without asking.
3. **Work leaves a `guarded` project only through the gate**, after a person reviews it. An
   `offline` project's work stays on the machine. An `online` project's gate passes the task's own branch on at once.
4. **Messages between agents pass a filter.** A person can still deliver a refused one, and the
   record says so.
5. **The agent cannot change what a project may reach.** A followed project's configuration applies
   only when signed with its pinned key. A project followed `--unverified` is marked as such.

**Not covered:** Porelo does not read MCP traffic, so a changed tool set goes unnoticed. A poisoned
tool can still send data to its own MCP server, which is an allowed destination. An MCP server
started inside the container is not checked.

See [why you should use Porelo](corporate-security.md) for the other questions a security officer
asks.

## Why not lock the agent into a devcontainer?

**Because a devcontainer is built to make a developer at home in a container, not to keep anything in it.** It is
a good way to describe a build environment, and Porelo uses it as one: a task's image can come from the repository's
devcontainer file ([decisions](decisions.md#a-devcontainer-files-features-are-not-fetched)). As a cage for an agent it
fails where a cage matters:

- **The agent can edit its own cage.** The devcontainer file lives in the repository the agent works on. Mounts,
  capabilities, `--privileged`, extra run arguments and the commands run at create and at start are all in it, and
  the next rebuild takes whatever the agent wrote. Porelo's limits live in the project file and on the host, where
  a task cannot write.
- **What it builds is chosen by whoever edits that file.** A feature is a script from a registry, run as root while
  the image builds. Porelo installs none of them and names each one as not installed.
- **The network is open.** A devcontainer reaches every host its machine reaches, and every name resolves. Porelo
  gives each task a firewall that refuses everything not named for its project, and a resolver that answers only
  those names; a task whose firewall cannot be loaded does not start
  ([egress](how-it-works.md#egress-nothing-is-reachable-unless-named)).
- **The credentials come along.** The tooling around a devcontainer forwards your ssh agent and your git
  credentials into it and passes tokens as environment variables, so the agent holds the real keys and can use them
  for anything, anywhere. In Porelo the container holds a phantom token, worthless outside that one task, and the
  real credential is added on the host on the way out
  ([vault and broker](how-it-works.md#the-vault-and-the-broker-the-container-never-holds-the-credential)).
- **The workspace is your checkout.** The container writes straight into the directory on your disk, `.git`
  included: a hook it plants there runs on your host, as you, at your next commit. In Porelo the agent works on a
  copy cloned from the gate, never on your working directory, and its work leaves only as a branch you review and
  approve
  ([the gate](how-it-works.md#the-gate-work-leaves-only-after-review)).
- **The runtime is often the machine's.** A devcontainer commonly runs under a container daemon with root rights or
  is handed its socket, and whoever reaches that socket owns the machine. A Porelo task runs rootless under your own
  account, with a hook that checks every start, and confined by SELinux where the system enforces it.
- **Nothing is written down.** A devcontainer keeps no record of what the agent tried to reach or what it was
  refused. Porelo logs every blocked connection per task, and every message that crossed between tasks in a record
  that can be checked afterwards.

A devcontainer stays the right answer for what it was made for: you, in your editor, with a reproducible toolchain.
For an agent that should not leave, keep the devcontainer file as the image's recipe and let Porelo build the room
around it.

## How do I work on several repositories at once in one task?

**A task works on exactly one repository.** For one agent to work across several, make them one:
`git subtree` brings another repository's files and history into yours as an ordinary directory.

```
git subtree add --prefix=lib https://github.com/you/lib.git main
```

The agent then works on all of it, and its work comes back through the gate as one branch. Taking a
change back to the repository it came from is your step, on the host:

```
git subtree push --prefix=lib https://github.com/you/lib.git the-agents-change
```

That pushes only what is under `lib/`, with its history, and a commit that changed both `lib/` and the
rest arrives there with its `lib/` part alone.

**Git submodules do not work for this.**

- **A task's workspace is filled from the gate without them.** The submodule's directory is empty, and
  `git submodule status` shows it uninitialised. Filling it means fetching from wherever the submodule
  lives, which a task reaches only if the project's `egress` allows that host.
- **A change inside a submodule is a commit of another repository.** What reaches the gate is only the
  new pointer to it, never the commit or its files, so nobody can review or approve the change itself.

**For repositories that stay separate:** a project of your own that names them all in its
`project.yml`, one task per repository, and the agents agree through their mailboxes - see
[messages between tasks](messages.md).
