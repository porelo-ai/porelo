# B202 — Credential Names In A Project File Checked

**Status:** Accepted.

**What must be true.** A credential's name in a project file is checked when the file is read, against the names that
can safely become what Porelo makes of them, and a name outside them is refused by name. What happens to a file that
holds such a name today is decided and said.

**Why.** Found in [B76](README.md#b76)'s rereading of `app-vault` (2026-10-17): a credential's name is not checked where it becomes a
route's token file and a variable's name in a task. Checking it narrows the project file's format, so the analysis
settles which names are allowed - letters, digits and which separators - and whether an existing file that holds
another is refused, warned about first, or kept working until a stated release.
