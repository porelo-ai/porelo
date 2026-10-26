# B294 — A Task's Trust And Limits Measured Where Not Yet Seen

**Status:** Idea.

**What must be true.** What `doc/credentials.md` says of the task authority and `doc/project-file.md` says of a task's
limits holds for the tools and hosts not yet measured, or the docs say where it does not.

## Why

The task authority and the default CPU limit are built and were seen working on a rented Ubuntu host: curl in a task
reached the host's forge under its authority without `--cacert`, and `nproc` read 4. Other tools and Fedora were not
part of that test.

## To be checked

- **The task authority:** git and Node in a task against a host service served under the authority; two concurrent
  tasks each with its own copy and private label on an SELinux host, and the account's file keeping its label.
- **A task's limits:** whether GraalVM's `native-image` in a task sizes its build threads from the CPU limit (the
  quota or `OMP_NUM_THREADS`), not the host's cores, since a native build is the largest build in a task today; the
  open-file limit and the delegated cgroup controllers on Fedora.
