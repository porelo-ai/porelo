# [B559](README.md#b559) — The Libraries A Plugin Links Are Apache-Licensed

**Status:** Backlog. Filed 2026-11-09 as porelo's part of `porelo-project` PJ33, on the operator's word of 2026-11-09
(Apache-2.0, without legal advice; the operator is the sole author). Built after the move-4 test.
**Size:** M - the license files, POMs and headers of four modules and the varlink definitions, one build check, and
one page of documentation.
**Blocked by:** -.

**What must be true.** `porelo-agent-api`, `porelo-build-api`, `porelo-ecosystem-api`, `porelo-wire` and the `.varlink`
interface definitions are Apache-2.0: each carries `LICENSE` and `NOTICE`, names Apache-2.0 in its POM's `<licenses>`
and its packages, and its sources say so in their headers. Everything else in porelo stays GPL-3.0-or-later. A build of
one of these modules fails when it gains a dependency that is GPL- or LGPL-only. porelo's documentation says in one place
where the boundary is, linked from `agents/README.md`, the build reader guide and `doc/transports.md`: a plugin may
carry any license, and one that only talks through the protocols is a work of its own.

## Why

An agent or a build reader built the documented way links `porelo-agent-api` or `porelo-build-api` and `porelo-wire`,
so today it is bound to the GPL. Porelo promises that a proprietary agent is a directory added, not a patch. Apache-2.0
asks for the license and notices to be kept, not for the library to be replaceable, so a plugin built as one native
image needs nothing further, and porelo itself keeps using the libraries unchanged.

## Plan

- Each of the four modules: `LICENSE` (Apache-2.0) and `NOTICE`, `<licenses>` overriding `porelo-parent`'s, and an
  SPDX header in each source. The `.varlink` files under `wire/src/main/resources/varlink/` and `builds/api` carry a
  header too.
- The packages that ship them name both licenses where they ship both.
- The check: a dependency of these modules whose license is GPL- or LGPL-only fails the build. Seen to fail first: a
  GPL-only test dependency added to `porelo-wire`, which is accepted today.
- `doc/`: one section on the boundary, and the three links to it.

## Acceptance

- Each of the four modules and the varlink definitions name Apache-2.0 in `LICENSE`, `NOTICE`, the POM and the headers.
- The check fails on a GPL-only dependency of an interface module, and passes on the tree as it is.
- The documentation states the boundary in one place, linked from the agent, reader and transport guides.
