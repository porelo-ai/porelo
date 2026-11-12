# How far a task can get

What a task can **reach**, **hold**, **spend** and **produce**, and what bounds each. It assumes the
worst case: an agent convinced, by something it read, to work for somebody else.

**Porelo does not detect that.** Any useful task reads text outsiders wrote: the repository, issues,
release notes, build logs. Nothing reliably separates instructions from content. So this page asks
**what a convinced agent is worth**.

Each guard is one of two kinds:
- a **control** holds even against an agent that tries to get round it;
- an **accident-catcher** stops the ordinary mistake and nothing more.

The numbers here are checked against the code by a test.

## Reach: what a task can connect to

| | Bound | What it is |
|---|---|---|
| A name the project did not declare | Does not resolve through the task's resolver | control |
| A declared host | Reachable on ports 80 and 443 only | control |
| The upstream | Never reached by a task, in any class: an online task's gate fetches from it and passes the task's own branch on, from the host; no port to it is open in a container | control |
| An empty `egress` section | Deny, never a default that widens with a release | control |
| A `project.yml` or an adapter's answer about a checkout | Opens nothing and chooses no package until a person says yes at a terminal to that text, read once; a start reads this machine's copy of it, and a change in the checkout asks again; an organization's project takes its signed file. A repository's `porelo.yml` is not part of that question | control |
| A reach for something undeclared | Blocked, and raises a clearance prompt: the closest thing on the machine to a hijack alarm | control |
| The git gate | One address and one port on this machine, never "the machine" | control |
| A declared `egress.host_services` name | Reaches the host's loopback on TCP 443 only, beside the gate's port, including offline; the gate's fallback address is used outside pasta | control |
| Another task | No path: separate containers, no shared workspace. Messages between tasks go through the filter and the peer table, both on the host | control |
| The host's files | Nothing outside what Porelo itself mounts or writes | control |

**Not bounded:**

- **The model provider.** Every task has a working route to its provider, with a request body of
  up to **32 MB**. Anything the agent knows can go into a prompt. **This is the largest open path, and it
  is open on purpose**: closing it closes the task. Porelo records request metadata but raises no alert.
- **Every declared host is a second such channel.** Data fits in a URL to a forge the work clones from.
- **A DNS query sent past the resolver** still gets an answer, though the connection does not.

See [the firewall](security.md) and [DNS](security.md) for how this is enforced.

## Hold: what a task has in its hands

| | Bound | What it is |
|---|---|---|
| The host vault share | Kept by the daemon in locked native memory until lock, daemon exit or reboot; never mounted into a task. Host helpers request temporary copies over the account's owner-only socket | control |
| The provider credential | Never in the container. The vault holds it; the task gets a phantom token, worthless anywhere else, that dies with the task | control |
| Credential headers from the task | `authorization`, `private-token`, `proxy-authorization` and `x-api-key` are dropped before anything is forwarded | control |
| A credential in the provider's answer | The first 8 KB of every answer are examined for `access_token`, `refresh_token` and `id_token` as JSON fields, before any of it is handed over; the rest is examined as it streams, and an answer that carries one later is cut off there. An answer with no type passes only as JSON or as events whose every line is a field and whose every `data:` is JSON, and is withheld or cut off otherwise; a withheld answer is a `400` naming the provider's status, which a client does not retry, or a `502` when the provider itself answered `5xx` or `429` | control |
| A request for a token of its own | A `refresh_token` or `client_credentials` grant is refused, in the query and in the whole body decoded - gzip, deflate or zstd - and a body coded otherwise, coded twice, beyond the limit or not decoding is refused rather than forwarded unread | control |
| A package repository's credential | Never in the container, nor any token for it: the task's repository broker adds it on the host to each request, and the task reads the repository at `127.0.0.1:9421/<name>/` | control |
| A request to a package repository | `GET` and `HEAD` only, under the prefix `tool_repositories` declares: a path with a `.` or `..` segment, plain or encoded, a path parameter, an encoded slash, backslash or percent, or an empty segment is refused before the repository; a range is never asked for; a redirect is followed while it stays under the prefix, at most 5 times, and answered `502` otherwise; every request is logged without its headers | control |
| A package repository's answer | Text, and whatever is typed JSON, YAML or XML, is examined as it streams for the credential, as stored and Base64-encoded, plain, JSON-escaped or percent-encoded, and cut off before it; text the repository compresses, or sends in a charset other than UTF-8, US-ASCII or ISO-8859-1, is withheld. Only the headers a build tool reads come back. A binary answer, such as a jar, passes unread: the prefix the person declares is the bound | control |
| A key for the upstream | Never in the container, nor a socket that answers with it, in any class: the host lends it to the gate | control |
| A guarded task's builds on a host | When the host names them for the project, the host reads its own forge and hands only build verdicts and failed-job logs into `/porelo/files`. The forge credential stays on the host; this opens no task egress | control |
| The workspace's `.git/hooks` and `.git/config` | Stay inside the container, where no host-side `git` runs them | control |
| Additional TLS trust | Only the host authority explicitly placed in the account's owner-only `task-ca-certificates.pem` is copied into a task, read-only; the host clients' `ca-certificates.pem` stays on the host. The system store and Node gain that trust; egress rules still bound the destinations | control |

