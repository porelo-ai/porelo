# B365 — The Providers' Usage Answered In One Form

**Status:** Idea.
**Size:** M, proposed - one verb with a reader per provider, a threshold setting and its tests; the readers are
fragile, since none of the three providers offers a documented usage interface.

**What must be true.** A person sees how much of each AI provider's allowance is left before a task stops on it.
`porelo usage [--json]` answers each provider the person's agents use in one form, one entry per machine, account and
window: the provider, the account, the window (such as the five-hour session or the week), the part left in percent,
the time it resets and when it was read. Today `~/.porelo/coordinator/quota.sh` reads the same by hand, from three
places that each answer differently: Antigravity's `/usage`, the `rate_limits` of Codex's newest session file and
Claude Code's `/usage`. A provider that cannot be read is answered as such, with why, never as zero or as full.

A threshold per provider and window, set by the person, marks a window as critical; the answer carries the mark, so a
status line in the person's interface can warn through "needs you" without a rule of its own. Possibly the answer also
names the running tasks whose agents use each provider, so the person sees which work an exhausted window stops.

Open: whether the verb reads on demand or a task's agent reports its usage as it goes, how often a read may run
(each `/usage` call starts the provider's tool and may itself count), and whether it belongs to 0.7, where the
coordinator places it beside the interface's status line.
