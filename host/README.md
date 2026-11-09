# host

`porelo-host` is the host's own program, called by sudo rules and root's systemd units.
`task start <task>` reads the sudo person's certificate on stdin and answers its gate token alone; `task end <task>` deletes that token and retains the record.
`forge issue-token` rotates the person's token, while `forge lend-token` preserves concurrent lends; both answer only the token on stdout.
`org follow` reconciles the signed organization from root's timer; `org refresh` follows immediately with a waiting migration's central token on stdin; `org renew-pin` takes renewed admin pins on stdin. All three run as root without `SUDO_USER`.
It reads root's organization status and task certificate records and sweeps ended task tokens.
Through sudo, a recorded, unlocked person who is not revoked by the organization can read any task whose principal names a project they belong to, using the host's people records for membership.
A task outside those projects is refused with status 65 and exactly the same output as an unrecorded fingerprint.
Root without `SUDO_USER` can read any task record.
Its project verbs register, remove, migrate and take off forge projects and report central state, check give-back and all refs before down, copy main privately, advance verified files, publish timestamped measurements to unlocked members and send or withdraw the pool run key; its root boundary depends on [wire](../wire/README.md), never on the applications or their environment-derived paths.
Forge HTTP administration comes from [organization/git-tree](../organization/git-tree/README.md); the local administrator reaches only the forge account's Unix socket, while central credentials are confined to the organization's forge.
The native profile builds `target/porelo-host`; [porelo-host(8)](src/main/man/porelo-host.8) describes its invocation boundary.

`forge gate --web|--git|--runner --upstream <socket>` serves a systemd accepted connection; web and runner require Unix peers, and only an admitted person's web request receives a forge sign-in.
`forge measure` measures an installed gate's activation and web keep-alive, reporting connections, failures, throughput and latency as JSON.
`pool revert <list>` watches the pool, `pool revert --one <name>` settles one VM, `pool give-back` schedules the sudo run account's return, `pool make` imports a VM from a prepared seed, `pool address NAME [SECONDS]` waits up to 300 seconds by default for its IPv4 lease, and `pool delete NAME` removes its domain and volumes and checks that none remain.
The pool sets the restored clock before raising a VM's link; seed construction remains with the collection's build tool.

Web admission is resolved once per accepted connection; every request still checks its host, route and credentials.
Run `porelo-host forge measure --socket /path/to/web.sock --host host.localhost:3000 --requests 1000 --concurrency 32 --per-connection 8` against each installed gate to compare them.
