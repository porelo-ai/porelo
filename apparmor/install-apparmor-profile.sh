#!/usr/bin/env bash
#
# Installs and loads Porelo's two AppArmor profiles, for an install that is not the .deb: an
# account install, a developer's build, a test machine. The .deb ships them under /etc/apparmor.d
# and loads them itself. Needed only where AppArmor runs; everywhere else Porelo works without it.
#
# 'porelo' is the label Porelo's binaries wear and confines nothing; 'porelo-task' is what a task's
# agent runs in, and may connect to no unix socket whose peer is not one of Porelo's binaries.
# Read both files next to this script; every rule says what it is for.
#
# Usage:  sudo ./install-apparmor-profile.sh            copy to /etc/apparmor.d and load
#         sudo ./install-apparmor-profile.sh --remove   unload and delete them again
set -euo pipefail

DIRECTORY="$(cd "$(dirname "$0")" && pwd)"
TARGET=/etc/apparmor.d
PROFILES=(porelo porelo-task)
REMOVE=/sys/kernel/security/apparmor/.remove

[ "$(id -u)" = 0 ] || {
    echo "run this with sudo: sudo $0 $*" >&2
    exit 2
}

if [ "$(cat /sys/module/apparmor/parameters/enabled 2>/dev/null || true)" != "Y" ]; then
    echo "AppArmor does not run on this machine; nothing to do"
    exit 0
fi

if [ "${1:-}" = "--remove" ]; then
    # The task's profile first: nothing is left that a task could be put into.
    for profile in porelo-task porelo; do
        printf '%s' "$profile" >"$REMOVE" 2>/dev/null || true
        rm -f "$TARGET/$profile"
    done
    echo "removed the porelo and porelo-task profiles"
    exit 0
fi

command -v apparmor_parser >/dev/null || {
    echo "apparmor_parser is not installed - on Debian and Ubuntu: apt install apparmor" >&2
    exit 2
}

# Where the porelo package installed them, they are its conffiles, and copying over them would
# have dpkg ask about every later update: the packaged files are loaded as they are.
if dpkg-query -S "$TARGET/porelo-task" >/dev/null 2>&1; then
    echo "the porelo package installed the profiles; loading those"
else
    for profile in "${PROFILES[@]}"; do
        [ -f "$DIRECTORY/$profile" ] || {
            echo "missing $DIRECTORY/$profile" >&2
            exit 2
        }
        # Under /etc/apparmor.d, so the machine loads them again at every boot.
        install -m 0644 "$DIRECTORY/$profile" "$TARGET/$profile"
    done
fi

# -r replaces a loaded profile of the same name, so this also takes a newer version.
apparmor_parser -r -W "$TARGET/porelo" "$TARGET/porelo-task"

echo "installed the porelo and porelo-task profiles"
echo "remove them again with: sudo $0 --remove"
