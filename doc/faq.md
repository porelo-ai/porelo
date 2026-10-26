# FAQ

- [Why not lock the agent into a devcontainer?](#why-not-lock-the-agent-into-a-devcontainer)
- [How is Porelo protected against tool poisoning (an MCP server's rug pull)?](#how-is-porelo-protected-against-tool-poisoning-an-mcp-servers-rug-pull)
- [Can an agent reach Maven Central, npm or PyPI while it works?](#can-an-agent-reach-maven-central-npm-or-pypi-while-it-works)
- [Can a task use a proxy or a mirror instead of the public registries?](#can-a-task-use-a-proxy-or-a-mirror-instead-of-the-public-registries)
- [Why does an undeclared host fail silently instead of prompting me?](#why-does-an-undeclared-host-fail-silently-instead-of-prompting-me)
- [Why does the `dnsmasq nftset` line in `porelo doctor` matter?](#why-does-the-dnsmasq-nftset-line-in-porelo-doctor-matter)
- [How do I work on several repositories at once in one task?](#how-do-i-work-on-several-repositories-at-once-in-one-task)
- [If porelo is a command line tool, why is there a daemon `porelod`?](#if-porelo-is-a-command-line-tool-why-is-there-a-daemon-porelod)

## Why not lock the agent into a devcontainer?

A devcontainer sets up a build environment, not a cage for an agent. Inside one, the agent can edit the devcontainer
file in the repository, reach any host on the network, use your forwarded ssh and git credentials, and write straight
into your host checkout. Porelo keeps its limits on the host, blocks undeclared hosts
([egress](how-it-works.md#egress-nothing-is-reachable-unless-named)), and keeps real credentials outside the container
([vault and broker](how-it-works.md#the-vault-and-the-broker-the-container-never-holds-the-credential)). The agent works
on a copy cloned from the gate, and its changes leave only through [the
gate](how-it-works.md#the-gate-work-leaves-only-after-review) — in a `guarded` project, only after you review and
approve them. Keep the devcontainer file as the recipe for the task's image, where Porelo skips its features
([decisions](decisions.md#a-devcontainer-files-features-are-not-fetched)), and let Porelo run the container.

## How is Porelo protected against tool poisoning (an MCP server's rug pull)?

Porelo does not inspect MCP traffic, so it cannot detect changed tools or stop an agent from sending data to its own
allowed MCP server. Instead, it limits what a poisoned tool can make an agent do:

- **No real key in the container:** the broker on the host adds the real key, refuses requests that mint a new token,
  and withholds answers carrying a credential.
- **Deny by default:** a task reaches only its agent, its provider, and the project's `egress`.
- **Controlled output and settings:** work leaves a `guarded` project only through the gate after review, messages
  between agents pass a filter, and the agent cannot change what a project may reach.

See [why you should use Porelo](corporate-security.md).

## Can an agent reach Maven Central, npm or PyPI while it works?

Yes, if the project declares it under `egress`:

```yaml
egress:
  sets: [maven, git-hosting]
  domains: ["nexus.corp.example"]     # a private mirror, if you have one
```

Run `porelo shield sets` to list the available sets, and use `domains` for any host no set covers. Without `egress`, a
task can only reach the hosts for its agent and its provider. Allowed hosts are reachable on ports 80 and 443 only. An
`offline` project cannot declare external `egress` (`sets` or `domains`), only `egress.host_services` on TCP 443 on this
host, so pre-fetch external dependencies during the image build instead, which runs outside the firewall.

## Can a task use a proxy or a mirror instead of the public registries?

Yes, if the mirror is on the network. Add its host name to `egress.domains` and point your build configuration
(`settings.xml`, `.npmrc`) at it. Leave out sets like `maven` if all traffic must go through the mirror. A mirror on
this machine can instead have a name declared under `egress.host_services`, which resolves to the host's gate address
and opens TCP 443 on the host. Use that name in the build configuration; container `localhost` is the container itself.
At image build time, any mirror works because the build is not behind the firewall.

## Why does an undeclared host fail silently instead of prompting me?

Because the host name never resolves. The task's resolver answers only for declared domains, so the program gets a DNS
error before it can connect. Because no connection is tried, there is nothing to prompt about. A clearance prompt only
appears when a connection is tried to an address that is not yet allowed. See [DNS](security.md).

## Why does the `dnsmasq nftset` line in `porelo doctor` matter?

Without `nftset` support, a declared domain resolves, but nothing can connect to it. No error message explains why the
connection fails. If `porelo doctor` shows anything other than `yes`, install `dnsmasq` 2.87 or later with `nftset`
support before starting your first task.

## How do I work on several repositories at once in one task?

A task works on one repository only. To let one agent work across another repository, bring it in as an ordinary
directory with `git subtree add`, and push changes back from the host after review with `git subtree push`:

```
git subtree add --prefix=lib https://github.com/you/lib.git main
git subtree push --prefix=lib https://github.com/you/lib.git the-agents-change
```

Do not use git submodules, because a task's workspace is filled from the gate without them and the gate only receives
the new submodule pointer, not the commit or its files. If the repositories must stay separate, list them all in
`project.yml`, run one task per repository, and let the agents coordinate through their mailboxes (see [messages between
tasks](messages.md)).

## If porelo is a command line tool, why is there a daemon `porelod`?

Every command except `porelo daemon connect` works without `porelod` running. The daemon offers the same functions to
the graphical interface over a socket that only you can open, also from another computer through ssh; it listens on no
network port. It also keeps working between commands: it watches the chat of a running task and the room of a project,
and fetches the projects you follow. `porelo setup` turns it on as a systemd user service for your account. See [the
daemon](running.md#the-daemon).
