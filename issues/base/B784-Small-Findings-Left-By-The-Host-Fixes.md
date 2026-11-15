# B784 — Small Findings Left by the Host Fixes

```json
{
  "$schema": "issue-1.json",
  "id": "B784",
  "title": "Small Findings Left by the Host Fixes",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 1,
  "blockedBy": [],
  "covers": "Small findings the readings of the host fixes left: exit codes, untested paths, acceptance gaps and checks still open",
  "openQuestions": 0
}
```

**What must be true.** What the readings of those fixes noted as left is either fixed, tested, or written down as
accepted, so nothing of it lives only in a deleted issue.

- `porelo-host org status`: a caller that is not root but carries a login session's variables now gets 77 (root's
  alone) where it got 78; the read's fixture runs as root only, so that path has no test.
- A taken project whose placement record is malformed resolves as unknown and is taken again, which fetches before
  it refuses; a take through to the conversation's setup is shown on a machine only, not by a unit test.
- With no upstream, any checkout whose `project.yml` names the project counts as its checkout, not only the one it
  was made from; only commits reachable from the branch come over, forward only. No unit test drives the start with a
  checkout that gained a commit.
- The key scenario still removes the gate's mirror by path before its start; that goes once the start from the
  checkout is on the test machine, with the acceptance kit's fixture change.
- The test of a start refused before any container drives a credential refusal (exit 69), not the secret scan's
  (65), which refuses on the same side; a start refused after enrollment still leaves an unfinished start.
- A vault entry carried sealed to a host: no RFC 7748 test vectors (the round trips and the low-order refusal stand
  for them); still to be checked that an interface's crash between seal and receive leaves only an expiring key in
  the host's memory.
- An agent's sign-in carried to a host: `ImportCredential` still answers the agent's own words; the importable
  sign-ins ask each agent once per provider with no overall deadline; no test pins that a shut vault and its guard
  are decided before the entry is built; still to be checked which agents' sign-ins are bound to this machine and
  must not be carried.
- `porelo host forge-token --if-forge` exits 0 without storing on a host with no forge, which a caller cannot tell
  from a stored token by the code; a window must not loop on it.
- The host's review read keeps the rows of a review deleted on the forge, and of a repository never carried again,
  while its watch lasts; a 401 on a review's confirming read is untested.
- An agent signed in through the vault: a credential type the provider's route cannot broker is answered signed in
  and refused at the start; an agent with no `config_dir`, or whose adapter fails, stays unknown without asking the
  vault; the vault is read once per agent without a sign-in; `AgentSignInRuns.state` keeps its own copy of the
  judgment; no test drives a held provider that is not the default.
- A run's time limit across a daemon restart: a new run that starts in the same moment the watch judges an orphaned,
  overdue run of its task due may be stopped by it; and an agent that ended on its own while no process waited, in a
  container still running, is ended at its deadline and read as ended by its limit, since nothing recorded its end.
