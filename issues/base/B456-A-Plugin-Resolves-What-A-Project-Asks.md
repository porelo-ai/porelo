# B456 — A Plugin Resolves What A Project Asks

**Status:** Backlog.
**Size:** L - a contract's method, the draft's two new kinds of key, the question, the wire and the page.

**What must be true.** When a project file is made, a plugin may look up what a project asks, and a person sees the lines it fetched
whole before a yes. Porelo's side of `EC09` in `porelo-ecosystems`, whose adapter does the looking up.

**The operator's word, 2026-11-05:** after the test. It replaced their earlier "before the test" the same day; nothing of it was built.
[B453](README.md#b453) stays after the test too.

## The Contract

- **`Resolve(directory, home, findings)` in `Ecosystem1`,** beside `Detect`, which stays offline. It gets that plugin's own `Detect`
  answer and no other's. A plugin without it answers `MethodNotFound`, which is "this plugin cannot say".
- **On success its findings replace that plugin's `Detect` findings whole.** On a failure, or `MethodNotFound`, `Detect`'s stand, with a
  note naming what was asked. So a lookup failing still leaves a question with a yes, and no build tool's name is in Porelo.
- **Two more kinds of key, by prefix:** `snippet.<name>`, image lines, joined in the order of the name; `env.<NAME>`, a variable's value.
  Two plugins giving one key two values are a disagreement. A name `TaskVariables.reserved` holds is refused.
- **Bound:** 5 s a request and 20 s a call are Agent Ecosystems' first figures, unmeasured.

## When It Is Called

When a file is made, at `project init` and a first start, before the question. Never for a start with a file, at `--no-input` or at
`--dry-run`, which promises nothing opened.

A yes calls `look` again (`FirstStart.java:288`), so the answer is kept for the question's life, keyed by what `Detect` answered, and a
miss at the yes asks anew.

## The Environment

The plugin starts with an empty environment (`InstalledEcosystem.java:259`), and at a first start through the socket the caller is
`porelod`, which holds no shell's exports. The client sends `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` and the lower-case forms for this
call alone. They can hold a credential: it never goes into a finding, a note or a line.

## The Question

Fetched lines run as root of the build, so they are shown whole for a new file too, and count in the hash.

A failed lookup's note reaches a window by `notes`, which [B455](README.md#b455) put on the wire. A resolved value's comment needs no
`:0`, by [B452](README.md#b452).

**With [B453](README.md#b453) still open,** a repository with its own image is resolved over, as a draft is today: the same stop in kind.

## To Say In The Page

`doc/ecosystem-plugins.md:7` says a plugin opens no connection. That changes for this call, plainly.
