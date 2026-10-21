# B243 — A Kit Project That Names Its Base Image

**Status:** Idea.

**What must be true.** A scenario in an agent repository can give the project it starts from a base image of its own,
through the acceptance kit's published steps, without editing the fixture by hand.

## Why

`a project called ... whose project file also says:` (`TerminalSteps.aProjectSaying`) writes `image: base_image:
"ubuntu:24.04"` itself and appends the scenario's text after it. Indented, the text continues that block; an `image`
mapping of its own is a second root `image` key, which the reader refuses. So a scenario cannot pin or change its base
image: measured in porelo-codex [CX01](https://github.com/porelo-ai/porelo-codex/blob/main/issues/README.md#cx01)'s
live wake case, which had to replace the step's line in its own fixture and follow the project again.
