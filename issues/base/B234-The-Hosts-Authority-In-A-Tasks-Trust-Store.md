# B234 — The Host's Authority In A Task's Trust Store

**Status:** Backlog.

**What must be true.** A task trusts a certificate authority the host names, so it reaches a service on the host
served under that authority's certificate without a project putting the certificate into its image.

## Why

porelo-host's forge role ([HO08](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho08)) serves its
registry over TLS with a certificate authority made on the host. `~/.config/porelo/ca-certificates.pem` is read only
by Porelo's own clients on the host (the broker, a token purchase, `vault authorize`); nothing puts it into a task's
trust store, and the image's snippet is per project, not per host.

## To be checked

- First, measured: where the task images' distributions read added authorities from, and whether a file mounted
  read-only at start reaches git, curl and the JVM's default trust in a task without rebuilding the image.
