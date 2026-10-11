# B138 — A Token Ceiling Per Task

**Status:** Estimated; blocked by sokar [B148](README.md#b148).
**Size:** M - the project file, the start, the per-task proxy and a refusal the agent reads; tokens only.

**What must be true.** A task cannot spend more than its project allows: the broker stops forwarding to the provider
once a task's token ceiling is reached, and says so to the task and to the person.

A ceiling in money is out of scope here and will be an issue of its own: it can hold only where the provider reports
the cost of each answer, as a router service does, and a token ceiling holds for every provider.

## Why

`corporate-security.md` says Sokar does not limit spending. A convinced or looping agent spends the account's money without a bound.

**Guideline points it answers:** OWASP `LLM10` (unbounded consumption); OWASP AI Agent Cheat Sheet §9; Sysdig 7/8; AISVS 9.1.2. From a review of security guidelines, 2026-10-09.

## Plan

Built on [B148](README.md#b148)'s counting; tokens only. Read in the code on 2026-10-11: each task has
its own credential proxy, `sokar vault serve --task <name>` (`apps/app-vault/.../VaultServeCommand.java`), which runs
one `VaultProxy` (`supervisor/.../VaultProxy.java`) on the task's socket; every request to a provider passes its
`handle`, and it already answers refusals in the provider's error shape (`VaultProxy.error`). [B148](README.md#b148) adds the count
there, from the provider's answer. `Limits` (`core/.../project/Limits.java`) holds what a task may consume today:
memory, cpus, pids, hand-in size. `doc/corporate-security.md` says "It does not limit spending".

1. **Where it is set.** `Limits` gains `tokens` (a count, `null` for none), read from the project file's `limits:`
   as `memory` is (`Limits.Declared`), and `task start --token-limit <n>` lowers it for one task, never raises it.
   The start passes it to `vault serve` as `--token-limit`, and it is kept in the task's record so a resume keeps it.
2. **Where it holds.** `VaultProxy.handle`, before a request is forwarded: when [B148](README.md#b148)'s count for the task has reached
   the ceiling, the proxy answers in the provider's error shape with a status the agents do not retry - 403 and
   `permission_error`, not 429, which they retry until they give up - saying `Sokar: this task's token ceiling of
   <n> is reached`, and nothing goes to the provider. Which status each agent gives up on is measured with the stub
   and the Pi family first. A request already forwarded is let finish, so one answer can pass the ceiling; the docs
   say so. The refusal is written to `vault.log` and once to the daemon, which shows it in `task status` and to the
   interface as the task's state.
3. **Raising it for a running task.** `sokar task limit <task> --tokens <m>` writes the new ceiling where `vault
   serve` reads it (a file in the task's state directory on the host, read per request). Only a person on the host
   raises it, above the project's too; the task has no way to that file.
4. **Docs:** `doc/project-file.md` (`limits.tokens`), `doc/commands.md` (`--token-limit`, `task limit`), and
   `doc/corporate-security.md` replaces "does not limit spending" with what the ceiling covers and what it does not:
   it counts tokens, not money.

Red first: a `VaultProxy` test with a count at the ceiling, which forwards today and must be refused without
reaching the stand-in provider; then `ProjectReaderTest` for `limits.tokens`.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Whether the daemon's contract gets a call to raise the ceiling, for the interface, or the CLI alone first
  (Agent Core's).
