# Porelo

<img src="doc/images/early-bird.svg" width="350" alt="Early bird - work in progress">

> **Early bird - work in progress.** Porelo is not stable yet: until release 1.0.0, its code, commands
> and file formats can change without notice.

**Sandboxing AI agents in YOLO mode using Podman and native Java/GraalVM.**

<img align="left" height="400" width="260" src="doc/images/porelo-400.png" alt="Porelo with AI agent in podman">

Normally, an AI agent will stop and ask you: "Can I edit this file?" or "Can I run this terminal command?". 
In YOLO mode, it skips those questions and directly modifies code, deletes files, installs packages, 
or runs terminal scripts completely unattended.

Porelo makes that safe anyway: It runs each agent inside a hardened, rootless container with default-deny
outbound networking, a credential vault that keeps real keys on the host, a git checkpoint for every run, and
a desktop notification path for live allow/deny decisions.

Use the agent as you want: Work locally: interactive in a shell, or headless and unattended.

Supervise agents from a Flutter client — wherever they run :construction:

<br clear="left"/>

**Documentation: [porelo-ai.github.io/core](https://porelo-ai.github.io/core/)**, one chapter of
[all of Porelo's documentation](https://porelo-ai.github.io).

> [!NOTE]  
> **Porelo runs on Linux only.** The containment is kernel machinery — an nftables
> ruleset loaded into the container's network namespace, OCI hooks, user namespaces,
> the kernel keyring — none of which exists on macOS or Windows, where a container
> runtime would put all of it on the far side of a virtual machine.

### What it guarantees

- **Fail-closed egress** — a default-deny firewall is loaded into the container's network namespace before the
  agent runs; if it cannot be loaded, the container does not start. A project names what its build may reach,
  e.g. `egress: {sets: [maven]}`, and nothing else resolves.
- **The key stays on the host** — the container holds a task-scoped stand-in token; a proxy swaps in the real
  credential on the way out, and a subscription sign-in is renewed on the host. No task holds a key for git.
- **Work leaves only through the gate** — a task pushes to a gate on the host; in a `guarded` project nothing
  reaches your repository until you approve it, and an `online` one passes only the task's own branch on.
- **Nothing to escalate to** — a rootless container, every capability dropped, `no-new-privileges`.
- **An agent is a package** — its own `.deb`/`.rpm` with its CLI pinned by checksum; Porelo names no agent in its
  code.

> Porelo is inspired by [Terok AI](https://github.com/terok-ai/terok) — not a fork and not a port, but it owes that
project a great deal: the architecture, and a lot of hard-won knowledge about how podman, nftables and D-Bus actually
behave. Big kudos to its developers. If you are more at home in Python, use it. It's a cool project!
Why build this rather than use it? See [why](doc/why.md)

## Supported providers and agents

<table>
<tr>
<td valign="top">

<table>
<tr><th>Provider</th><th>State</th></tr>
<tr><td><a href="https://docs.anthropic.com">Anthropic</a></td><td>:white_check_mark:</td></tr>
<tr><td><a href="https://openrouter.ai">OpenRouter</a></td><td>:white_check_mark:</td></tr>
<tr><td><a href="https://platform.openai.com">OpenAI</a></td><td>:construction:</td></tr>
<tr><td><a href="https://ai.google.dev">Google</a></td><td>:construction:</td></tr>
<tr><td><a href="https://github.com/features/copilot">GitHub Copilot</a></td><td>:white_check_mark:</td></tr>
<tr><td><a href="https://docs.x.ai">xAI</a></td><td>:construction:</td></tr>
<tr><td><a href="https://z.ai">Zhipu</a></td><td>:construction:</td></tr>
</table>

</td>
<td valign="top">

<table>
<tr><th>Agent</th><th>State</th></tr>
<tr><td><a href="https://github.com/anthropics/claude-code">Claude Code</a></td><td>:white_check_mark:</td></tr>
<tr><td><a href="https://github.com/earendil-works/pi">Pi</a></td><td>:white_check_mark:</td></tr>
<tr><td><a href="https://github.com/openai/codex">Codex CLI</a></td><td>:construction:</td></tr>
<tr><td><a href="https://antigravity.google/cli">Antigravity CLI</a></td><td>:construction:</td></tr>
<tr><td><a href="https://github.com/github/copilot-cli">GitHub Copilot CLI</a></td><td>:construction:</td></tr>
<tr><td><a href="https://github.com/xai-org/grok-build">Grok Build</a></td><td>:construction:</td></tr>
<tr><td><a href="https://github.com/sst/opencode">OpenCode</a></td><td>:construction:</td></tr>
<tr><td><a href="https://github.com/can1357/oh-my-pi">Oh My Pi</a></td><td>:white_check_mark:</td></tr>
</table>

</td>
</tr>
</table>

## Getting started

**Debian and Ubuntu**

```sh
sudo apt install -y ca-certificates curl gnupg
curl -fsSL https://fuinorg.jfrog.io/artifactory/api/security/keypair/porelo-packages/public -o /tmp/porelo.asc
gpg --show-keys --with-colons /tmp/porelo.asc \
  | grep -q '^fpr:::::::::BF27A2FA637F505CAFBE431768D19D175D904415:' \
  && sudo gpg --dearmor -o /usr/share/keyrings/porelo.gpg < /tmp/porelo.asc
echo "deb [signed-by=/usr/share/keyrings/porelo.gpg] https://fuinorg.jfrog.io/artifactory/porelo-dist-deb releases main" \
  | sudo tee /etc/apt/sources.list.d/porelo.list
sudo apt update
sudo apt install -y porelo porelo-agent-claude
```

**Fedora and RHEL**

```sh
sudo tee /etc/yum.repos.d/porelo.repo >/dev/null <<'EOF'
[porelo]
name=Porelo
baseurl=https://fuinorg.jfrog.io/artifactory/porelo-dist-rpm/releases
enabled=1
gpgcheck=0
EOF
sudo dnf install -y porelo porelo-agent-claude
sudo /usr/share/porelo/selinux/install-selinux-policy.sh
```

**Then, as the user who will run agents:**

```sh
porelo setup                  # hooks, the daemon, and the vault (asks for a passphrase twice)
porelo doctor                 # says what this machine still lacks, and what to do about each
porelo vault login claude     # the agent's own sign-in, stored in the vault - never in a container
cd ~/src/my-repository
porelo task start             # a task on this repository, in the built-in project 'default'
```

Already signed in to Claude Code on this machine? `porelo vault import claude` copies that instead. An API key
goes in with `porelo vault put anthropic --type api-key`. A project with settings of its own is followed from its
repository: `porelo project follow <project> <git-url> --signed-by "ssh-ed25519 AAAA..."`.

[Getting started](doc/getting-started.md) explains each step and what goes wrong without it.

## Documentation

The pages of this repository's chapter on [the site](https://porelo-ai.github.io/core/), readable here as well:

- [How it works](doc/how-it-works.md) — tasks, projects, the gate, the vault and egress, and a glossary
- [Commands](doc/commands.md) — every command, and what to type for a given job
- [The project file](doc/project-file.md) — everything a `project.yml` can say
- [Security](doc/security.md), [what a task can reach](doc/reach.md) and [credentials](doc/credentials.md) — how
  an agent is kept in its place
- [For whoever decides whether an agent may run in an organisation](doc/corporate-security.md)
- [Running it](doc/running.md) — preparing a machine, the daemon, your own tooling in a task
- [FAQ](doc/faq.md), [three ways of working](doc/way-of-working.md)
- [Adding an agent](agents/README.md#onboarding-a-new-agent), [requirements](issues/README.md),
  [building from source](doc/build.md)
- [Contributing](CONTRIBUTING.md), and [reporting a vulnerability](SECURITY.md)

## Modules

What each Maven module is, in a few sentences of its own:

- [bom](bom/README.md), [wire](wire/README.md), [core](core/README.md), [testing](testing/README.md) - the versions,
  the records that cross a boundary, the domain model, the tests' fixtures
- [runtime](runtime/README.md), [shield](shield/README.md), [clearance](clearance/README.md),
  [vault](vault/README.md), [gate](gate/README.md), [supervisor](supervisor/README.md) - what runs around a task
- [agents](agents/README.md), [builds](builds/README.md) - the contracts agents and build readers are written against
- [apps](apps/README.md) - the `porelo` command line, area by area
- [daemon](daemon/README.md), [hooks](hooks/README.md) - `porelod` and the OCI hooks
- [acceptance](acceptance/README.md) - scenarios at a real terminal
- [dist](dist/README.md) - the packages

## Licence
GNU General Public License v3.0 or later — see [LICENSE](LICENSE).

-----

> [!NOTE]  
> <img src="doc/images/ai-powered.svg" alt="A little robot peeking out of its sandbox" align="left" height="62">
> This project is fundamentally powered by AI.
> <br clear="left"/>
