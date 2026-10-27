# B298 — A Repository Names Its Agent

**Status:** Idea.

**What must be true.** A project file can name the agent each repository is worked with, as
`repositories.<repository>.agent`, and the daemon's `Projects` answer carries it on each `Repository`, so an interface
starts one task per repository with that repository's agent without asking for it.

## Why

porelo-frontend [F148](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f148), starting an
agent for each repository at one click, needs to know which agent a repository is worked with. Read in porelo at
0b02c1b7: `ProjectSchema` allows a repository `upstream`, `description`, `egress`, `limits`, `issues` and `image`, and
no agent; `Tasks1`'s `Start` says "a project file names neither an agent nor a provider"; porelo-project
[PJ53](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj53) calls it a new key.

## To be checked

- How a named agent relates to `--agent` and to the agent found on the machine ([B225](README.md#b225)): which wins,
  and whether an agent the machine does not have refuses the start or only the one-click start.
- Whether the key is signed like the rest of `project.yml` and needs nothing else on the host.
