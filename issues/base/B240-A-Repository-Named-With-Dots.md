# B240 — A Repository Named With Dots

**Status:** Idea.

**What must be true.** A project names a repository by its real name, dots included (`porelo-ai.github.io`), as a
forge allows it, so a task's repository is the repository a person knows.

## Why

Measured on `0.5.1~snapshot.307`: `repositories: porelo-ai.github.io:` in a project file is refused with "Invalid
repository name 'porelo-ai.github.io', expected lower-case letters, digits and hyphens". The name becomes a mirror
directory, part of an image name and part of a container's; each allows a dot, but `..` and a leading dot must stay
refused.
