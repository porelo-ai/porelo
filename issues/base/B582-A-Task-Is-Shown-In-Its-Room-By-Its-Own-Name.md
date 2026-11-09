# [B582](README.md#b582) — A Task Is Shown In Its Room By Its Own Name

**Status:** Review. Filed 2026-11-09 under ST290, from the fourth move.
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

## As built

- `TransportLifecycle.display` takes the task's own name (`agentName(project, container)`, "Agent Pi") first. Where
  another enrolled task holds it, the agent's name follows in brackets ("Agent Pi (Claude)"), then a number as before.
  A container not of the project's task form falls back to the agent's name, with its container name in brackets on a
  clash.
- A held name stays unless another task holds it too, or it is the agent's form the room gave before this change
  (`earlierForm`: "Agent Scout" for a task not called scout, or "Agent Scout (fix)" with the short name in lower case).
  The names the room gives now ("Agent Scout (Scout)") and one a person chose ("Agent Scout (Reviewer)") stay, so a
  task called as its agent keeps its name from one enroll to the next.
- `TaskConversations.rejoin` (a resume) asks the room again where it still holds the task under that earlier form, so
  the task takes its own name on its way back too, not only at its next start.
- This turns [B532](README.md#b532)'s choice of the agent's name round, after the fourth move showed tasks of one agent
  indistinguishable and lines headed by a task's name reaching nobody; the coordinator decided it on 2026-11-09. Its
  changelog entry `task-shown-by-its-name.yml` says the new rule.
- Tests in `TransportConversationsTest`, each red against the method before: 
  `aTaskIsShownInTheRoomByItsOwnNameWhateverAgentRunsIt`, `twoTasksOfOneAgentAreEachShownByTheirOwnNameOnEveryEnroll`, 
  `aTaskWhoseOwnNameAnotherHoldsIsShownWithItsAgentInBracketsUntilThatTaskIsRetired`, `aTaskEnrolledBeforeNamesWereKeptHoldsTheNameItWasShownByUntilItIsRetired`,
  `aHeldNameAnotherTaskHoldsTooIsNotKept`, `aTaskNamedAfterItsAgentKeepsTheNameItIsGivenNow`,
  `aNameHeldInTheAgentsFormIsGivenUpForTheTasksOwn`, `aNameHeldInAnotherFormIsKept`, and
  `tasksOfOneAgentEnrolledAtOnceAreEachGivenANameNoOtherHolds` (now with containers outside the project's form, which
  all want the agent's name). In `TaskLifecycleCommandsTest`: `aResumedTaskTheRoomStillShowsByItsAgentTakesItsOwnName`.
  The enrol tests of a start, a restart and a resume after a reboot (`TaskRunCommandTest`) and a resume outside its
  room now expect the task's own name.
- Left for a new issue: a name that cannot be made unique within the room's 64 characters (a task name of
  54 or more characters on a clash) and a task name with two hyphens in a row still get no name.
