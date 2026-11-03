# B409 — A Project With No Address Goes On No Host

**Status:** Implementing.
**Severity:** low.
**Size:** S - one check in `addProjectToHost`, its contract check, and the window's dialog saying the same.

**What must be true.** `porelo org host carry HOST PROJECT` refuses a project with no valid address, at once and with its
reason, and *Put a project on the host* in the window says the same. Today it refuses only `on-another-host`, `not-found` and
`exists`, and a project with no address goes on the host and waits there for a token that cannot help.

**Plan, by reading on 2026-11-03.** The write judges from the start, with no memory, so an address a lost key filed counts as
none here, as it does for a host set up anew. `not-opened`, exit 66, says the right thing for a project never opened; a refused
address needs words of its own, naming `project address`. The window lists what it offers and leaves such a project out or says
why, once this stands, and only then.

**What it leaves.** A host admin and a project's admin can be different people: the one now waits for the other, which is the
operator's choice. An address refused after the project went on the host is not caught here; that stays with the host's own
answer, [B405](README.md#b405).
