# B309 — A Machine Sets Its Own Transport Settings

**Status:** Idea.

**What must be true.** A machine that follows a project may set some of a transport's settings for itself - for
Matrix, the homeserver, the room, the CA certificate and the registration token - in place of what the project file
names, so one project file serves machines that talk through different homeservers; every other setting stays the
project file's.

## Why

Split from [B304](README.md#b304) by the operator on 2026-10-27, which
superseded an earlier choice (a machine's own settings over the signed project file's): for now the signed project file names the
homeserver, the room, the CA's text as `ca_certificate` and a `registration_token_file`, and every machine reaches the same name,
so no machine needs settings of its own. It comes back when machines of one project must reach different servers
under one project file.

What was built for it and taken out again, at `baa7e433` in porelo's history: `describe` naming `machineSettings`
and `machineSecrets`, an owner-only file per project and transport under the account's configuration written by
`porelo talk settings` and the daemon's `SetMachineTransportSettings`, one seam (`TransportSettings.of`) for every
reader of a project's transport settings, the machine's secret in a vault entry of its own, the `settings` check of
the merged map (`--loopback-only` for an offline project) before anything is kept, and the file removed with the
conversation once the transport's `clear` succeeded, and `VaultClearing.elsewhere` walking `mail().conversations()`
so a scheme named only through a peer, with this machine's server, is cleared too. **The risk to decide again:** the machine's values are the
account's unsigned file, so whoever writes as the account could point a guarded or online project's messages at
another server than the signed project file names.
