# B626 — A Start Step Fails When The Start Ends

```json
{
  "$schema": "issue-1.json",
  "id": "B626",
  "title": "A Start Step Fails When The Start Ends",
  "state": "Idea",
  "priority": "Medium",
  "rank": 42,
  "blockedBy": [],
  "covers": "The acceptance kit's step that waits for an agent's session fails as soon as the terminal's `porelo task start` has ended without one, naming the start's exit and output, instead of waiting out its 900 seconds."
}
```

**What must be true.** When a task started in a scenario's terminal is refused, the scenario fails within seconds
and its report says why. Today `ReadySteps.theAgentInContainerReachesWork` only polls for the task's tmux pane, up to
`LiveTaskSteps.START_SECONDS` (900 s): a start refused after a failed image build leaves no pane, so the step waits
out the whole bound and reports "No session to read appeared in <container> within 900s", without the start's own
error. Seen on the Ubuntu leg during a mirror outage: two scenarios each spent about 910 s there, and their log shows
no cause, while other scenarios in the same minutes named the failed mirror.
