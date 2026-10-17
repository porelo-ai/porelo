#!/bin/sh
# How this tree is built on a rented machine: for its acceptance suite (acceptance/legs) and, by the machine images'
# build in porelo-buildtools, to prove an image can build it. The modules the suite installs and what they need.
#
# Run from the tree's root with the pinned GraalVM on the PATH. The one argument, if given, is the run number the
# snapshot version is stamped with. The module list is checked against the reactor by LegBuildScriptTest in
# acceptance/legs on every build, so a regrouping that breaks it fails here and not on a rented machine.
set -eu
MODULES=apps/app,daemon,hooks,agents/stub,builds/stub
# porelo's settings.xml, where the build tooling's snapshots come from; the machine has none of its own. The code
# style is the Build job's to check, and a rented machine has no shfmt to check it with.
exec ./mvnw -B -s settings.xml -Pnative -DskipTests -Dspotless.check.skip=true package ${1:+-Dporelo.snapshot.run=$1} -pl "$MODULES" -am
