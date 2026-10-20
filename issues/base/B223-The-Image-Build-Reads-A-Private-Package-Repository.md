# B223 — The Image Build Reads A Private Package Repository

**Status:** Accepted; blocked by [B219](README.md#b219) and [B192](README.md#b192).

**What must be true.** An image's `snippet` fetches from a repository declared for [B219](README.md#b219) during the
image build, with the credential in no layer, no build argument and no file of the build context. Built together
with [B207](README.md#b207), which limits the build to the project's egress.
