# B621 — The Native-Image Checks Live With Porelo

```json
{
  "$schema": "issue-1.json",
  "id": "B621",
  "title": "The Native-Image Checks Live With Porelo",
  "state": "Idea",
  "size": "M",
  "priority": "Medium",
  "rank": 30,
  "blockedBy": [],
  "covers": "porelo-ffm-check and porelo-cpu-check are built, tested and published from this repository; porelo-buildtools no longer holds them, and porelo-planner takes porelo-cpu-check from here."
}
```

**Why this size.** two small modules moved with their tests and READMEs, ffm-check's JSON reading moved onto
porelo-wire's `Json`, the root pom's two executions pointed at the new modules, the publish set widened, and
porelo-planner's dependency changed by its owner.

Filed 2026-11-11 as this repository's part of `porelo-project` ST320, "The work tools live with their users".

**What must be true.** The two checks that guard porelo's native images, `ForeignRegistrations` (every FFM downcall
registered for native-image) and `CpuFloor` (no instruction above the CPU floor), are porelo's own: changed, tested and
released with the code they guard. Today they live in porelo-buildtools for history's sake, and a change to either
waits for a build-tools release.

How it is today:

- The root pom runs `ffm-check` at `prepare-package` and `cpu-check` at `package`; core, host, shield and vault run
  `ffm-check` too. Both come from porelo-buildtools as `porelo-ffm-check` and `porelo-cpu-check`.
- `porelo-ffm-check` depends on `porelo-json`, which porelo-buildtools' release and machines modules use as well, and
  porelo's `dist/package-check`.
- porelo-planner runs `porelo-cpu-check` on its own native image.

## Plan

- Two modules under `build/`, `build/ffm-check` and `build/cpu-check`, with the same artifact ids, packages and tests,
  built before every module that runs them (they are plugins' dependencies, so the reactor orders them by hand: the
  root pom names them as modules first).
- ffm-check reads its JSON through porelo-wire's `Json` in place of porelo-json; porelo-json stays a build-tools
  helper, since release and machines use it. `dist/package-check` keeps its porelo-json dependency until it moves
  onto `Json` too, a change of its own.
- Both are published with porelo's snapshots and releases, so porelo-planner takes `porelo-cpu-check` from here
  (Planner's change, named in their ST320 issue).
- porelo-buildtools drops both modules once porelo's first publish holds them (Machines' change).
- Red first: none to see, the tests move as they are; the root build proves the executions still refuse a missing
  registration and an instruction above the floor (a fixture each, as in their own tests today).

## Acceptance

- `./mvnw -Pnative install` runs both checks from this repository's modules, and their tests pass here.
- porelo-planner's build takes `porelo-cpu-check` from porelo's published artifacts.
