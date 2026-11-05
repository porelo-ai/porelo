# B445 — The Test Agent Has A Place Of Its Own

**Status:** Backlog.
**Size:** S - one upload step with its cleaning, then one removal.

**What must be true.** Every snapshot Build publishes the stub agent's binary by version, with its checksum, in a repository of
its own, readable anonymously, and depending on neither package index. Once its reader has moved, the stub is no longer in the
package indexes, so `apt install 'porelo*'` and `dnf` do not offer it.

## Plan

- A generic local repository `porelo-dist-test`, named as the others are. Path: `porelo-agent-stub/<version>/porelo-agent-stub`
  and `porelo-agent-stub.sha256` beside it. A step of its own in Publish, with `art_put`, so the upload carries both checksums.
- It keeps the 20 newest versions, and the same step deletes the older ones: about 15 MB each, so about 300 MB. A reader moves
  its named version with its other pins.
- `artifactory-smoke.yml` also checks the new repository.

## Order

1. The repository exists and the publication token may deploy and delete there. Only the operator can do this, and it is outward.
2. This publishes there beside the old way.
3. The interface's scenario reads it by a named version, checking the checksum.
4. The block in `.github/workflows/build.yml` that uploads to the indexes goes, and the comment in `agents/stub/pom.xml` is then true.
5. The old files are deleted from the indexes, on the operator's word.

Takes the place of [B429](README.md#b429)'s steps 1 and 2.
