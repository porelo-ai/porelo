# B309 — A Machine Sets Its Own Transport Settings

```json
{
  "$schema": "issue-1.json",
  "id": "B309",
  "title": "A Machine Sets Its Own Transport Settings",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 53,
  "blockedBy": [],
  "covers": "A machine sets for itself what a transport lists - for Matrix its homeserver, room, CA and registration token - over the signed project file's, so one project file serves machines on different servers; split from [B304](#b304)"
}
```

**What must be true.** A machine running a project may set some of a transport's settings for itself - for
Matrix, the homeserver, the room, the CA certificate and the registration token - in place of what the project file
names, so one project file serves machines that talk through different homeservers; every other setting stays the
project file's.

## Why

Split from [B304](README.md#b304), which
superseded an earlier choice (a machine's own settings over the signed project file's).
`apps/app-model/src/main/java/org/fuin/porelo/app/ProjectMembers.java` reads the project's transport settings;
for an organization project with no declared Matrix homeserver, it supplies the host's homeserver and room.
Arbitrary machine overrides of individual settings remain unbuilt.

What was built for it and taken out again, at `baa7e433` in porelo's history: `describe` naming `machineSettings`
and `machineSecrets`, an owner-only file per project and transport under the account's configuration written by
`porelo talk settings` and the daemon's `SetMachineTransportSettings`, one seam (`TransportSettings.of`) for every
reader of a project's transport settings, the machine's secret in a vault entry of its own, the `settings` check of
the merged map (`--loopback-only` for an offline project) before anything is kept, and the file removed with the
conversation once the transport's `clear` succeeded, and `VaultClearing.elsewhere` walking `mail().conversations()`
so a scheme named only through a peer, with this machine's server, is cleared too. **The risk to decide again:** the machine's values are the
account's unsigned file, so whoever writes as the account could point a guarded or online project's messages at
another server than the signed project file names.
