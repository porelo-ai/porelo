# B284 — A Test Install On An Rpm Host

```json
{
  "$schema": "issue-1.json",
  "id": "B284",
  "title": "A Test Install On An Rpm Host",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 70,
  "blockedBy": [],
  "covers": "The legs module's `deploy` installs on a Fedora machine, `--account` included, rather than unpacking only the `.deb` with `dpkg-deb`, which Fedora lacks"
}
```

**What must be true.** The legs module's `deploy` installs a build on a Fedora machine as it does on an Ubuntu one,
`--account` included, so a change is tested there against the binary it built.

## Why

`Deploy` takes only the newest `porelo_*.deb` from `dist/dist-deb/target`: `--account` unpacks it with `dpkg-deb -x`
(`Deploy.accountInstall`), and the machine-wide install runs `apt-get update` and `dpkg -i`. The `fedora` VM has
`rpm` and `rpm2cpio` but neither `dpkg-deb` nor `dpkg`, so a deploy there stops at the unpack and the account keeps
the binary it had. The `.rpm` the same build makes, in `dist/dist-rpm/target`, is
installed there by hand today.