Through a declared MCP `Arrivals` method, a task can read its own project's published arrival ledger.
The daemon checks and bounds the snapshot as described under [host arrivals](hosts.md#reading-a-repositorys-arrivals).
The restriction to the task's own project is a control.

## Spend: what a task can use up

**Not bounded: a credential that cannot be read can still be spent.** The phantom token can do
whatever the real credential can at the provider: use tokens, spend money. The only bound is the
account's own limit at the provider, which Porelo does not set.

The broker records provider-reported token usage for generation paths ending in `/chat/completions`,
`/responses`, `/v1/messages`, `:generateContent` and `:streamGenerateContent`, and asks for the streamed
Chat Completions usage chunk. Counting endpoints and model listings are excluded. Its owner-only
`usage/<container>.jsonl` record survives removal with a new name and holds counts, model, agent,
provider, credential and tool names, reported cost and end states. It contains no prompt, answer
text, tool arguments, credential values or header values. Missing counts stay unknown. Parsing is
bounded to 1 MiB per body or event and runs off the relay; a failed reading cannot stop an answer.
This is observation, with no ceiling on what a task can spend. See [credentials](credentials.md#what-usage-the-proxy-records).

| | Bound | What it is |
|---|---|---|
| A task's CPU | **4 CPUs** by default, replaced by `limits.cpus` or removed by `none`; the quota throttles CPU time. `OMP_NUM_THREADS` hints the limit to `nproc`, rounded down to at least one unless the image or project sets it. Image builds pass the same hint as a build argument to their `RUN` steps; `getconf` and `sysconf` still read the host's cores | control |
| Its gate on the host | 4 requests at once, `503` beyond; a push spooled to disk, never held; the gate and its `git` in a scope of 1 GiB memory, 256 tasks and half the default CPU weight | control |

## Produce: what a task can leave behind

| | Bound | What it is |
|---|---|---|
| Work, in `guarded` | Leaves only when a person approves it at the gate; nothing the agent can reach calls approve. On the host's forge that approval opens or reuses a pull request as the task's account. An amendment replaces only the gate's recorded last push for that task, with a lease on the live head | control |
| Work, in `offline` | No Git forwarding: stays in the host's Git mirror until a person exports a bundle or fetches it; model calls still reach the model provider through the broker, and declared `egress.host_services` reach the host on TCP 443 | control |
| What the agent installed | Counted with `podman diff` before removal, and dies with the container | control |
| What it reached for | Journaled per task, and kept after the task | control |
| A declaration of how work ended over MCP | Only the task bound to that socket may declare DONE or BLOCKED, never a child or another project's task. The endpoint must allow `Done`; the daemon keeps its outcome, time and a reason of at most 1024 UTF-8 bytes, one line with no control characters, in its private durable record outside every task mount. BLOCKED requires a non-blank reason. The reason reaches the task's reader, never another agent's prompt; task endpoints without Logs or Tail receive no `ended` in List or Watch. A later start clears the declaration | control |
| The file a task hands back | Only for a task started with `--result`, and only the one file of that name in `/run/porelo/result/`: moved out of the agent's reach before it is looked at, never through a link, and kept only when it is a regular file of at most 1048576 bytes of UTF-8 text. The host keeps it, owner-only and outside every task mount, until the next start of the task's name, and answers it only over the person's socket | control |
| `gate protect`'s hook | Fires on a first, an ordinary and a force push. Four things go round it: `--no-verify`, a `core.hooksPath` pointing elsewhere, a fresh clone, and a client built on a git library | **accident-catcher** |
| `gate check`'s author test | The author can be spoofed | **accident-catcher** |

**Not bounded: the work itself.** A plausible but harmful change on the branch the task may write is
stopped by nothing above, only by a person reading the patch. Commit signing does not help: it proves
origin, not intent.

**So the review is ranked.** `porelo gate review` shows first what the task was asked, then every file
that is dangerous by kind, however small the change: CI definitions, build scripts, dependency
manifests and lockfiles, what runs on checkout, files made executable, symbolic links. Then everything
else; reformatting and generated files last. This is not detection; it only sets what a person reads
first.

**The easy way round the gate is a person** fetching the agent's branch and pushing it. Use
`porelo gate checkout` instead: it is the safe way and just as easy.

## When a clearance prompt appears

The agent tried to reach something the project did not declare. Nothing can tell you which of two
things that is:
- an ordinary missing declaration;
- an agent acting on instructions from something it read.

Allow it only if you expected that host. A prompt is asked once per destination and task, and the
answer, or the silence, is journaled. Silence refuses: a prompt nobody answers within 60 seconds is a Deny, and the
destination stays blocked for the run unless a person allows it later from the interface while the task runs.

**This host, as the task reaches it, is never asked about**, nor is any other IPv4 link-local address, such as a
cloud's metadata service. The firewall opens only the gate's port and, for a declared host service, TCP 443 there;
an Allow would open every other port the host listens on. A connection to one of them is a line in `events.jsonl`
and a Deny in the journal by `host`, and no answer from an interface allows it.
