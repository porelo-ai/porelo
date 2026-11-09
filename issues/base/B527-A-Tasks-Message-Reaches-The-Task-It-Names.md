# [B527](README.md#b527) — A Task's Message Reaches The Task It Names

**Status:** Implementing. Filed 2026-11-08 from the operator's decision on move 3's 13c/22: "tasks reach each other only
by naming them".
**Size:** M - it replaces 2026-10-04's "every agent reads every message in the room" in the hand-out.

**What must be true.** A task's message that arrives through the project's conversation is handed to the task its
`metadata.to` names and to no other task. A message to `people` reaches no task ([B523](README.md#b523)). The room
still shows every message to the persons.

## Open for the plan reading

- **Which names count.** `metadata.to` names a task by its container (`porelo-p-review`, as the card lists it) and,
  in `TransportConversationsTest.whatArrivesGoesToEveryTaskOfThatProjectWhomeverItNamesAndNothingIsKept`, by its
  short name (`review`). Both must reach it, and [B526](README.md#b526)'s "Agent Review" may become a third.
- **A peer on another machine.** A message naming somebody not on this machine was handed to every task here. Under
  the decision it reaches none. Say so to the sender, or keep it silent as the room shows it anyway?
- **TalkPassProjectTest's removed siblings**: `aRemovedSiblingWhoseNameFitsTwoProjectsKeepsWhatItsRoomHeldForIt`
  and `aRemovedTaskOfAProjectNoLongerFollowedIsGivenNoOtherProjectsConversation` failed under a first narrowing.
  Read why before changing what they pin.
- The guide (`MailboxGuide`, doc/messages.md) and the decision in doc/decisions.md say the new rule.

## Why

In move 3 every message of one task woke the others, who filed them away as not meant for them, or answered.
