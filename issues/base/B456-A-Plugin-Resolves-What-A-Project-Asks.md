# B456 — A Plugin Resolves What A Project Asks

**Status:** Review.
**Size:** L - a contract's method, the draft's two new kinds of key, the question, the wire and the page.

**What must be true.** When a project file is made, a plugin may look up what a project asks, and a person sees the lines it fetched
whole before a yes. Porelo's side of `EC09` in `porelo-ecosystems`, whose adapter does the looking up.

**The operator's word, 2026-11-05:** after the test. It replaced their earlier "before the test" the same day; nothing of it was built.
[B453](README.md#b453) stays after the test too.

## The Contract

- **`Resolve(directory, home, findings) -> (findings)` in `Ecosystem1`,** beside `Detect`, which stays offline. It gets that plugin's
  own `Detect` answer and no other's. A plugin without it answers `MethodNotFound`, which is "this plugin cannot say";
  `Ecosystem.resolve`'s default answers so. `EcosystemProtocol.VERSION` is 2, `LOWEST` stays 1.
- **On success its findings replace that plugin's `Detect` findings whole.** On a failure, a refusal, a timeout or `MethodNotFound`,
  `Detect`'s stand, with a note naming what was asked: the plain tool keys and their versions, never the plugin's own words, a host or a
  credential's id. So a lookup failing still leaves a question with a yes, and no build tool's name is in Porelo.
- **Two more kinds of key, by prefix:** `run.<name>`, image lines, joined in the order of the name after the package lines, into the
  draft's `image.run`; `env.<NAME>`, a variable's value, into `image.env`. `EC09` names the first `snippet.<name>`: the project file's
  image lines are called `run` since the file was read through one model, and a draft's image lines and variables go to the project's
  top-level `image`, so the key is named for the field it fills. Two plugins giving one key two values are a disagreement, whatever part
  each put it in. A name `TaskVariables.reserved` holds is refused, `PATH` among them, by the model's own check of `image.env`. Lines
  are held to the rule the model applies to `image.run` (`RUN`, `ARG`, `ENV` only); a key whose lines break it is left out whole, and
  the question and `project init`'s `refused` say so.
- **Bound:** 5 s a request (`RESOLVE_REQUEST_SECONDS`, the plugin's to keep) and 20 s a call (`RESOLVE_CALL_SECONDS`, Porelo's),
  unmeasured first figures.

## When It Is Called

When a file is made, at `project init` and a first start, before the question. Never for a start with a file, at `--no-input`,
`--yes`, with nobody at a terminal, or at `--dry-run`, which promises nothing opened.

A yes through the daemon calls `look` again (`FirstStart.confirm`), so the daemon holds one `FirstStart`, which keeps each lookup for
the question's life, keyed by the plugin and what its `Detect` answered: at most 32, for an hour, and dropped once a yes is taken. A miss
at the yes asks anew, and an answer that changed is `MOVED_ON`.

## The Environment

The plugin starts with an empty environment (`InstalledEcosystem`). For `Resolve` alone a process of its own is started
(`InstalledEcosystem.forResolve`) with `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` and the lower-case forms, and no other name. At a
terminal and at `project init` they are the shell's. At a first start through the socket the caller is `porelod`, which holds no
shell's exports, so `FirstStart` and `ConfirmFirstStart` in `15-task.varlink` take an optional `proxy: ?[string]string`, of which
only those six names are read. They can hold a credential: an answer repeating `HTTP(S)_PROXY`'s value is not used, and none goes
into a finding, a note, a log line or an answer.

## The Question

Fetched lines run as root of the build, so they are shown whole for a new file too, each as `image line`, and each variable as
`image env`; they are in the file and count in the hash.

A failed lookup's note reaches a window by `notes`, which [B455](README.md#b455) put on the wire. A resolved value's comment needs no
`:0`, by [B452](README.md#b452).

**With [B453](README.md#b453) still open,** a repository with its own image is resolved over, as a draft is today: the same stop in kind.

## To Say In The Page

`doc/ecosystem-adapters.md` says a plugin opens a connection in `Resolve` alone, when a file is made, and documents the two key kinds,
the bounds and the proxy variables.

## Acceptance

- Unit tests with fake plugins over the real socket and in process: a success replaces the findings; `MethodNotFound`, a refusal and a
  failure leave `Detect`'s and a note; a timeout is bounded; a disagreement; `env.PATH` and reserved names refused; a forbidden line
  dropped; no lookup at `--dry-run`, `--no-input`, unattended or with a file; the yes reusing the kept answer, over the socket too; the
  proxy values reaching the plugin's process alone and never the output; the question showing the fetched lines whole.
- The scenario `a plugin's fetched lines are shown whole before the question` in `project-first-start.feature`, on the VM.
