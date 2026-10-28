# B319 — CanStart Without A Gate Checks The Repository

**Status:** Idea

**What must be true.** The daemon's `CanStart` asked with `noGate` and a `repository` answers what `Start` would:
`Start` refuses an unknown repository even without a gate (`TaskLaunch.chosenRepository`), while `CanStart` skips the
repository's check when there is no gate (`StartCheck.withRepository`) and answers `READY`.
