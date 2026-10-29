# B305 — Porelo's Agent Works In A Porelo Task

**Status:** Idea.

**What must be true.** porelo's agent works in a Porelo task on the move's host, as the project's procedure for an agent's
move lays out for the last builders,
and takes this issue to Review inside the task with the host's Forgejo build green. It talks in the project's room as
`Agent Core`, from the nickname `Core` that porelo-project's `project.yml` is to give the repository `core`
([B298](README.md#b298)), and can work on this machine again at any time.

## What moves, and what to check

- **The repository:** `porelo`, the repository `core` in porelo-project's `project.yml`. A task holds one repository,
  so `porelo-parent` (the repository `parent`) has no move of its own: a change to it is a task on `parent` that this
  agent starts when one is needed. `porelo-adapters` and `porelo-ai.github.io`, kept by the same agent today, move by
  issues of their own repositories.
- **The task's image:** this repository's `porelo.yml`, Ubuntu by digest with the pinned GraalVM and `shfmt`, for the
  unit tests and the native build. Its comment still calls it a draft nothing reads and claims the package check;
  both change with the move.
- **To be checked before the move:**
  - The native build's hooks link against the musl toolchain from the build tools, which `porelo.yml` does not
    install yet.
  - The package check runs `podman run` against several distributions' images, and the acceptance suite needs
    podman and a machine of its own: both run on the forge's legs, never in the task.
  - The vault's tests use the kernel keyring, whose system calls the task's seccomp refuses.
- **Forgejo's build:** `build.yml` rents legs today; it runs on the local forge only after [B306](README.md#b306).
- **The way back:** the task's open work is fetched from the host's forge into this machine's checkout, the
  repository's upstream switches back in `project.yml`, and the local session resumes with its watcher, as the project's procedure for an
  agent's move lays out.
