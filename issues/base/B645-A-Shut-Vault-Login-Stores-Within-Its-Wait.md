# B645 — A Shut Vault's Login Stores Within Its Wait

```json
{
  "$schema": "issue-1.json",
  "id": "B645",
  "title": "A Shut Vault's Login Stores Within Its Wait",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 85,
  "blockedBy": [],
  "covers": "The acceptance scenario of a login asked for while the vault is shut says 'Signed in - the credential is stored' within the time it waits on every leg, or the wait is measured and set as its sibling's is."
}
```

Filed 2026-11-12 under ST314 from porelo build 43228069308 (1d9997fb7). Its fedora leg failed on one scenario, and the
ubuntu leg was green: agents/agent-login.feature:62, "a login asked for while the vault is shut asks for the passphrase
first, then logs in and stores". It saw "stub: logged in", then waited 30 seconds for "Signed in - the credential is
stored as 'anthropic'" and did not see it (267 run, 1 failed). The change under test only touched the snapshot publish.

Not known as flaky: no earlier failure of this scenario is recorded in the issues or in the CI logs kept in the
hand-overs. A lead, read in the feature: the sibling scenario waits 120 seconds for the same line after the same
login, while this one waits 30. Here the store comes after the vault is opened with a typed passphrase, and it goes
through porelod.

**What must be true.** The scenario passes on both legs as long as the store happens. If the store is slower on fedora,
the wait follows a measurement, as the sibling's does. If the store did not happen at all, that is a fault and gets its
own red test. The rerun of 43228069308's failed jobs is the first measurement.
