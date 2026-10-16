# Acceptance

What a person does at a terminal, run against a real machine. Three modules, the way `agents/` has
the contract beside the stub:

- [**kit**](kit/README.md) - `porelo-acceptance-kit`, published. Drives a machine over ssh from a
  Cucumber scenario and reports where GitHub shows it. Any repository's suite is built on it: this
  one's, and each agent's.
- [**suite**](suite/README.md) - `porelo-acceptance`, not published. This repository's own
  scenarios, and nothing else.
- [**legs**](legs/README.md) - `porelo-acceptance-legs`, not published. Builds this tree on a rented
  machine with [`ci/leg-build.sh`](../ci/leg-build.sh) and runs the suite there - what CI's legs do -
  and installs a handover on a machine somebody keeps.

```
./mvnw -pl acceptance/suite verify -Dporelo.acceptance.host=<machine> -Dporelo.acceptance.key=<key>
```
