# B219 — A Private Package Repository Through The Broker

```json
{
  "$schema": "issue-1.json",
  "id": "B219",
  "title": "A Private Package Repository Through The Broker",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 50,
  "blockedBy": [],
  "covers": "A task reads a company's authenticated package repository through a second, token-less broker on its own socket and relay: GET and HEAD under a declared prefix, the vault's credential added, no credential or phantom token in the task."
}
```

**Why this size.** a second kind of broker route that carries no token and answers binary bodies, a new `project.yml` key
and its contract, a second relay per task, `doc/reach.md` rows, an accepted risk, and an acceptance step against an
authenticated HTTPS repository the suite must stand up.

**What must be true.** A task builds against a package repository that needs authentication - a company's Nexus or
Artifactory behind Maven, npm, pip or Gradle - while the credential stays in the vault on the host and nothing
credential-shaped, not even a phantom token, enters the task.

## Why

Today a private mirror is only reachable as a declared domain (`doc/faq.md`), so its credential has to be in the
task's `settings.xml` or `.npmrc`. [B15](README.md#b15) rejects a repository manager as something a task publishes
to; this one only reads through the broker, and publishing still happens after the gate.
A project's draft `project.yml` is proposed
from a checkout's build tools, and a `configure` verb writes the tool settings this issue needs.

## The shape proposed

- **`project.yml` declares the repository, not only its host**: a URL prefix and the vault entry holding its
  credential (a name such as `repository/corp-nexus`).
- **The broker serves the task as the tool's mirror**: the task's tool settings point at a broker endpoint with no
  credential; the broker drops every credential header the task sent, adds the real one (Basic, Bearer, or a header
  such as `X-JFrog-Art-Api`) and forwards over TLS. The task is known by its own socket.
- **Read only**: GET and HEAD pass, every other method is refused, so a task cannot deploy to the company's
  repository with the company's credential.
