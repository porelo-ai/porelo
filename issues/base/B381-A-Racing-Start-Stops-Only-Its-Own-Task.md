# B381 — A Racing Start Stops Only Its Own Task

**Status:** Idea.
**Severity:** medium.
**Size:** S, proposed - a failed start's cleanup bound to the id it recorded itself, with a test of two starts of
one name.

**What must be true.** When two starts of the same task name race, the one whose create fails on the taken name
stops nothing of the other. Today `TaskLaunch.cleanUpOrFail` trusts the id recorded for the name, which the first
start wrote, so the second start's failed create stops the first start's task as if it were its own.

Found while fixing [B378](README.md#b378) on 2026-10-31; not built tonight (operator's rule of 22:30Z).
