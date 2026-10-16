# B55 — The Changelog Entry A Change Has To Bring

**Status:** Estimated.
**Size:** L - crosses repositories: the check is proposed to logchange first, otherwise built as a fuinorg plugin of
its own, then bound in `porelo-parent` and given a step of its own in `porelo-frontend`.

**What must be true.** A change to what ships or builds brings a changelog entry, or says on purpose
that it does not, and the build checks it in every repository that keeps a changelog.

## Why

Nothing is blocked by it: the gap it describes is deliberate for now.

## Decided

- **logchange is adopted, in Porelo first** - `dev.logchange:logchange-maven-plugin`, its version managed
  in `porelo-parent`, which every Maven repository of Porelo inherits: Porelo, `porelo-buildtools`, the
  agent and message repositories and `porelo-frontend`. A change is recorded as **one YAML file per entry** under
  `changelog/unreleased/`, and the generated `CHANGELOG.md` follows Keep a Changelog. An existing
  `CHANGELOG.md` is kept without rewriting by moving it to `changelog/archive.md`.
- **The check goes into `porelo-parent`**, so every repository inheriting it gets the check from one
  place; the fuinorg parent POM is not involved. `porelo-frontend` builds its product with Flutter, not
  Maven, so it gets a step of its own.
- **The current changelog check is removed everywhere for now.** That is `check-changelog.py`, 308
  lines, byte-identical in the three agent repositories and run by each one's `build.yml`. No other
  repository had a check. The shared build tools no longer carry it either.

**So, until this requirement is met, nothing forces a change to bring an entry.** That is the price of
not rebuilding the old check on the old format, and it is paid on purpose.

## What logchange makes smaller

With one file per entry, the check becomes one question: **does the commit range add a file under
`changelog/unreleased/`?** The hardest part of the old script disappears by construction. It had to
tell *touching* `CHANGELOG.md` from *adding an entry to it*, because an earlier version was satisfied
by a commit that only rewrote a URL in the `[Unreleased]:` link line. A new file is an entry.

## The way to build it

1. **Propose it upstream to logchange first**, as a goal of its own or as an option of `lint`.
   Requiring an entry is exactly that tool's subject, and built there it serves everyone who uses
   it. Today `lint` checks only that the `changelog` directory exists and that its YAML is valid,
   and nothing in logchange (1.19.16) requires an entry.
2. **If upstream declines, a small fuinorg plugin**, in its own repository under
   `github.com/fuinorg`, since the rule puts what is not Porelo-specific there. Either way
   the plugin that repository would have held becomes smaller, or unnecessary.

## What the check must keep from the old one

Each of these was in `check-changelog.py` because it had gone wrong once:

- **A waiver answers for its own commit only.** `[no changelog]` excuses the commit it is written on
  and nothing travelling with it. It used to be looked for across the whole push, and a
  docstring-only tip carrying the marker excused the two code commits under it.
- **Documentation is not exempt.** A typo and a rewrite of a setup guide are both documentation, and
  no diff tells them apart, so a person decides with the waiver. Editor settings and the licence are
  exempt.
- **A shallow clone must not make it pass.** A range that cannot be compared - an abbreviated SHA in a
  depth-1 clone - fails rather than being judged fine.

## Plan

Read in today's code. The root `pom.xml` runs `logchange-maven-plugin`'s `lint` at `validate` (`inherited=false`),
configured by `changelog/logchange-config.yml`, with entries under `changelog/unreleased/`. Nothing requires an
entry, and no workflow checks one.

Steps, in order:

1. Red first: the check's cases as tests, taken from what the old script kept - a range that adds a file under
   `changelog/unreleased/` passes; one that changes a shipped path without one fails; a waiver excuses its own commit
   only; editor settings and the licence are exempt; a range that cannot be resolved in a shallow clone fails.
2. Propose a goal, or a `lint` option, to logchange with those cases, and offer the implementation.
3. If upstream declines, the same goal and tests in a fuinorg plugin in its own repository under
   `github.com/fuinorg`.
4. `porelo-parent` binds the goal at `validate`, once per repository at its root rather than in every module, with
   the range from CI (the push's `before` and `after`) and a full-history checkout in the job that runs it. Porelo
   moves its own `lint` binding there too, and each inheriting repository gets the check with the `porelo-parent`
   release that carries it; `porelo-buildtools`, still on `0.1.1`, gets it when it moves up.
5. The agent and message repositories adopt logchange as Porelo did: `changelog/unreleased/`, their `CHANGELOG.md`
   moved to `changelog/archive.md`, and the check through `porelo-parent`. Each is asked of its repository's agent.
6. `porelo-frontend`, a step of its own: it keeps a hand-written `CHANGELOG.md` and no `changelog/` today. It adopts
   the same layout, and its build workflow runs the check as a step of its own beside the Flutter build. Asked of
   its repository's agent, with the dependency named.
7. Break each rule in turn and watch its case fail.

## Acceptance

- A range that changes what ships without adding an entry fails; the same range with an entry passes;
  a commit carrying the waiver passes for itself and excuses nothing else. Seen to fail: a push whose
  tip carries `[no changelog]` and whose earlier commits change code without an entry passes.
- Each of those is proven against the failure it exists for, by breaking the check and watching the
  matching case fail.
- It works in a shallow CI clone, and a range it cannot compare fails. Seen to fail: a depth-1 clone
  with an abbreviated SHA in the range passes.
- A repository that inherits `porelo-parent` gets the check from it, not by writing a check of its own.
  Seen to fail: a repository needs a script or a binding of its own beside its parent for the check to run.
- `porelo-frontend`'s build runs the check as a step of its own. Seen to fail: a push to it that changes what
  ships without an entry passes.

## To be checked

- **What logchange's maintainers want** (logchange's maintainers'): a goal or a `lint` option, and what a waiver
  looks like in their model, where entries are files rather than lines.
- **Where "what ships or builds" is defined** (Agent Core's): by convention, or configured per project with editor
  settings and the licence exempt by default.
