# B219 — A Private Package Repository Through The Broker

**Status:** Estimated.
**Size:** L - a second kind of broker route that carries no token and answers binary bodies, a new `project.yml` key
and its contract, a second relay per task, `doc/reach.md` rows, an accepted risk, and an acceptance step against an
authenticated HTTPS repository the suite must stand up.

**What must be true.** A task builds against a package repository that needs authentication - a company's Nexus or
Artifactory behind Maven, npm, pip or Gradle - while the credential stays in the vault on the host and nothing
credential-shaped, not even a phantom token, enters the task.

## Why

Today a private mirror is only reachable as a declared domain (`doc/faq.md`), so its credential has to be in the
task's `settings.xml` or `.npmrc`. [B15](README.md#b15) rejects a repository manager as something a task publishes
to; this one only reads through the broker, and publishing still happens after the gate.
[PJ52](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj52) proposes a draft `project.yml`
from a checkout's build tools, and its `configure` verb writes the tool settings this issue needs.

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
