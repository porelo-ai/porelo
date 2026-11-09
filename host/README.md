# host

`porelo-host` is the host's own program, called by sudo rules and root's systemd units.
It reads root's organization status and the sudo person's task certificate records, and sweeps ended task tokens.
Its project verbs register, remove, migrate and take off forge projects and report central state; its root boundary depends on [wire](../wire/README.md), never on the applications or their environment-derived paths.
Forge HTTP administration comes from [organization/git-tree](../organization/git-tree/README.md); the local administrator reaches only the forge account's Unix socket, while central credentials are confined to the organization's forge.
The native profile builds `target/porelo-host`; [porelo-host(8)](src/main/man/porelo-host.8) describes its invocation boundary.
