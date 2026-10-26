# [B291](README.md#b291) — One SARIF Profile For Every Review

**Status:** Idea.

**What must be true.** Every review comment, finding, suggestion, thread and check round uses one closed,
versioned SARIF 2.1.0 profile, shared by the daemon, message filter, forge adapters, CLI, interface and IDE plugin.
The profile states what each producer may submit and what each destination may receive.

## Proposed

- Blocked by: none.
- Blocks: [B292](README.md#b292), [B293](README.md#b293), [B166](README.md#b166), [B171](README.md#b171),
  [B172](README.md#b172), [B176](README.md#b176), [B141](README.md#b141); the message filter's
  porelo-message-sluice [SL24](https://github.com/porelo-ai/porelo-message-sluice/blob/main/issues/README.md#sl24), adapters' review work
  porelo-adapters [GH02](https://github.com/porelo-ai/porelo-adapters/blob/main/issues/README.md#gh02), interface
  porelo-frontend [F107](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f107) and IDE plugin
  porelo-intellij [IJ04](https://github.com/porelo-ai/porelo-intellij/blob/main/issues/README.md#ij04).
- Root `version` is `2.1.0`; root `properties` contains only `porelo/profileVersion: "1"`. A run names one configured
  repository and exact commit in `versionControlProvenance[0]`, its base in `porelo/baseRevisionId`, and
  `columnKind: "unicodeCodePoints"`. Daemon-owned run properties name project, repository, review round and
  `porelo/completeness` (`complete`, `partial`, `failed`, `unknown`). An observed `porelo/reviewVerdict` is evidence,
  never approval authority.
- A discussion result has `kind: "review"`, `level: "none"`; a finding has `kind: "fail"`, an explicit `ruleId` and
  `note`, `warning` or `error` level. Each result has a daemon UUIDv4 `guid`. `porelo/anchorKind` is `line`, `file` or
  `general`; `porelo/diffSide` is `old` or `new`. General comments have no locations; file comments omit the region.
  Threads use `porelo/threadId`, `porelo/replyTo` and `porelo/threadState`; replies do not share a `correlationGuid`.
- Enumerate scalar keys and ownership in every property bag, at most 4 KiB each. Origin keys include
  `porelo/sourceKind`, `porelo/authorId`, `porelo/authorDisplayName`, `porelo/taskId`, `porelo/taskRole`,
  `porelo/forgeInstanceId` and daemon-derived `porelo/sourceLevel`. Native mappings, observed flags and resolver
  evidence are adapter/daemon-owned. Relocation fields required by [B176](README.md#b176) are enumerated here,
  including `porelo/outdatedReason` and scalar original-anchor fields; no unrestricted extension map is admitted.
- Live documents are at most 1 MiB UTF-8, one run, 100 results, depth 24 and 100,000 tokens. Bodies are at most 8 KiB
  each; at most four related locations; line spans are at most 100 inclusive lines. Pages distinguish a complete
  page from a complete review and cannot conceal missing evidence. Scanner import limits belong to
  [B292](README.md#b292).
- Artifact URIs are canonical repository-relative keys: decode once, re-encode and compare; at most 1,024 decoded
  UTF-8 bytes and 3,072 encoded ASCII bytes. Refuse schemes, authority, absolute paths, backslashes, drive prefixes,
  queries, fragments, empty/dot components, encoded separators, decoded percent signs and control/format characters.
  Match exact Git-tree entries without case folding, Unicode normalization, host filesystem reads, URL fetches,
  symlink traversal or submodule traversal. Line anchors and fixes require regular blobs; a mode diagnostic can name
  a link entry without dereferencing it or making it an actionable navigation target.
- A fix has exactly one artifact change and one replacement in the primary anchor's regular file on the new side.
  Resolve line/column defaults and exclusive end columns against the pinned blob; the replacement interval must
  equal the authorized anchor. Permit at most anchor lines plus 10, 400 Unicode scalar values per line and 8 KiB.
  No automatic application, file creation, deletion, rename, mode change, old-side or outdated actionable fix.
- Derive plain text and Markdown from one sanitized representation: paragraphs, simple lists, emphasis and code,
  with at most four nesting levels. No HTML, images, external links, autolinks or active controls. Refuse bidi and
  zero-width characters, malformed Unicode and disallowed controls after unescaping. Code is not silently rewritten.
- Drop sender snippets, artifact contents, invocation payloads, external references and all unlisted property bags.
  Reject forbidden authority fields from tasks; imports may drop nonessential fields with a bounded loss notice,
  and must hold any loss affecting an anchor, identity, state or edit. Suppressions are distinct authenticated
  decisions; suppressions, `baselineState` and missing results never make a review clean.

## To be checked

- Choose the shared module and publication boundary without adding review-library dependencies to hooks or
  broadening the five published artifacts without an explicit contract decision. `porelo-wire` supplies JSON and
  varlink; `porelo-build-api` supplies the existing adapter API.
- Specify every allowed field, scalar grammar and direction in bundled offline schemas and valid/adversarial
  fixtures; cover Unicode columns, literal braces, conflicting coordinates and destination-specific projection.
- Verify identical fixtures at the daemon, filter, adapter, interface and IDE boundaries, including body/rendering
  agreement and unsupported native capabilities. Keep profile semantics here and library behavior in
  [B292](README.md#b292).
