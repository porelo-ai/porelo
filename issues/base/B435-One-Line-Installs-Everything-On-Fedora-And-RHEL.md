# B435 — One Line Installs Everything On Fedora And RHEL

```json
{
  "$schema": "issue-1.json",
  "id": "B435",
  "title": "One Line Installs Everything On Fedora And RHEL",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 16,
  "blockedBy": [],
  "covers": "`dnf install 'porelo*'` installs all of Porelo on Fedora and RHEL, and the build says so when it stops working."
}
```

**What must be true.** On Fedora and RHEL, `sudo dnf install 'porelo*'` installs all of Porelo, on every published channel, and
stays true: the build says so when the line stops working, as the apt line's check does.

## To be checked

- Whether a runner or a container can resolve `dnf` against a channel without a rented machine.
- What the line needs beyond apt's: repository metadata is signed and the RPMs are not yet.
