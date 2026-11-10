# B442 — One Line Installs Everything On Fedora And RHEL

```json
{
  "$schema": "issue-1.json",
  "id": "B442",
  "title": "One Line Installs Everything On Fedora And RHEL",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 21,
  "blockedBy": [],
  "covers": "`sudo dnf install 'porelo*' keepassxc` installs all of Porelo on Fedora and RHEL, shown by a real run."
}
```

**Why this size.** the rpm side of the apt line for Debian and Ubuntu, with a real run.

**What must be true.** On Fedora and RHEL, `sudo dnf install 'porelo*' keepassxc` installs all of Porelo and updates it the
same way, on every published channel, shown by a real run on a Fedora VM.

## Why

The apt line covers Debian and Ubuntu only. The same line for dnf is a claim of its own: dnf reads a glob and conflicts its own way,
so what apt showed proves nothing here.

## Plan

After the next test. Run the line on a Fedora VM against each channel first, and take what it finds from there.
