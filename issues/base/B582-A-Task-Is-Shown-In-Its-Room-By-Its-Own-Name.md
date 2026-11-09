# [B582](README.md#b582) — A Task Is Shown In Its Room By Its Own Name

**Status:** Backlog. Filed 2026-11-09 under ST290, from the fourth move.
**Size:** S - the order of two names in one method, the held names it keeps, and its tests.

**What must be true.** A task is shown in its project's room as "Agent" and its own name, as
[B526](README.md#b526) set it: the task `pi` running Claude Code is "Agent Pi", never "Agent Claude". Where another
task of the project already holds that name, the agent's name follows in brackets ("Agent Pi (Claude)"). A task enrolled
earlier under its agent's name gets its own name at its next start.

## Plan

Read in the code: `TransportLifecycle.display(project, container, agent, held)` takes the agent's name first and the
task's (`agentName(project, container)`) only when no agent is given; a task that holds a name keeps it
(`held.get(container)`), so a task already shown as "Agent Claude" stays so.

- `display`: the task's own name first; the agent's name only to tell two tasks apart.
- A held name that is the agent-name form of that task is not kept; any other held name is.
- The transport's half (its member list showing the new name on a confirming enroll) is porelo-message-matrix's.
- Red first in `TaskDisplayTest`/`TransportConversationsTest`: the task `pi` with agent `claude` is displayed
  "Agent Pi"; a task holding "Agent Claude" from before is given "Agent Pi".
