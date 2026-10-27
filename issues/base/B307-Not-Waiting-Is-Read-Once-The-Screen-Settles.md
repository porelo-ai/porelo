# B307 — Not Waiting Is Read Once The Screen Settles

**Status:** Idea.

**What must be true.** The acceptance kit's step "porelo shows the agent ... not waiting for a person" passes when the
agent stops waiting a moment after the step begins, as after a key that closes a dialog, and fails only when Porelo
reads it waiting again after it once read it not waiting, or never reads it not waiting within the bound.

## Why

`WaitingSteps.showsNotWaiting` fails on the first poll that reads `reads as waiting`. Porelo's reading trails the
screen by up to one poll, so a step taken right after `Esc` closed a picker read the picker still there and failed,
while the screen had already closed it: measured by porelo-codex's fixture, 2 runs of 3 on `.343`. `showsWaiting`
waits for its state instead, so the two steps judge alike only once this one settles first. Every agent repository
whose scenario closes a dialog and then checks may see the same false red, in runs that can spend a provider's
money.
