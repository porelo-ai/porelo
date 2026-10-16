# B192 — An Image Chosen Per Repository

**Status:** Accepted; blocked by porelo [B170](README.md#b170).

**What must be true.** A project whose tasks work on different repositories gives each repository its own image -
built from that repository's devcontainer file, with the toolchain it needs (GraalVM and Maven, Flutter, Gradle,
Python) - and a task started on one repository gets that repository's image, rather than one image for the whole
project.

From [PJ39](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj39), where Porelo's own work moves into Porelo tasks, one task per issue, phase and repository.
