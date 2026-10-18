# B210 — Build Plugins Without Their Known Advisories

**Status:** Idea.

**What must be true.** No Maven plugin that porelo-parent versions carries a dependency with a known advisory, where a
fixed version exists that has stood the age
[BT14](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt14) asks
(`porelo.release.min-age`, three days). Every Maven repository takes its plugins from the parent, so one move there
fixes them all.

**[B99](README.md#b99) may not start before the bouncycastle move.** The dentrassi `rpm` plugin brings
`bcprov-jdk18on` and `bcpg-jdk18on` 1.77, with ten advisories, two of them critical. Today its signing is off
(`rpm.sign.skip` is `true` in `dist/dist-rpm/pom.xml` and `agents/pom.xml`), so the OpenPGP code is loaded but never
called. [B99](README.md#b99) switches signing on, and from then on that code signs packages beside the signing key.

## Why

[PJ47](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj47)'s reading of 2026-10-18 looked up
in OSV every coordinate porelo, porelo-parent, porelo-build-github and the site resolve. Nothing that ships had an
advisory. Every hit was in a plugin's own dependencies, and every plugin version is set by porelo-parent. The other
repositories' readings found the same plugins:
- `cyclonedx-maven-plugin` 2.9.3 and `native-maven-plugin` 1.1.11 bring jackson 2.17.2 (15 advisories) and
  `cyclonedx-core-java` 9.0.5;
- `logchange-maven-plugin` 1.19.16 brings jackson 2.22.1;
- the dentrassi `rpm` 1.13.0 brings bouncycastle 1.77;
- `jdeb` 1.14, the codehaus `rpm-maven-plugin` 2.3.0 and `maven-jar-plugin` 3.5.0 bring `aircompressor` 0.27,
  `commons-compress` 1.20, `commons-lang3` and `snappy` 0.4;
- the core plugins (compiler, resources, enforcer, exec, build-helper, install, deploy, clean, javadoc and source)
  bring `plexus-utils` 3.0.24 to 4.0.2 and `commons-io`;
- `maven-site-plugin` 3.12.1 and `maven-project-info-reports-plugin` 3.9.0 are Maven's defaults for `site`. No Build
  runs `site`, and they hold the oldest of all: maven-core 3.2.5, dom4j 1.1, jetty 9.4.46, jsoup and velocity.

No plugin reads network input; each works on the repository's own files. But `Publish` runs `mvnw deploy` beside the
GPG key, so jdeb, rpm, cyclonedx and native run beside a secret there.

## The shape proposed

One move in porelo-parent, in the same wave as
[BT20](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt20) (`cyclonedx-core-java`), each
at the newest release three days old, read from Central on 2026-10-18:

1. **Where a newer release exists:**
   - `native-maven-plugin` 1.1.14 and `logchange-maven-plugin` 1.20.0;
   - `build-helper-maven-plugin` 3.6.2 and `exec-maven-plugin` 3.6.4;
   - `maven-jar-plugin` 3.5.1, install and deploy 3.2.0, compiler 3.16.0, resources 3.5.0, enforcer 3.6.3 and clean
     3.5.0;
   - `maven-site-plugin` **pinned** at 3.22.0, so that a `site` run never takes 3.12.1.
2. **Where no newer release exists** (`cyclonedx-maven-plugin` 2.9.3, the dentrassi `rpm` 1.13.0, `jdeb` 1.14, the
   codehaus `rpm-maven-plugin` 2.3.0, `maven-project-info-reports-plugin` 3.9.0): the parent's `pluginManagement`
   gives each plugin its fixed dependencies:
   - jackson 2.22.3;
   - `cyclonedx-core-java` at the version
     [BT20](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt20) takes;
   - `bcprov-jdk18on` and `bcpg-jdk18on` at the parent's own `bouncycastle.version`;
   - a fixed `aircompressor`, `commons-compress` and `plexus-utils`.

## To be checked

- **Whether each move clears its hits.** It is not measured. The measurement is the same reading run again on the
  moved parent, from a local install under a prefix of its own. After that, every Maven repository's whole check and
  both legs, since a parent snapshot reaches them all at once.
- **Whether a plugin works with a dependency newer than the one it was built against**, above all bouncycastle under
  the dentrassi `rpm` plugin, once signing is on.
