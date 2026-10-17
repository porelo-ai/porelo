# B201 — A Start Now, Built Or Removed

**Status:** Idea.

**What must be true.** `porelo task start --now` does what it says, or is no longer offered: an option a person can
type and that changes nothing is either built or removed, with whichever is chosen written down.

**Why.** Found in [B76](README.md#b76)'s rereading of `app-task` (2026-10-17): `--now` is accepted by `task start` and does nothing
today. Its help says what it was meant for: "Returns as soon as the task exists, without waiting for the image
build. Needs --detach" - what the daemon's `Start` does with its `now`. The analysis finds why the command line lost it
and whether a script relies on the option being accepted. Removing it changes the command line; building it may change what the command waits for.
