# B192 — An Image Chosen Per Repository

**Status:** Accepted; blocked by sokar [B170](README.md#b170).

**What must be true.** A project whose tasks work on different repositories gives each repository its own image -
built from that repository's devcontainer file, with the toolchain it needs (GraalVM and Maven, Flutter, Gradle,
Python) - and a task started on one repository gets that repository's image, rather than one image for the whole
project.

From [PJ39](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj39), where Sokar's own work moves into Sokar tasks, one task per issue, phase and repository.
