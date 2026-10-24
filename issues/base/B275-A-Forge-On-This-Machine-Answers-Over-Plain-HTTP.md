# B275 — A Forge On This Machine Answers Over Plain HTTP

**Status:** Backlog, for sprint 3.
**Size:** XS - one rule in `Builds`' validation, its tests, one line of `doc/project-file.md` and a changelog entry.

**What must be true.** A project file's `builds.api` may be an `http://` address when its host is this machine's
loopback - `127.0.0.1`, `::1` or `localhost` - so a forge run in podman on the same machine is asked without a
certificate; any other `http://` address is still refused naming why.

## Why

From porelo-build-github [GH07](https://github.com/porelo-ai/porelo-build-github/blob/main/issues/README.md#gh07):
its acceptance asks a Forgejo run in podman, which answers `http://`, and `Builds` refuses every `builds.api` that is
not `https://` (`Builds.java:54-57`). The token then crosses only the loopback, which nothing off this machine sees.

## Plan

1. `Builds`' check reads `api` as a `URI`: `https` is accepted as today; `http` only when `getHost()` is exactly
   `127.0.0.1`, `[::1]` or `localhost` (compared case-insensitively, with no user information); anything else is
   refused with "'builds.api' must be an https:// address, or http:// on this machine's loopback (127.0.0.1, ::1,
   localhost), not '<api>'".
2. `doc/project-file.md`'s `builds:` example names the loopback exception in its comment.
3. A changelog entry, `type: changed`.

**Red first:** `ProjectBuildsTest.acceptsPlainHttpOnTheLoopbackOnly`: `http://127.0.0.1:3000/api/v1`,
`http://[::1]:3000/api/v1` and `http://localhost:3000/api/v1` accepted - refused today - and, each refused,
`http://forge.example/api/v1`, `http://localhost.example/api/v1`, `http://127.0.0.1@forge.example/api/v1` and
`http://127.0.0.2/api/v1`.

## Acceptance

- The three loopback forms are accepted and every other `http://` address refused, the test seen red first.
- `porelo build status` asks a Forgejo in podman at `http://127.0.0.1:<port>/api/v1` ([GH07](https://github.com/porelo-ai/porelo-build-github/blob/main/issues/README.md#gh07)'s acceptance).