- **Only the declared URL prefix**, never the whole host, and every request is logged as the provider's are.
- **Running tasks only.** A `snippet` fetching from the private repository during the image build is
  [B223](README.md#b223); mTLS client certificates are out of scope.

## Plan

Read in the code on 2026-10-20.

**What exists and what does not fit.** `VaultProxy` (`supervisor/.../VaultProxy.java`) is one broker per task on
`<state>/vault.sock`, bind-mounted at `/run/porelo/vault.sock`; `porelo vault relay` (`SocketRelay`) gives it
`127.0.0.1:9419` inside the task's namespace (`CredentialWiring.startRelay`). Three things in it are wrong for a
package repository, and none should be loosened there:
- a route is chosen by the phantom token's scope (`VaultProxy.java:444`), and a request without one is a 401;
- an answer must be `application/json` or `text/event-stream` (`UNDERSTOOD_TYPES`), so every jar is a 502;
- there is no method allow-list and no path prefix.

**So it is a second broker kind beside the first, not a mode of it.**
1. **`project.yml`**: a key `repositories_read` (name open), each entry `name`, `prefix` (https URL), `credential`
   (a vault entry). `ProjectSchema` lists it, `ProjectReader` reads it into a record `PackageRepository`, `Project`
   gains the list, `CheckProjectFile` refuses a prefix that is not https or has a query. `doc/project-file.md` gets
   the section.
2. **The credential** is a vault entry put with `vault put --type basic|bearer|header --setting header=...`;
   `VaultPutCommand` already takes a type and settings. `repository/` is not a reserved prefix (`VaultNames`), so
   nothing new is hidden.
3. **`RepositoryProxy`** in `supervisor/`, sharing `HttpHead`, `HOP_BY_HOP` and `CREDENTIAL_HEADERS` with
   `VaultProxy`: its own socket `<state>/repository.sock`; the path's first segment names the repository; only GET
   and HEAD; the rest of the path, normalized, must stay under the prefix (`..` and encoded slashes refused);
   credential headers dropped, the real one added; `Redirect.NEVER`, a redirect inside the prefix followed by the
   proxy, one outside answered 502 and logged. A text answer (`text/*`, JSON, XML) is scanned as it streams for the
   credential's own value and its Basic encoding, and cut off where either appears; a binary answer is streamed
   unread. Each request is logged as `request ...`, method, path and status, never a header.
4. **Wiring**: `TaskRunner` mounts the second socket only when the project declares a repository;
   `CredentialWiring` starts a second relay on `127.0.0.1:9420` beside 9419 and puts `PORELO_REPOSITORY_<NAME>_URL`
   (`http://127.0.0.1:9420/<name>/`) into the environment. The relay is a recorded helper, restarted on resume like
   the first.
5. **Tool settings** are not written by `porelo`: core names no build tool. The environment variable is the
   contract; the build-tool plugin's `configure` writes `settings.xml`, `.npmrc` or `pip.conf` from it.
6. **`doc/reach.md`**: rows for the method, the prefix and the redirect, each `control`; `ReachDocumentTest` reads
   the method set and the port off the code, and a row saying a binary answer is not scanned. `doc/credentials.md`
   gets a section.
7. **`doc/decisions.md`**: the accepted risk, with its index row. The exposure: a binary answer under the prefix
   could carry the credential and is streamed unread. Why it stays: a jar or a tarball cannot be scanned, and
   refusing them breaks every build; the prefix is the control, and the person chooses it. What would change the
   answer: a repository found to put its credential into a binary answer under a usual prefix.

**Red first:** a `RepositoryProxyTest` against a local HTTPS server that demands Basic auth: a GET through the proxy
with a task's own `Authorization` header reaches the server with the vault's credential and not the task's; a PUT, a
GET outside the prefix, `../` out of it and a redirect to another host are each refused and never reach the server;
a text answer carrying the credential's value is cut off before that value reaches the task.

## Decided in building (2026-11-12)

The repository is a `tool_repositories` entry ([B467](README.md#b467)) with a credential, not a second key.
- **Why.** [B467](README.md#b467) already declares a build tool's repository by `ecosystem`, `name` and `url`,
  refuses a bad name or a URL that is not https, and hands it to the ecosystem adapter's `Configure`, which writes
  the tool's settings. A `repositories_read` key would declare the same repository twice, in two shapes, and leave
  the settings to a variable no adapter reads.
- **The entry.** `credential` is optional: the name of a vault entry (`repository/corp-nexus`). Without it the
  entry is as [B467](README.md#b467) built it. With it, `url` is the prefix the broker serves: https, with no query
  and no fragment. Its host needs no `egress` line, since the broker reaches it from the host, not the task.
- **The adapter gets the broker's address.** For an entry with a credential, `Configure` is given
  `http://127.0.0.1:9421/<name>/` as the repository's URL. The contract's `Repository.url` is already "its URL
  inside the task", so the contract does not change. The task's environment also carries
  `PORELO_REPOSITORY_<NAME>_URL` with the same address, for a tool no adapter configures.
- **The port is 9421**, not 9420: 9420 is the default port of a sign-in's redirect on the host (`CodeGrant`).
- **The credential's form** is the vault entry's type: `basic` (the value is `user:password`; the broker encodes
  it), `bearer`, or `header` with the setting `header=<name>` (such as `X-JFrog-Art-Api`).
- **No socket in the task.** The relay enters only the task's network namespace and dials the broker's socket on
  the host, so the second socket is not mounted into the container (plan part 4 mounted it).
- **Plan parts this changes.** Part 1: no new key; `ToolRepository` gains `credential`, and the schema, the reader,
  the writer and `doc/project-file.md` with it. Part 4: the address goes into `Configure` as well as the
  environment, the port is 9421, and no socket is mounted. Part 5: the settings are written by the adapter from
  `Configure`, as [B467](README.md#b467) built it, not from the environment variable.
- **[B474](README.md#b474).** Its plan puts a package route into the agent's broker (`VaultProxy`) with a phantom
  token in the settings. This broker is the token-less one for the same kind of read; [B474](README.md#b474) could
  serve its project's snapshots as one more repository of this broker, its credential the task's host-issued
  token, once someone decides so. Nothing here changes `VaultProxy` or blocks [B474](README.md#b474) as planned.

## Built (2026-11-12)

- A `tool_repositories` entry takes `credential`, a vault entry's name. With one, its `url` must be https, without
  user information, query or fragment, and without a `.` or `..` segment, plain or encoded; its host needs no
  egress line. `ToolRepository`, the binder, the writer, `ProjectSchema` and `doc/project-file.md` know it.
- `RepositoryProxy` in `supervisor` is the second broker kind beside `VaultProxy`, with no token. It passes `GET`
  and `HEAD` only and answers every other method `405`. The path's first segment names the repository. A path
  with a `.` or `..` segment, plain or encoded, an encoded slash, backslash or percent, an empty segment, a control
  character, or a target that is no path is refused before the repository.
- It drops every credential header the task sent, its cookies and its wish for a compressed answer, and adds the
  vault's credential. It follows a redirect itself while it stays under the prefix, at most 5 times; a redirect
  that leaves is answered `502`, unfollowed, and its target is not repeated.
- A text answer is scanned as it streams for the credential, as stored and Base64-encoded, and a basic's password
  of 8 or more characters alone, and cut off before it; the cut is logged. Text that comes compressed is withheld.
  A binary answer passes byte for byte, unread. Each request is logged by method, path and status, never a
  header, a query or a credential.
- `porelo vault repositories` serves it for one task on `<state>/repository.sock`. It reads the credentials once
  and refuses an entry that is not `basic` (`user:password`), `bearer`, or `header` with its `header` setting.
- A start runs it when the project declares a repository with a credential, before the container, and a second
  relay, `repository-relay`, on `127.0.0.1:9421` in the task's namespace after it. Both are recorded helpers; a
  resume points the relay at the new container. A repository whose credential the vault lacks is said and left out.
  A project with none gets neither.
- `Configure` is given `http://127.0.0.1:9421/<name>/` for such a repository, and the task's environment carries
  `PORELO_REPOSITORY_<NAME>_URL`. Nothing credential-shaped enters the task.
- `doc/reach.md` and the daemon's `Reach` have three rows, read off the code by `ReachDocumentTest`;
  `doc/decisions.md` keeps the accepted risk of the binary answer; `doc/commands.md` names the command.
- The tests use a plain-http repository on loopback; the broker's decisions do not depend on TLS. The acceptance
  against an authenticated HTTPS repository is still to be run: it needs a repository that demands Basic auth over
  TLS (a Nexus or Artifactory, or a stand-in serving a jar), its authority in the account's added authorities, the
  credential stored with `porelo vault put --type basic`, and a task whose `curl` and Maven build fetch through it.

## Acceptance

- In a task, `curl $PORELO_REPOSITORY_CORP_URL<path>` fetches a jar from a repository that requires Basic auth, with
  no credential in the task's environment, files or process list; a Maven build doing the same is the Maven
  plugin's acceptance, through its `configure`.
- A PUT to the declared repository from the task is refused and logged; a GET outside the declared prefix is
  refused.
- A credential header the task sends is not forwarded.
- A text answer that carries the credential, in clear or Basic-encoded, is cut off before the credential reaches the
  task, and the cut is logged.
- A project that declares no repository starts its tasks with no second socket and no second relay.

## To be checked

- Whether Nexus and Artifactory redirect a download to another host (an object store's signed URL) in their usual
  setups, which the redirect rule above would refuse.
- Maven's `settings-security.xml` encrypted passwords: whether `vault put` should take one, decrypted by the person,
  or the drafts' offer to store a credential decrypts it.
- That `SocketRelay` serves a large download at full speed; it has no test today.
