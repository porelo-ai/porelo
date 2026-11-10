# B223 — The Image Build Reads A Private Package Repository

```json
{
  "$schema": "issue-1.json",
  "id": "B223",
  "title": "The Image Build Reads A Private Package Repository",
  "state": "Accepted",
  "size": "-",
  "priority": "Medium",
  "rank": 15,
  "blockedBy": ["B219"],
  "covers": "An image's `snippet` fetches from a declared private repository during the build, the credential in no layer."
}
```

**What must be true.** An image's `snippet` fetches from a repository declared for [B219](README.md#b219) during the
image build, with the credential in no layer, no build argument and no file of the build context. It was to be built
together with [B207](README.md#b207), which would have limited the build to the project's egress; that one is rejected,
so this stands alone.
