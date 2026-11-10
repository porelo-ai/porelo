# B434 — A Refused Start Says What Helps There

```json
{
  "$schema": "issue-1.json",
  "id": "B434",
  "title": "A Refused Start Says What Helps There",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 110,
  "blockedBy": [],
  "covers": "A start a host refuses for want of a certificate names `task start --host` at the person's own computer, not `ssh-add`."
}
```

**Why this size.** one refusal's words and one file's writing, with their tests; no contract other repositories read.

**What must be true.** A start a host refuses for want of a certificate says what helps the person where they are.

## Why

A bug: `TaskLaunch` answers "add the key of your card to your agent (ssh-add) and start it again". That helps a person in a
shell on the host with an agent there, which a host does not forward. A person starting from an interface, or by ssh, has
the key in their agent already, on their own computer, and running `ssh-add` changes nothing. The way that works,
`porelo task start ... --host LOGIN@HOST` at their own computer, is not named.

## Plan

- **`TaskLaunch`:** the refusal names `porelo task start TASK --host LOGIN@HOST --project PROJECT`, run at the person's own
  computer, with the task, the login and the project filled in and the host's address left to name. `ssh-add` stays second,
  for an agent on the host. The refusal for a project whose access file names no certifier stays as it is.
- **`doc/commands.md`** at the words on a host, if they then differ.
- With [B433](README.md#b433) done, a start through the daemon no longer ends here, and the words stay for a command.
- **`StartStepFile.write`:** a directory that is gone is said nothing about. `StartSteps.finish` writes the failed step late,
  and the likely cause is that the refusal's clean-up took the task's state first. Read so far, not traced to the end: the
  first test reproduces it before anything changes.
- **Red first:** a test that the refusal holds the `--host` line, and one that `write` into a removed directory warns nothing.

## Acceptance

- The refusal names the `--host` form with the task's project and the person's login, and it runs when copied, given the
  address.
- The refusal for a project whose access file names no certifier stays apart.
- A refused start warns nothing about its step; a step that cannot be written for another reason still warns.
