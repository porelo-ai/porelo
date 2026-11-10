# B15 — Handing Artifacts Between Tasks

```json
{
  "$schema": "issue-1.json",
  "id": "B15",
  "title": "Handing Artifacts Between Tasks",
  "state": "Estimated",
  "size": "XL",
  "priority": "Medium",
  "rank": 5,
  "blockedBy": [],
  "covers": "Artifacts over the message transport; splits into the design redone, a gate blob threshold, transport verbs, a drop directory, delivery by hand-in, withdrawal."
}
```

**Why this size.** a contract grown in the transport's repository, a way out of a task, delivery, a record and a gate check.

**What must be true.** What a task builds can reach another task of the project wherever that task
runs, carried by the project's message transport beside its messages, addressed by content,
recorded on the host, and never reachable from inside a task. A
project hands artifacts only once its project file switches it on, and a task can be started with it
switched off; a project without a message transport hands none. These are intermediate results between tasks
that are working, never the work's result, which is built elsewhere, on the build server.

## Why

Neither a repository manager nor Git LFS: what is left is smaller than both, because most of it is
already built and called the gate, and the rest is carried by the message transport a project
already has.

**How it would be built** is
*Design* below.
The artifact path is not built or measured, and it is written for one machine with podman's store as
the storage: it has to be redone around the message transport below before it is built.

