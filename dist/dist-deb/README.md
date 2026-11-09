# dist-deb

Builds the `porelo` `.deb`: the executables of `apps/app`, `daemon` and `hooks`, the providers and egress
sets, the SELinux policy, the systemd user unit, the setup script and the bill of materials. It builds no executable
itself.

Also builds the separate `porelo-host` Debian package, with the privileged host binary, its section-8 man page and
copyright. It installs no sudo rule, unit or timer.

- Built with `-Pdist`; both packages land in `target/`. See [building](../../doc/build.md).
