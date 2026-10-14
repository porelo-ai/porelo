# B195 — Sokar Renamed Porelo Here

**Status:** Analyzed.
**Size:** L - a script is a day; the rest is the whole reactor, the native build, the acceptance on Ubuntu and an
enforcing Fedora, and reading the diff for the exceptions; contracts that four other repositories read change.

**What must be true.** This repository builds, ships and documents Porelo, not Sokar: no tracked file outside the
exceptions below says `sokar`, and Porelo is installed afresh - no state, command or path of Sokar is read or kept,
and no `sokar` command remains. From [PJ41](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj41).

## Plan

**How:** one commit, made by a script this repository's agent runs and reviews, never by hand: `git mv` for every
path naming `sokar`, then `sokar`/`Sokar`/`SOKAR` replaced by `porelo`/`Porelo`/`PORELO`, then the exceptions below
put back. Nothing is renamed before the window is announced.

**What carries the name**, counted on 2026-10-14: 13,482 occurrences in 1,190 of 1,296 tracked files, and 919 paths.
What others read:

- **Artifacts:** `org.fuin.sokar:*`, 45 of them, become `org.fuin.porelo:porelo-*` at `0.5.0`; Maven Central keeps
  the old ones as they are, with no relocation.
- **Contracts on the socket:** the varlink interfaces `org.fuin.sokar.Agent1`, `Build1`, `Clearance1`, `Review1`,
  `Tasks1`, `Tasks2`, `Transport1` become `org.fuin.porelo.*` - read by `sokar-frontend`, `sokar-intellij`, the build
  readers and the transports.
- **Programs and units:** `sokar` and `sokard` become `porelo` and `porelod` (the replacement gives `porelod` by
  itself), `sokard.service` `porelod.service`, the socket `porelod.sock`.
- **On the machine:** the packages `porelo`, `porelo-agent-*`; `/usr/libexec/porelo` (agents, builds, hooks,
  transports), `/usr/share/porelo`, the XDG directories; 47 `SOKAR_*` variables; the nft table `inet porelo`; the
  SELinux module and types (`porelo_exec_t`, `porelo_socket_t`); the container label, the names `porelo-<project>-<task>`
  and the branches `refs/heads/porelo/<task>`.
- **On the wire:** the SSHSIG namespace `sokar-message` (`MessageIntake.NAMESPACE`) that a message is signed under
  becomes `porelo-message` in the same window as `sokar-message-sluice`'s filter, which checks it - or every signed
  message is refused; the gate's refs `refs/sokar/base/` and `refs/sokar/incoming/`, read by `sokar-intellij`.
- **Native image metadata:** seven `META-INF/native-image/org.fuin.sokar/...` directories, moved with the packages.

**Exceptions**, put back after the replacement and listed in the test below:

- The 66 files under `changelog/v*`: released entries stay as released, saying Sokar. `0.5.0` is the first entry
  under the new name and says that it was Sokar.
- Whatever names something outside that is not renamed: the Artifactory repositories `sokar-dist-deb` and
  `sokar-dist-rpm` until Porelo's packages are out (then deleted), the signing keypair's name at Artifactory unless
  the preparation renames it, and `org.fuin.sokar` where a page says what Maven Central still holds.
- A sentence that says what the old name was.

**What the script cannot do by replacement:**

- **Two images draw the name**, `sokar.xcf` and `sokar-pitch.svg`: the word is edited in the image, keeping its
  design and colours.
- **Lengths:** `porelo` is one character longer per occurrence. Socket paths sit in the runtime directory because of
  the 108-character limit (`FollowCredential`), and `TaskListCommand` cuts names at 18; the tests that assert a cut or
  a padded line move and are read, not regenerated.
- **The Debian pool path** `pool/main/s/sokar*` in `build.yml` becomes `pool/main/p/porelo*`: the letter is the
  package name's first, which the replacement does not change.
- **Deploy keys at a forge** carry the title `sokar <host> <project>/<repository>` (`DeployKeys`), and Porelo finds
  its own by the title `porelo ...`: a key Sokar left at a forge is not Porelo's and is not removed by it. With no
  migration that is the person's to remove, and the documentation of clearing says so.
- **Names built from parts** are all `"sokar-" + ...` (21, container names and a bounce id) and are replaced with
  the rest; none splits the word.
- **Signed fixtures:** the SSH signatures in `MessageDispatchTest`, `MessagePassTest`, `MessageReadTest`,
  `MessageReleaseTest`, `TransportSendTest` and `SshSignatureTest` that carry the namespace inside their base64 are
  signed again with throwaway keys under `porelo-message`, not replaced; `GitGateTest`'s are read to see which
  namespace they carry.
- **Other fixtures:** the feature files and tests that name it are text and are replaced.

**Published before this builds:** `porelo-parent` `0.2.0` and the build tools `0.5.0` (both renamed in
`sokar-buildtools`' issues), the organisation and this repository renamed on GitHub, so `check-citations` resolves.

**How it is tested:**

1. **Red first:** a test tagged `documents` that fails while any tracked file outside the exceptions says `sokar`,
   in any case - seen red before the script, and kept.
2. The whole reactor, the documents, the native build, the package check, and the acceptance on the ubuntu VM and
   an enforcing Fedora, on accounts with no Sokar state.
3. Published as a snapshot, for the repositories built on it.

## Acceptance

- The test above is red before the script and green after it, and stays.
- The build, the native build and the acceptance on both legs are green on the renamed commit, on fresh accounts.
- `porelo` and `porelod` run from the packages; nothing of `sokar` is installed, read or called.

## To be checked

- That no other fixture is a recorded answer whose bytes include the name in a way the reader checks (a length
  header, a hash); none found by reading beyond the signatures above, confirmed by the reactor after the script
  (Agent Core's).
