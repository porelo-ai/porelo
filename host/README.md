# host

`porelo-host` is the host's own program, called by sudo rules and root's systemd units.
It currently provides only help and version; its root boundary depends on [wire](../wire/README.md), never on the applications or their environment-derived paths.
The native profile builds `target/porelo-host`; [porelo-host(8)](src/main/man/porelo-host.8) describes its invocation boundary.
