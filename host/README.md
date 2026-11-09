# host

`porelo-host` is the host's own program, called by sudo rules and root's systemd units.
It reads root's organization status and task certificate records and sweeps ended task tokens.
Through sudo, a recorded, unlocked person who is not revoked by the organization can read any task whose principal names a project they belong to, using the host's people records for membership.
A task outside those projects is refused with status 65 and exactly the same output as an unrecorded fingerprint.
Root without `SUDO_USER` can read any task record.
Its project verbs register, remove, migrate and take off forge projects and report central state, check give-back and all refs before down, copy main privately, advance verified files, publish timestamped measurements to unlocked members and send or withdraw the pool run key; its root boundary depends on [wire](../wire/README.md), never on the applications or their environment-derived paths.
Forge HTTP administration comes from [organization/git-tree](../organization/git-tree/README.md); the local administrator reaches only the forge account's Unix socket, while central credentials are confined to the organization's forge.
The native profile builds `target/porelo-host`; [porelo-host(8)](src/main/man/porelo-host.8) describes its invocation boundary.