One task produces something another needs: a built binary, a dataset, a trained model, a coverage
report, a screenshot of a failure. Today there is no artifact hand-over path. A task's workspace is inside its
container and stays there on purpose; bind mounts include the vault socket, mailbox, screen file,
guide and, when needed, certificates and the signing shim. The conditions are [B14](README.md#b14)'s: it must be
controlled, visible to a person, recorded, restrictable and stoppable.

## Most of this is answered, and the answer is the gate

Before designing anything: a task already has a way to hand its work over. It pushes a branch to the
project's mirror on this machine, the work waits under `refs/porelo/incoming/<task>`, a person reads
it, and `porelo gate approve` is what forwards it. That is versioned, content-addressed,
deduplicated, diffable, reviewed before it moves, host-local, and it needs no credential inside the
container.

**If the work product is source, this requirement has nothing to add.** Anything that is text, that
a person should look at, and that belongs to the repository, is a branch — and building a second
path to the same place would be building a weaker one.

Where that stops working is specific and worth stating as a threshold rather than a feeling: git
carries every version of every blob forever, in every clone. A few megabytes is nothing. A hundred
megabytes of build output, regenerated on each run, permanently enlarges a repository that a person
will clone for years, and no amount of packing takes it back out.

## What an artifact is that a branch is not

Big, opaque, regenerable — and the property that matters most:

**An artifact is content another agent will execute.** This is worse than a message, and the
difference is not one of degree. A message is text that a model may or may not act on; a wheel, a
jar, a container image or a `node_modules` tree is something the next build runs without anybody
deciding anything. One compromised or merely careless task, one artifact, and the second task is
running the first one's code. That is a supply chain between two things that
were isolated from each other on purpose.

**It carries what a message carries, with a far bigger payload.** A conversation leaks at
conversation bandwidth. One put is the entire workspace in a single operation, and it goes wherever
the project's conversation goes.

**And nobody reviews a binary.** This has to be said plainly, because a design that implies
otherwise sells something it cannot deliver. What a person can approve is the *provenance* — which
task made it, in which project, from which commit, how large it is, and what it claims to be — never
the bytes. Every guarantee below is built on recording provenance well, and none of it is a
substitute for someone having read a diff.

## Why not a repository manager

Artifactory, Nexus, a central file store: **no**, neither as something a task publishes to nor as a
second storage behind the host.

**A task publishing to one** puts the storage's credential in the path, needs a new declared host in
every participating project's egress set, and is exactly the side door
a hand push past the gate is about - built in on purpose and reviewed by
nobody.

**A second storage behind the host** would avoid those, and would still be a second thing to run,
authenticate and reach from every machine of the project, beside the message transport that already
reaches all of them with accounts whose secrets stay on the host. One way between a project's
machines is enough.

**And a result is not this requirement's business.** What a consumer outside Porelo - CI, a
colleague, a deployment - takes is built on the build server from the approved work, and published
from there to whatever repository manager the organization runs. What a task hands another task here
is an intermediate result, used while both are working.

## Why not Git LFS

This one deserves a fact rather than a preference, because the name is misleading.

**LFS does not put the bytes in git.** It puts a small pointer file in git and the bytes on an LFS
server, reached over HTTPS with its own authentication. So "versioned in Git LFS" is the repository
manager above in a git costume: a second service to run, a second credential to keep out of the
container, a second host in every egress set.

**And here it costs something specific: the mirror stops being complete.** Today the mirror on the
host holds everything an agent produced, which is what makes reviewing it possible with no network
at all — and is the whole basis of the `offline` class. With LFS the mirror holds pointers, a
review has to fetch, and an `offline` project cannot use it in any form.

**Its failure is also silent.** A checkout without the client gives a hundred-odd bytes of text
where a binary was expected, and nothing reports an error. A guard that presents as a corrupt file
is a guard people learn to work around.

## What is left

**The project's message transport carries artifacts beside its messages, never inside one.** A
message stays what it is - one text part, no file part, within the transport's `max_bytes`, read by
the filter - and an artifact is not text the filter can read. But a transport that keeps a project's
conversation already has what handing a file needs: accounts per task whose secrets stay on the
host, a place every machine of the project reaches, and a way to announce something with the task's
signature beside it. A conversation with no server declared is kept on this machine, and an
`offline` project's reaches nothing beyond it, so one machine is not a special case.

**The artifact verbs are the message transport's contract grown by a few** - put, fetch, remove,
list - which a transport offers or does not, announcing in `describe` its own limit on an artifact's
size. Porelo knows the verbs and nothing about the system behind them: which messaging system that is,
how it stores bytes and how large it lets them be is the transport package's business, and the half
that speaks to one system lives in that transport's repository. A message may announce an artifact by
its digest without carrying it. **A transport never answers across projects**: content addressing
across projects is a covert channel by construction - one project could probe for another project's artifact by guessing its digest, and a hit is itself the answer.

What stays Porelo's, whatever the transport:

- **Taking an artifact out of a task**, through a directory of that task's own that only its own
  helper reads, never a directory two tasks share. A writable directory shared between containers is
  a channel nobody designed: no record of who wrote what, no size limit, no way to interrupt.
- **The checks at the moment of taking**: a regular file only, its size counted against the limit
  per artifact as the bytes are copied, the threshold, the digest. How much a project keeps is the
  messaging system's business, since it decides what it keeps and for how long.
- **The signature.** An artifact is signed on the host that took it, over its digest, with this
  machine's signing key, and a receiving host checks it against the keys listed for the peer before
  anything reaches a task. Messages use task keys held on the host, with certificates checked against the
  receiving project's certifiers; the machine-key artifact signature is not built.
- **Whether it happens at all**: off unless the project file switches it on, and a task can be
  started with it off where the project has it on, never the other way round. One switch covers
  handing on and receiving.
- **The policy**: an artifact stays in its project and travels only in the project's own
  conversation, never in a direct chat ([B122](README.md#b122)), so the
  operator, who is in that conversation, can see what was handed. The project declares what its
  tasks accept, and refusals are outcomes rather than exceptions.
- **The record**, written before the other side can see anything: every artifact a task put or
  received - name, digest, size, media type and the commit it came from - listed for a person who
  asks. The gate shows the diff and nothing else, since an intermediate result is not the work.
  Nothing about an artifact is committed into the work: a pointer file in git would name bytes a
  clone elsewhere may not reach, and the record already ties each artifact to its task and commit.
- **Delivery into a task**, read-only and never executable, into a place of that task's own.

## Notes

**The relationship to [B14](README.md#b14).** The same conversation, the same
transports and the same split between Porelo and a transport, with a different payload; unlike a
message, an artifact never crosses into another project. The record is this requirement's own.

**This is the more useful of the two and the more dangerous.** Handing a built binary between tasks
is a thing people will want immediately; it is also the one that turns two isolated containers into
a supply chain. That ordering — more useful, more dangerous — is the argument for doing it second
rather than first, not for doing it quietly.

**Porelo has no scanner and should not grow one.** Nothing here inspects an artifact for what it
does. The guarantees are provenance, non-execution by default, declaration by the consumer, and a
record — not safety of the content. Saying so is better than a check that catches the careless case
and teaches people to trust it for the deliberate one.

## Design

How [B15](README.md#b15) would be built. **The artifact path is not built yet**: the artifact-specific
classes, paths, methods and files below are proposals, and none of it has been measured. Facts about the
running system are quoted from the code and marked where it matters.

It shares its policy with [B14](README.md#b14)'s design, deliberately. Its record is its
own: [B14](README.md#b14) records in a hash-chained log on each host.
Where that is so, this document points there rather than restating it — two versions of one decision
is the failure `TaskInventory` and `TaskControl` exist to prevent.

### Scope of the first version

**This design predates [B15](README.md#b15)'s decision that artifacts travel
through the project's message transport.** It treats podman's store as the storage, which [B15](README.md#b15) no
longer has. What below is Porelo's - the drop directory, ingest, the record, the policy, delivery, the
daemon's interface - stays Porelo's; storing and fetching bytes move to the message transport's
artifact verbs, the record below is plain rather than chained, the quota per project gives way to a
limit per artifact, retention is the messaging system's, the gate shows no artifacts, nothing crosses projects, and the design
is redone along those lines before anything is built.

One machine. A task produces artifacts; a person sees them; another task in a declared peer project
receives the ones its own project asked for. Out of scope: anything crossing machines, publishing to
a repository manager, expiry, and any inspection of what an artifact contains.

### What is reused

The two host-side halves of this — a content-addressed store, and a way to get bytes into a
container — do not need building. **podman already has both**, and Porelo already requires podman.
Porelo's `HandIns` also delivers files to running tasks, root-owned and mode `0644`, in `/porelo/files`;
the artifact path does not yet use it.

| Need | Reused | Left to build |
|---|---|---|
| Content-addressed storage | `podman artifact add` / `ls` / `inspect` / `rm` — the local OCI artifact store | The per-project namespace and the index over it |
| Delivery into a container | `podman run --mount type=artifact,src=…,dst=…`, read-only, one blob selectable by digest | The mid-run case, which a mount cannot serve |
| Identity | The OCI digest, which the store computes anyway | Nothing |
| Provenance format | in-toto attestation | Filling it in |
| Policy, refusals, record, quota | Nothing | All of it |

`podman artifact` was experimental when it appeared and **is stable as of podman 5.6**; 5.7 added
`inspect --format` and artifact lifecycle events. Both acceptance legs are above that floor —
Ubuntu 26.04 ships podman 5.7.x, Fedora 44 ships 5.8.x — but Porelo today only refuses podman *4*,
so **using this raises the minimum to 5.6**, which needs a `doctor` probe that names it rather than
a put that fails strangely.

What podman does **not** provide is everything this requirement is actually about. Its store is per
*user*, not per project, and it has no notion of who may hand what to whom. The namespace, the
policy, the quota, the record and the refusals below are unchanged by this.

### The shape

```
  task A container                     host                              client
  ┌──────────────────────┐   ┌──────────────────────────────┐        ┌──────────────┐
  │ agent writes a file  │   │ porelo store serve (A)        │        │ frontend/CLI │
  │  /run/porelo/out/  ───┼──▶│  1 rename out of reach       │        └──────┬───────┘
  │                      │   │  2 copy, count, quota        │               │
  │  /run/porelo/in/   ◀──┼───│  3 podman artifact add       │─── podman's   │ varlink
  │    (read-only)       │   │  4 journal (chained)         │    artifact   │
  └──────────────────────┘   │  5 publish                   │    store      │
                             │              porelod ◀────────┼───────────────┘
  task B container           │                │             │
  ┌──────────────────────┐   │        StorePolicy decides   │
  │  /run/porelo/in/   ◀──┼───│ porelo store serve (B)        │
  └──────────────────────┘   └──────────────────────────────┘
```

No socket is shared, no directory is shared, and the store is mounted nowhere. Each task has its own
drop directory and its own inbox, both bind-mounted from **its own** state directory — the same kind
of mount `/run/porelo/vault.sock` already is.

### What gets built, module by module

| Module | What is added |
|---|---|
| `store/` (new) | `Artifact`, `ArtifactIndex`, `ArtifactJournal`, `StorePolicy`, `StoreService`, `PutOutcome`, `StoreException`. There is no `ArtifactStore` and no `Digest`: podman holds the bytes and computes the digest. |
| `core/src/main/java/org/fuin/porelo/core/project/` | `Store` on `Project`; `ProjectReader` learns one key. |
| `apps/app/` | `StoreCommand` and subcommands, `StoreServeCommand`, `StoreWiring`, `StoreEdit`, one entry in `TaskHelpers`, one method on `PoreloPaths`, and one more `Probe` in `DoctorCommand` for the podman floor. |
| `runtime/` | `Podman` learns the `artifact` subcommands; `ContainerSpec` learns `--mount type=artifact`. |
| `daemon/` | New types and methods on `org.fuin.porelo.Tasks1`. |
| `agents/api` | Nothing. This needs no agent capability at all — see below. |

**A naming note before it causes confusion:** `InstallArtifact` already exists in `agents/api` and
means something else entirely — a file an agent's installation downloads. The type here is
`org.fuin.porelo.store.Artifact`, in a different module, and the two should never appear in one
sentence without qualification.

### Why a drop directory rather than a protocol

[B14](README.md#b14) needs the agent to take part in a second repository and to read what arrives there mid-run, which
is a convention it has to be told about. **Producing a file is less than that.** Every agent can write a file, so the mechanism can be a
convention instead of a capability:

- **`/run/porelo/out/`** — writable, bind-mounted from `context.paths().tasks().containerState(container)
  .resolve("out")`. The agent writes finished files here. Nothing else reads it but this task's own
  helper.
- **`/run/porelo/in/`** — mounted read-only, populated by the helper according to the project's
  declaration, with a `manifest.json` naming each file, its digest, its size, and which project and
  task it came from.

That is the whole container-side interface: two directories in a place the agent already has, no
new binary in the image, no MCP, no field in the agent definition, and nothing in Porelo that names
an agent.

**The mount is not a shared directory.** Each is under one task's own state directory. Two tasks
never see the same inode, which is the property the requirement turns on.

**Reading that directory from the host is safe; running anything in it is not.** This looks like the
rule that keeps the host out of `/workspace`, and the difference is worth writing down: that rule
exists because `.git/hooks`, `core.pager` and `core.fsmonitor` make *executing git* in an
agent-controlled directory a way to run agent-controlled code. Hashing bytes executes nothing. So
the helper may read, and nothing anywhere may run a program out of either directory.

**It also survives a container that died**, which `/workspace` does not — the same reason the
clearance journal is not under the runtime directory. An artifact produced by a run that then failed
is still on the host.

#### Delivery: a mount at start, the same directory mid-run

**At container start** an artifact the project already accepts is given to podman directly:
`--mount type=artifact,src=<ref>,dst=/run/porelo/in/<name>`. Nothing is copied and nothing is
extracted, and read-only is the runtime's property rather than a permission Porelo has to keep
correct. A single blob can be selected by digest where an artifact holds several.

**Mid-run it cannot be.** A mount is decided at `create`/`run`, which is the shape the ruleset and
the resolver already have: what a container has is built when it starts. So an
artifact handed to a task that is already running is **extracted** into that task's own `in/`
instead. The two paths deliberately land in the same directory, so an agent has one place to look
and never has to know which way it arrived.

### Ingest, step by step, and why the order is that order

1. **Rename out of reach.** `Files.move` from `out/` into a staging directory in the task's state
   directory that is **not** mounted into the container. After this the agent has no path to the
   file. Doing anything else first is a race with a process that is still running.
2. **Refuse anything that is not a regular file.** Opened with `LinkOption.NOFOLLOW_LINKS`, checked
   before it is read. **A symbolic link is refused, never followed** — an agent that drops a link to
   the operator's vault file, a key in the SSH folder or `/etc/passwd` would otherwise have the helper
   read it, hash it, store it and hand it to another project. This is the single most important line
   in this design.
3. **Copy it to a private file, counting bytes as they are copied.** A rename is not enough on its
   own: the agent may still hold an open descriptor to that inode and write through it, so what is
   handed on must be a copy nothing else has a handle on. The count comes from the copy rather than
   from `stat`, because the length can change between the two and the quota is the thing being
   cheated. Exceeding the quota or the per-artifact limit aborts the copy, removes the partial file,
   and names the number.
4. **`podman artifact add` the copy.** The digest is the store's own, so nothing hashes twice, and a
   failure here is a failure to store rather than a half-stored artifact.
5. **Journal, then publish.** The entry is appended before the artifact is visible anywhere. A
   journal that cannot be written means the artifact is not published — the same ordering [B14](README.md#b14) uses,
   and for the same reason.
6. **Receipt.** A line is written into `out/.receipts/<name>.json` giving the digest, so the agent
   knows what was stored. Nothing about it is committed into the work; the gate shows it from the
   journal.

Failure at any step leaves the file in staging and says so, rather than deleting the only copy of
something a run spent an hour producing.

### The store is podman's

Porelo lays out no directory of its own. It owns the **names** and the **index**; podman owns the
bytes.

**The reference carries the project** — `porelo/<project>/<name>` — so `podman artifact ls` is
readable by a person looking for what a project holds. It is not the authority: podman has no notion
of a project and cannot be asked to enforce one, so Porelo's own index decides what exists, and the
journal below is what it is rebuilt from.

**Nothing in a task can reach the store.** No podman socket is mounted into any container, which is
what keeps a per-*user* store from being a per-machine channel. That was already true; it becomes
load-bearing here, so it is asserted in a test rather than assumed.

**The deduplication nuance, written down because it reads like a contradiction of the
requirement.** OCI blobs are addressed by digest, so two projects producing identical bytes share
one blob on disk whether or not Porelo would like them to. The rule the requirement states is about
what is *observable*: the index is per project, `Artifacts` never answers across projects, and a
digest belonging to another project is answered exactly as a digest that exists nowhere. The
physical deduplication happens; the oracle does not — unless somebody measures free disk, which is
an open question below rather than a solved one.

**Not executable, and read-only where it lands.** An artifact mount is read-only by construction,
and anything extracted into an inbox is written `0644` with no executable bit, whatever the dropped
file carried.

### The record

**`$XDG_STATE_HOME/porelo/artifacts/<project>.jsonl`**, `0600`, one JSON object per line, chained:
`prev` is the previous line's `hash`, the first line's `prev` is 64
zeros, `hash` is SHA-256 over the line's canonical `Json.write` serialization with `hash` removed.
`porelo store verify <project>` walks it and names the first line that does not verify.

**Each line carries an in-toto attestation rather than a shape invented here.** in-toto is
CNCF-graduated and its subject is exactly this: recording who did what, where and how. A statement
names a *subject* — an artifact, by digest, which is how in-toto already addresses one — and a
*predicate* carrying the rest. It is a format, not a service, so it costs nothing at runtime and
means the record can be read by tooling nobody here wrote.

```json
{"seq":12,"prev":"…","hash":"…","statement":{
  "_type":"https://in-toto.io/Statement/v1",
  "subject":[{"name":"build.tar.zst","digest":{"sha256":"9c1e…"}}],
  "predicateType":"https://porelo.fuin.org/Handover/v1",
  "predicate":{"event":"put","project":"porelo","task":"porelo-a1b2","bytes":48210114,
               "media":"application/zstd","commit":"6e4187a…","at":"2026-09-08T11:02:44Z"}}}
```

**The statement is not wrapped in a signed DSSE envelope**, which is how in-toto is usually carried.
The chain is the integrity mechanism here and no key goes near a container. An envelope becomes
worth adding under exactly the condition [B14](README.md#b14) names: a record that leaves the machine which produced
it — which is why [B14](README.md#b14), whose record does, signs on the host.

`event` is `put`, `hand`, `receive`, `withdraw` or `refused`. **A refusal is recorded too**: a
policy that only writes down what it allowed cannot answer the question anybody actually asks after
an incident.

`commit` is the provenance that cannot be reconstructed later — the revision the workspace was at
when the file was dropped. The gate's view gives only the last pushed commit, so it does not meet this
provenance requirement. How the helper learns the workspace revision remains open below.

### Policy

```yaml
project:
  name: "porelo"
  security_class: "guarded"
store:
  quota: "5g"
  max_artifact: "512m"
  peers:
    - "porelo-frontend"
  accepts:
    - from: "porelo-frontend"
      names: ["coverage-*.json"]
```

`ProjectReader` learns `store`; `Project` gains a `Store` record beside `Egress` and `Limits`;
editing goes through a `StoreEdit` built like `EgressEdit` — replace the key where it stands, leave
unknown lines alone, and parse with `ProjectReader` before writing.

**`StorePolicy` is the only thing that decides**, asked by the daemon and the CLI alike. Its rules,
in order:

1. Both projects name the other in `peers`. One-sided is refused.
2. Neither project is `offline`.
3. Both projects are the same security class. A `guarded` artifact handed to an `online` project
   reaches an upstream through its peer, and neither end did anything forbidden.
4. The receiving project's `accepts` matches. **The consumer declares; it does not discover.**
5. The receiving project is within quota.

Refusals are **outcomes, not errors**, as `SetEgress` and `WidenTask` already answer.

**If [B14](README.md#b14) is built, rules 1 to 3 are `TalkPolicy`'s and must be shared rather than copied.** They are
the same sentence about the same file.

### The daemon's interface

Additions to `org.fuin.porelo.Tasks1`, which only grows.

```
type Artifact (
  # sha256:<hex>. Identity is the content; pass it back unchanged.
  digest: string,
  project: string,
  # Container name of the task that produced it.
  task: string,
  name: string,
  bytes: int,
  media: string,
  # Revision the workspace was at, or "" when the task had not committed anything.
  commit: string,
  at: string,
  # Projects it has been handed to, in the order it happened.
  handed: []string
)

type ArtifactOutcome (
  STORED, HANDED, NOT_DECLARED, REFUSED_BY_CLASS, NOT_ACCEPTED, OVER_QUOTA, TOO_LARGE,
  NOT_A_REGULAR_FILE, NO_SUCH_ARTIFACT, ALREADY_HANDED
)

# What a project holds. Never answers across projects in one call: a client that can ask for
# everything is a client that can be asked for everything.
method Artifacts(project: string) -> (artifacts: []Artifact)

# Artifacts as they are produced and handed, for a view that is already open. Streaming only.
method Handovers() -> (artifact: Artifact, event: string, outcome: ArtifactOutcome)

# Hands one to another project. Deliberate, recorded, and refused as an outcome.
method Hand(digest: string, to: string, dryRun: ?bool)
  -> (artifact: ?Artifact, outcome: ArtifactOutcome)

# Removes it from the store. Says what still points at it; does not pretend to recall what was
# already handed over.
method Withdraw(digest: string, reason: ?string)
  -> (outcome: ArtifactOutcome, referencedBy: []string)

error NoSuchArtifact(digest: string)
```

`Hand` takes `dryRun` for the reason `SetEgress` and `WidenTask` do: it is consequential, and what
it would do should be answerable before it is done.

### The command line

```
porelo store list [--project P]
porelo store show <digest>
porelo store hand <digest> --to <project> [--dry-run]
porelo store withdraw <digest> [--reason ...]
porelo store verify [--project P]
porelo store serve ...            # the helper, not for people
```

Rendering only. Every refusal comes from `StorePolicy`, so the CLI and a remote client cannot come
to disagree about what is permitted — the failure that would otherwise be an artifact handed over
remotely that the machine's own tooling says is impossible.

### Lifecycle

| Event | What happens |
|---|---|
| `task start` (new task) | Start the helper, mount directories and accepted artifacts, record the command.[^start] |
| A file appears in `out/` | Ingest, as above. Watched with a directory watch, with a sweep on helper start so nothing dropped while it was down is missed. |
| `store hand` | Policy, journal, then it appears in the receiver's `in/` and its `manifest.json` — mounted if the receiver has not started, extracted if it is already running. In that order. |
| `task stop` | The poststop hook reaps the helper by its pid file. `out/` is swept once more before it is given up, so a file written at the end is not lost. |
| `task start` (existing task) | Replay the command and restore `in/` from the record.[^resume] |
| A container that never started | `TaskRunner.reapOrphans` covers the helper, since no hook fires for a container that never ran. |
| `task remove` | The store is **not** touched; removing an artifact is `store withdraw`. |
| `porelo panic` | The helper is stopped with everything else. Nothing is removed. |

[^start]: The helper starts in phase `BEFORE`; `out/` and `in/` are created `0700` on the host and mounted;
    artifacts the project already accepts are mounted with `--mount type=artifact`; the command is recorded
    in `resume.json`.
[^resume]: The recorded command is replayed. `in/` is rebuilt from the record, so a resumed task finds what it had.

The store is on the host; outliving the container is the point.

### Failure modes, and what each must do

| When | What must happen | Why it is listed |
|---|---|---|
| A symlink is dropped in `out/` | Refused and recorded, never followed. | Otherwise the helper reads the operator's vault, ssh key or anything else it can open. |
| The agent holds the file open and keeps writing | The stored copy is what was read; the quota counts what was written. | `stat` then read is a race, and the quota is the thing being cheated. |
| The journal cannot be written | Nothing is published or handed. | "A person sees everything" stops being true and nothing detects it. |
| The quota is exceeded mid-copy | Abort, remove the partial file, name the number. | A partial artifact with a real digest is worse than none. |
| The disk fills | The put fails loudly. | A full disk otherwise presents as "podman cannot start a container". |
| The receiving project does not accept the name | Refused as `NOT_ACCEPTED` and recorded. | The consumer declares; discovery would be the covert channel. |
| An artifact is withdrawn after being handed | Removed here, recorded, and the answer says it cannot be recalled from where it went. | Bytes cannot be un-given, and saying otherwise is the dangerous lie. |
| Two tasks produce identical bytes in one project | One artifact, one set of bytes, both provenances recorded. | Dedup within a project is free and correct. |
| The same bytes in two projects | One blob on disk, two index entries, and no way to observe the sharing. | The oracle is closed at Porelo's API, not at podman's storage layer. |
| A hand-over to a task that is already running | Extracted into its `in/`, never mounted. | A mount is decided at create; pretending otherwise produces an artifact nobody can find. |
| podman is older than 5.6 | `doctor` says so by name and a put refuses. | The artifact suite was experimental before that, and an experimental store is not a record. |

### What must be proven to fail

Per the rule that a test nobody has watched fail is a test nobody has checked — and that the
**right** thing must fail:

- Drop a symlink to a file outside the mount and assert the store does not contain the target's
  bytes. Assert on the store's content, not on the wording of the refusal.
- Drop a fifo, a directory and a device node. Each refused.
- Write past the quota from a still-open descriptor after the length was `stat`ed, and assert the
  refusal happens and no oversized file is stored.
- Assert the stored file's mode has no executable bit and the inbox mount is read-only, by trying to
  write to it from inside a container.
- Hand across two classes and watch it refuse. Mutate the class in the fixture and watch the refusal
  disappear, to prove the test is reading the class and not something correlated with it.
- Edit one journal line, and separately remove one, and assert verification fails in both cases —
  removal is the case a per-line hash without a chain misses.
- Make the journal unwritable and assert the receiving task's `in/` stays empty.
- Ask for an artifact by a digest belonging to another project and assert the answer is the same as
  for a digest that does not exist anywhere. **The two must be indistinguishable**, or the oracle is
  back. This is now a test of Porelo's index rather than of its storage, because podman's store does
  deduplicate across projects.
- Assert that no podman socket is reachable from inside a task container. It was always true; it is
  now the thing that keeps a per-user store from being a per-machine channel.
- Run the put path against a podman older than 5.6 and watch `doctor` name it, rather than watching
  a put fail for a reason nobody can read.

Everything involving a real mount, a real container writing into `out/`, and a read-only `in/`
belongs in the acceptance suite — unit tests must run without a container runtime.

### Alternatives considered

The question this design was re-examined against: can an existing, maintained, open-source tool take
the host-side halves, leaving only an adapter? For storage, **yes** — and it is already installed.
For everything else, no.

| Tool | What it gives | Verdict |
|---|---|---|
| **[`podman artifact`](https://docs.podman.io/en/latest/markdown/podman-artifact.1.html)** | A local OCI artifact store with `add`/`ls`/`inspect`/`extract`/`push`/`rm`, digests, and `--mount type=artifact` into a container. Stable [since podman 5.6](https://github.com/podman-container-tools/podman/releases/tag/v5.6.0); Ubuntu 26.04 ships 5.7.x, Fedora 44 ships 5.8.x | **Taken.** No new dependency, no port, no credential, no egress. |
| **[in-toto](https://in-toto.io/) attestations** (CNCF graduated, February 2025) | A standard statement for *who did what, where and how*, addressed by artifact digest | **Taken**, as the journal's line format. A format, not a service. |
| [zot](https://github.com/project-zot/zot) + [ORAS](https://oras.land/docs/) | A full OCI registry with htpasswd auth and identity-based **per-repository** authorization over glob paths — the same shape the git gate already runs on loopback | **Later, not now.** The right answer once artifacts must leave the machine; `podman artifact push` reaches it with the format unchanged, so nothing has to be redesigned to get there. |
| [git-annex](https://git-annex.branchable.com/special_remotes/) | The honest version of the LFS idea: pointers in git, content-addressed bytes, and a plain local directory as a special remote — **no server** | Rejected, but on cost rather than principle: a Haskell runtime, symlink-based worktrees that are awkward inside a container, and the *agent* would have to drive it. Evidence of current maintenance is indirect. |
| Git LFS, Artifactory, Nexus | — | Rejected in the requirement: a second service, a second credential, permanent egress, and an incomplete mirror. |

**What did not get taken off the pile:** the per-project namespace, the class rules, the quota, the
record, the refusals, and the drop directory with its symlink and descriptor traps. Those are the
requirement, and no store answers them.

### Deliberately not designed here

- **Publishing to a repository manager.** Argued in the requirement: it belongs after the gate, as
  the operator's act on something approved, not as a task's.
- **Scanning artifacts.** Porelo has no scanner and should not grow one. The guarantees are
  provenance, non-execution, declaration and a record.
- **Signing.** The digest is an integrity property and says nothing about authorship, authorship
  comes from the transport, and a key an agent can reach is a key it can copy. [B14](README.md#b14) signs on the host
  only because its record leaves the machine; this one does not.
- **Cross-machine.** The daemon binds no network interface in any configuration.
- **Expiry.**

### Open questions this design leaves

- **How the helper learns the commit.** Provenance needs the revision the workspace was at, and
  nothing may run git inside the container or read `/workspace` from the host. Reading it from the
  gate's mirror gives the last *pushed* commit, which is not the same thing and may be
  significantly older.
- **What `in/` should do when the same artifact is handed twice**, or when a newer artifact has the
  same name. Overwriting is a file that changes under a running agent; refusing means a project
  cannot iterate.
- **Whether a directory watch is enough.** A sweep at start and stop covers the gaps this design
  knows about; whether an agent writing a large file in place produces a partial ingest depends on
  how the watch fires, and that has to be measured rather than assumed.
- **Whether free disk is a usable oracle.** podman deduplicates blobs by digest across the whole
  user store, so writing bytes that already exist consumes nothing. A project that can observe its
  own quota accounting closely enough might learn that another project holds the same bytes. The fix
  if it matters is to charge a project for what it put rather than for what was stored, which is
  cheap — what is unknown is whether the difference is measurable from inside a task at all.
- **How the reference is named.** `porelo/<project>/<name>` is readable and it is also a namespace
  podman does not enforce, so two Porelos sharing a user account would collide. Nothing does that
  today, and the alternative — an opaque reference and a lookup — costs the `podman artifact ls`
  legibility that makes the store debuggable by hand.
- **Whether the podman floor should be 5.6 or higher.** 5.7 added artifact lifecycle events, which
  would let the helper notice changes it did not make instead of assuming it is the only writer.
  Both test legs are already above 5.7; the floor is about what an operator's machine must have.
- **Whether the quota should be bytes, count, or both.** A thousand small artifacts is a different
  problem from one enormous one, and `Limits` models memory, cpus, pids, `handIn` and subagents.
  The `limits.hand_in` project setting is a per-file byte limit, not a storage quota.

## Plan

Read in the code on 2026-11-04. The artifact path is not built; the design file is to be redone first, around the
message transport rather than podman's store.

**What is reused.**

- **Delivery into a task is `HandIns`** (`apps/app-model/src/main/java/org/fuin/porelo/app/HandIns.java`,
  the daemon's `HandIn`, `TakeBack`, `HandIns`): a file arrives in parts, is checked by size and sha256,
  written as root into `/porelo/.incoming` and
  renamed into `/porelo/files`, mode 0644, in a directory the agent cannot write, and every hand-in is a line in a
  record. That is "read-only and never executable, into a place of that task's own" already; an artifact received
  is a hand-in whose record line names the artifact, the peer and the signature.
- **The transport contract** is `TransportDescription`, `TransportSend`, `TransportPoll` and `TransportLifecycle`
  (`apps/app-messaging/src/main/java/org/fuin/porelo/app/`): `describe` is read as JSON and `takes` lists
  the options a transport accepts beyond the common ones. The artifact verbs join it the same way, and `describe`
  announces them and the size limit.
- **The signature** uses SSHSIG over exact bytes, with a namespace of its own, in `MessageIntake`;
  `MessageDelivery` verifies it and `MessageSigners` traces the task key's certificate to the receiving project's
  certifiers. An artifact is to be signed on the host over its digest with the machine's key, under another
  namespace so one cannot be replayed as the other.
- **The push limit** at the gate is `GitHttpServer`'s `LimitedInputStream` over the whole request; a limit per blob
  is a check on the received pack, which is new.

**The split**, each an issue of L or smaller:

1. **The design redone (S).** *Design* below rewritten: the transport's verbs, a
   drop directory per task, `HandIns` for delivery, the plain record, no podman store.
2. **A blob over the threshold refused at the gate (M).** A project key (`gate.max_blob_bytes` or similar, with a
   default) in `ProjectSchema`; the gate's receive path checks each new blob's size (`git cat-file
   --batch-check` over the received objects) and refuses naming the number. Independent of the rest; red first:
   a push carrying a blob over the threshold that waits for review.
3. **The artifact verbs (L, with the transport's repository).** `put`, `fetch`, `remove`, `list` with their
   arguments and exit codes, the `describe` fields, and a stand-in transport for the tests in `testing/`. Red first:
   a stand-in transport offering the verbs needs no change in `porelo` to carry an artifact.
4. **Taking an artifact out (L).** A drop directory bind-mounted from the task's own state (as the mailbox is),
   read by a host-side helper of that task only: regular files only, symbolic links refused, size counted as copied
   against the project's limit (never above the transport's), sha256, the commit the workspace was at, the record
   line written, then the signed put. The project switch (`artifacts:` in `project.yml`) and `task start
   --no-artifacts`. Red first: a symbolic link in the drop directory that is followed.
5. **Receiving, listing and withdrawing (M).** The accept declaration in `project.yml`, fetch and signature check,
   delivery through `HandIns`, `porelo artifact list` and `withdraw` naming the tasks that have it, the daemon's
   methods. Red first: an artifact put with mode 0755 that arrives executable, or one not declared that arrives.

## Acceptance

- An artifact leaves a task through a channel Porelo owns. **No directory is shared between two
  tasks, and no storage is mounted into one or reachable from it.** Seen to fail: a test that
  inspects a task's mounts goes red when a storage, or a directory another task writes, is among
  them.
- **Artifacts go only through the project's message transport, and Porelo names no messaging
  system.** No code outside a transport knows one or how it stores bytes, and a second transport
  adds nothing to `porelo`. Seen to fail: a stand-in message transport offering the artifact verbs,
  written for the test, needs a change in `porelo` to carry an artifact between two tasks.
- A project whose message transport does not offer the artifact verbs, or that has none, is refused a
  hand-over, naming why. Seen to fail: such a hand-over waits, or is refused without the reason.
- An artifact is signed on the host that took it and checked against the peer's listed keys before it
  reaches a task. Seen to fail: a stand-in transport that hands back bytes with no signature, or with
  another key's, has them delivered.
- No transport credential enters a task: it is kept in the vault and handed to the transport on the
  host. Seen to fail: a search of a task's environment, files and process arguments finds it.
- Identity is the content — `sha256` — so a second put of the same bytes is the same artifact, and
  the digest is what every record and message names. Seen to fail: two puts of the same
  bytes yield two artifacts.
- Deduplication is within a project. Two projects cannot learn anything about each other's
  artifacts, including by asking for a digest. Seen to fail: a request from one
  project for another's digest answers differently from a request for a digest that exists nowhere.
- Nothing about an artifact is committed into the work, and the gate shows only the diff. A person
  who asks is shown, from the record, every artifact a task put or received - name, digest, size,
  media type and the commit the workspace was at. Seen to fail: an artifact a task put or received is
  missing from that listing, or a file naming it appears in the work.
- An artifact from a `guarded` task goes to the transport when it is taken, as the task's messages
  do - signed and recorded first - without waiting for the task's work
  to be approved. It is not filtered, and that is said where a person reads what `guarded` keeps on
  the machine. Seen to fail: a `guarded` task's artifact waits for approval, or the statement is
  missing.
- An `offline` project's artifacts stay on this machine, as its messages do. Seen to fail: an
  `offline` project's artifact reaches anything its conversation may not.
- Provenance is recorded at the moment of the put — the task, the project, and the commit the
  workspace was at — because it cannot be reconstructed afterwards. Seen to fail: a put whose record
  lacks the task, the project or the commit.
- An artifact is delivered **read-only and never executable**, whatever mode it was written with,
  and the project **declares what its tasks accept** rather than discovering what exists. Seen to
  fail: an artifact put with mode 0755 arrives writable or executable, or one the project did not
  declare arrives at all.
- Handing files is off unless the project file switches it on. A task can be started with it off in
  a project that has it on, and then neither hands on nor receives; nothing switches it on for a
  task whose project has it off. Seen to fail: a project whose file says nothing hands or receives an
  artifact, a task started with it off does either, or a task start switches it on against its
  project.
- An artifact never leaves its project: it travels only in the project's own conversation, never to
  another project and never in a direct chat, and an attempt is refused, naming why. Seen to fail: a
  hand-over to another project's task, or one sent into a direct chat, arrives.
- Every put, every hand-over and every removal is in a plain record on the host that outlives both
  tasks, not chained, and written **before** the artifact is visible to the other side. Seen to
  fail: the record is gone after both tasks are removed, or the other side sees the artifact before
  the entry exists.
- A file larger than the project's threshold is refused at the gate when it arrives as a git blob,
  naming the threshold, which has a default a project file can change. Seen to fail: a push carrying
  a blob over the threshold waits for review, or the refusal does not name the number.
- How long an artifact is kept is the transport's business: Porelo removes nothing on a timer and
  cleans nothing up. A project file may set a lifetime per artifact, which Porelo passes with every
  put; a transport that announces in `describe` that it keeps one applies it, and for one that does
  not, checking the project file says the lifetime has no effect there. Seen to fail: Porelo removes
  an artifact nobody withdrew, a set lifetime is not passed with a put, or a transport that cannot
  keep one is not named when the project file is checked.
- An artifact has a size limit, set in the project file and never above what the transport announces
  in `describe`. Exceeding it is a refusal naming the number, not a warning, enforced against the
  bytes as they are copied rather than against what a file claims to be. There is no quota per
  project. Seen to fail: an artifact over the limit is handed on, or the refusal does not name the
  number.
- Nothing that is not a regular file is taken. A symbolic link in a drop directory is refused, not
  followed. Seen to fail: a symbolic link in a drop directory is stored or followed.
- A person can see what exists, where each came from, and can withdraw one. Withdrawing removes it
  from the transport and records it, so no task receives it afterwards; a task that already has it
  keeps it, and the withdrawal names those tasks. It is never called a revocation. Seen to fail: a
  task receives a withdrawn artifact, or a withdrawal of one that was handed over does not name the
  tasks that received it.
- Nothing here widens what a container may reach: no port, no firewall element, no resolver entry,
  no new host in any egress set. Seen to fail: a task's ports, firewall elements, resolver entries
  or egress set differ with this in use.

## To be checked

- **The artifact verbs** (with the transport's repository): their arguments, what `describe`
  says about them, and their exit codes - settled by writing one transport that crosses machines against them.
- **What a message transport can carry** (the transport repository's agent), measured per transport rather than
  assumed: the largest artifact its system accepts, what keeps the bytes and for how long, and who can read them
  there. Each transport answers this for itself in `describe` and in its own documentation.
- **How the helper learns the commit**. Provenance needs the revision the workspace was at, and
  nothing may run git inside the container or read `/workspace` from the host. The gate's mirror gives the last
  pushed commit, which may be older.
- **What happens when an artifact of the same name arrives twice**. `HandIns` replaces a file of the
  same name today; for an artifact that is a file changing under a running agent.
