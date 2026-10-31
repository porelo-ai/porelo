# [B171](README.md#b171) — Forge Review Comments To And From A Task

**Status:** Analyzed.
**Blocked by:** [B166](README.md#b166), [B291](README.md#b291), [B292](README.md#b292), [B293](README.md#b293).
**Blocks:** [B288](README.md#b288), [B289](README.md#b289).
**Size:** L - a review adapter contract, host synchronization and durable release flow across repositories, with project, daemon and message changes.

**What must be true.** Pull request comments, findings, suggestions, threads and observed verdicts reach the owning
task as SARIF, and its released replies reach the same pull request through a host adapter. Each item's authenticated
source governs delivery and release; the task holds no forge credential.

## Plan

`builds/api` serves `Build2` through `BuildReader`, `BuildMain` and `BuildServer`. `BuildWatch` is a task helper for
online pushes, not a daemon PR watcher. `Builds` has `forge`, `credential`, `api`, `logs`; `ProjectReader` refuses
builds outside online projects. `ReviewReader`, `Review1`, `ReviewWatch`, reviewer lists and release settings are
additions. `InboundCheck` filters external peer messages; forge input needs its own authenticated inbound route.

1. Add `ReviewReader` and `org.fuin.porelo.Review1.varlink` in the published artifact `porelo-review-api`, beside the
   review profile, not in `builds/api` (operator, 2026-10-31); dispatch through
   `BuildMain`/`BuildServer` only when implemented, with capabilities in `Describe`. Extend `builds/stub/StubReader`.
   Read PR state and paged SARIF logs; write explicit create, reply, edit, delete, resolve, unresolve and verdict
   operations, each with exact target, native IDs, commit and released content. A SARIF snapshot is never a command.
2. Red first: stub-adapter tests in `apps/app` show an E0 comment held, E1/I1 delivered as untrusted data, an A reply
   awaiting release, and a copied native marker unable to confer ownership. Incomplete pagination stays partial.
3. Add durable task-to-PR mapping by configured forge instance, stable repository ID, branch and exact pushed commit.
   Record successful `GitGate.passOn` and approval destinations. Add a daemon-managed `ReviewWatch` so stopped tasks
   and gate-approved work can still receive reviews. Reuse build-reader invocation and per-call vault lending;
   do not assume the online helper already covers that lifecycle.
4. Extend project models, `ProjectReader` and `ProjectSchema` with review configuration separate from the online-only
   build restriction, including a separate vault write credential with only the required review-write scope. Keep
   read and write tokens on the host and lend them per call. Use [B293](README.md#b293)'s stable-account source policy. E0 input waits for individual release;
   E1/I1/A prose is delivered at once, all untrusted. E0 fixes are non-applicable; E1/A fixes require release. An I1
   forge resolution clears its exact mapped finding at the gate; other resolvers are shown without clearing it.
   Unknown resolver identity cannot clear anything, and a verdict never authorizes gate approval.
5. Every review crosses [B291](README.md#b291)/[B292](README.md#b292) and
   the message filter's checks. The adapter performs all
   native conversion and supplies independently authenticated origin evidence for the daemon to classify. Support
   line, file and general comments; stamp conversion identity and native mappings on the host. Keep bodies out of
   prompts, snippets and artifact contents out of projections, and raw payloads out of failures and logs.
6. A durable outbox binds release to instance, repository, PR, operation, parent/native IDs, commit, final native body
   digest, releasing principal and expiry. Editing content or changing destination invalidates release. Default to
   release by a person; only I1 replies may bypass it under explicit project policy permitted by machine policy.
   A task-origin A reply always waits. Queue caps: 1,000 per task and 5,000 per project; overflow is explicit.
7. Enforce 10 published comments per task and 60 per project per rolling hour across processes, retries and batches.
   Hold excess until the budget permits, never drop it. Honor backoff and reconcile ambiguous writes using durable
   mappings and authenticated bot ownership before retrying. Neither a hidden marker nor a sender GUID proves it.
   Escape notification syntax in prose, check final serialized bodies for secrets and destination visibility, and
   hold unsupported or lossy native actions before release. Never insert zero-width characters into suggestions.
8. GitHub and Forgejo are the release's adapters. Native clients use `java.net.http.HttpClient`, Jackson and records.
   Declare per-operation capabilities; test GitHub thread-resolution conversion and Forgejo anchor/range limitations
   against the deployed APIs, without inventing locations or silently changing a released operation. On the host's
   Forgejo the agent's PR uses its own account and the approving person uses their own.
9. `MessagingMethods` and `45-messaging.varlink` expose held/released operations through the host queue, with bounded
   SARIF evidence. Reuse `talk held`/`talk release` where their authorization fits; add review-specific handling.
   Journal delivery, hold, release, write and resolver effects in the review store. Update `doc/project-file.md`,
   `doc/build-readers.md` and `doc/messages.md`; exercise CLI and daemon against the stub and adapter contract fixtures.

## Acceptance

- Seen red first: E0 reaches the task without release; a changed body reuses release; an A reply bypasses it; a copied
  marker edits someone else's comment; an ambiguous retry duplicates a post. Each must be prevented.
- E1/I1 input is delivered untrusted; I1 resolution clears only its mapped finding while E0/E1/A resolution only shows
  the remote state. A forge approval, empty result page or deleted comment never approves waiting code.
- Eleventh/sixty-first comments wait and later publish once; full queues and partial fetches are visible. A release
  authorizes one operation with the exact final body. Tokens and generated source context never enter the task or PR.
- GitHub and Forgejo conversion tests show supported round trips and explicit holds/loss notices for unsupported
  actions. Stopping a developer task does not silently stop its open PR's review synchronization.

## To be checked

- Verify authenticated resolver, merge-head and membership evidence per adapter, plus minimum token scopes and
  redirect behavior. Adapters' implementation remains outside this repository, including
  the forge adapters in porelo-adapters.
- Measure watch, queue and mapping recovery across daemon restarts, task removal and PR head movement; settle burst
  and state-mutation budgets before enabling writes.
- Keep forge HTTP calls in `porelo-forge-clients` rather than in `porelo-adapters`, and align the contract review
  cases and the Forgejo review adapter in `porelo-adapters` with that boundary.
