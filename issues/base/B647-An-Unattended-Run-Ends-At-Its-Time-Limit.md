# B647 — An Unattended Run Ends At Its Time Limit

```json
{
  "$schema": "issue-1.json",
  "id": "B647",
  "title": "An Unattended Run Ends At Its Time Limit",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 86,
  "blockedBy": [],
  "covers": "An unattended run has a time limit, set at its start with a default, after which porelo ends it, records it as ended by its limit and keeps its log; a model that never ends its turn no longer holds a task forever."
}
```

Filed 2026-11-12 under ST314 from porelo-frontend F492 run 26, step 9. The reader `porelo-foo-project_foo-1-2`
(pi, glm-5.3-flash) never ended its turn. Every message stopped at a tool call, and it started foreground poll loops
of about 10 minutes again and again until a person stopped the task after 30 minutes. Agent Smith read the same log:
pi and its adapter behaved as built, since pi has no cap of its own and a shell call's timeout is the model's argument.
The run's overall time is porelo's to bound. (Why the reader polled at all is [B646](README.md#b646).)

How it is today, read in the code: porelo sets no time limit on an unattended run. The only deadline in a task's run
is the build watch's two hours, which is about a build's verdict, not the agent.

**What must be true.**
- An unattended run (`--prompt`, Tasks1 Start with a prompt) has a time limit: a default, and a value given at its
  start on the CLI and in Tasks1 Start, so the planner can set one per phase.
- When the limit passes, porelo ends the agent as a stop does, records the run as ended by its limit (List's and
  Watch's `agentEnded` say so), and keeps its log and its result file as at any end.
- An attended session has no such limit.
