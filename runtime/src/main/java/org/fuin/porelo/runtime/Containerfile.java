package org.fuin.porelo.runtime;

import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.TaskVariables;

/**
 * Builds the {@code Containerfile} for a project's task image.
 *
 * <p>Three layers, in this order and for this reason:
 *
 * <ol>
 *   <li><strong>base</strong> - the distro image the project chose, plus the unprivileged user and
 *       the fetch tooling every later layer needs;
 *   <li><strong>agent</strong> - whatever the selected agent contributes, which is normally a
 *       pinned and digest-verified download of its CLI;
 *   <li><strong>project</strong> - the operator's own additions, which is how extra tooling gets
 *       into a box.
 * </ol>
 *
 * Ordering is not cosmetic: the project layer runs last so it can rely on the agent being there,
 * and both run after the user exists so nothing has to guess at ownership.
 *
 * <p>Assembled by hand rather than from a template, on purpose: the byte-exact output is pinned by
 * golden-file tests, so when this moves to a template engine the tests decide whether the result is
 * still the same image.
 */
public final class Containerfile {

    /**
     * How many lines a task's shell session remembers.
     *
     * <p>Pinned so that "what may re-entering claim" has an answer. Ten thousand lines is enough to
     * hold a build somebody walked away from and small enough that a session left open costs
     * nothing worth counting.
     */
    public static final String SCROLLBACK = "10000";

    /**
     * Where the session's configuration is written, and read from explicitly.
     *
     * <p>Shared with everything that starts or joins a session, because a second spelling of this
     * path is a second session with different rules.
     */
    public static final String TMUX_CONF = "/etc/porelo/tmux.conf";

    /**
     * A person's own tmux settings, copied from their account's {@code ~/.config/porelo/tmux.conf}
     * when a task starts, owned by root so the agent reads it and cannot change it. Read after
     * Porelo's settings and before {@link #REQUIRED_TMUX_CONF}.
     */
    public static final String ACCOUNT_TMUX_CONF = "/etc/porelo/account-tmux.conf";

    /**
     * What Porelo itself depends on in a session, read last, so a person's own settings cannot
     * change it unawares.
     */
    public static final String REQUIRED_TMUX_CONF = "/etc/porelo/tmux-required.conf";

    /**
     * Name of the one session a task has.
     *
     * <p><strong>One, and every entry point uses it.</strong> Starting a task and attaching to it
     * used to reach the container by different routes - the start ran the agent under a plain
     * {@code exec} and only {@code attach} used a multiplexer - so attaching to a task that was
     * already running created a second, empty session beside the agent and showed a bare shell in
     * the workspace. Reported on 2026-09-12.
     */
    public static final String SESSION = "porelo";

    /**
     * The directory of Porelo's tmux server: the container's root's alone, mode 0700, so the agent
     * cannot reach the socket in it.
     */
    public static final String TMUX_DIRECTORY = "/run/porelo";

    /**
     * The socket of Porelo's tmux server, given to every tmux call of Porelo's with {@code -S}.
     *
     * <p>The server runs as the container's root and every pane drops to the agent through {@link
     * #AS_AGENT}, so the agent can neither connect to it nor set what the next session starts with.
     * The agent's own tmux servers are other servers and are left alone.
     */
    public static final String TMUX_SOCKET = TMUX_DIRECTORY + "/tmux";

    /**
     * What every pane of Porelo's session runs through: a root-owned script that clears tmux's own
     * variables, sets the agent's home, user and shell, and gives up root with {@code setpriv}
     * before it runs the agent's command, or the agent's login shell without one.
     */
    public static final String AS_AGENT = "/etc/porelo/as-agent";

    /**
     * File the session's own shell writes when it ends, inside the container.
     *
     * <p><strong>How finishing is told from detaching.</strong> Both end the command the terminal
     * was attached to, so something has to be asked afterwards.
     *
     * <p><strong>Why not ask tmux whether the session survived.</strong> Not because it races -
     * that was claimed here first and then measured on 2026-09-12, and it does not: {@code
     * has-session} answered "gone" on the first probe after the last window exited, five times out
     * of five. The reason is the direction it fails in. A container that cannot be asked, one that
     * has just died, one whose tmux is wedged - all of them make that question fail, and a failed
     * question there reads as "no session", which reads as "finished", which removes a task. This
     * one fails the other way: no answer means no marker means not finished, and the task is kept.
     *
     * <p>The shell removes it when the window starts and writes it when the window's shell returns,
     * so its presence is a statement about this session rather than an earlier one. Anything that
     * kills the session without the shell returning - {@code tmux kill-server}, a crash - leaves no
     * marker, which reads as "not finished" and keeps the task. That is the safe direction: work is
     * never discarded on an ambiguous signal.
     */
    public static final String SESSION_ENDED = "/tmp/.porelo-session-ended";

    /**
     * Build argument marking where the agent's layers begin.
     *
     * <p>Passing a value podman has not seen invalidates its cache from this line down, so the
     * agent's tooling is rebuilt and the packages above it are not. Nothing in the image reads it:
     * it exists to be changed.
     */
    public static final String LAYER_EPOCH = "PORELO_LAYER_EPOCH";

    /**
     * TEMPORARY, for Canonical's outage of 2026-11-11 (its archive and DNS down): a public Ubuntu
     * mirror outside Canonical's hosts, the last Ubuntu mirror tried. apt still checks every
     * signature. Removed by the issue that names this constant.
     */
    static final String OUTAGE_MIRROR = "http://mirrors.edge.kernel.org/ubuntu/";

    /** What the image's own package steps ask for, as the Debian family names it. */
    static final List<String> TOOLS =
            List.of(
                    "curl",
                    "ca-certificates",
                    "git",
                    "openssh-client",
                    "tmux",
                    "util-linux",
                    "python3");

    /**
     * The mirrors an apt step goes on to, in order, after a project's own sources, when an update
     * fails: azure's first, as the default, then Canonical's own, then {@link #OUTAGE_MIRROR}, then
     * Debian's. Each is tried only where it serves the archive a stanza names and would change it.
     */
    static final List<String> FALLBACK_PACKAGE_SOURCES =
            List.of(
                    "http://azure.archive.ubuntu.com/ubuntu/",
                    "http://archive.ubuntu.com/ubuntu/",
                    OUTAGE_MIRROR,
                    "http://deb.debian.org/debian");

    /** Where the sources step's per-source commands go, replaced once the lines are listed. */
    private static final String POINTING = "\u0000pointing";

    /** Where an apt step keeps the sources of the first mirror that only warned. */
    private static final String WARNED = "/var/tmp/porelo-apt-warned";

    /** Where an apt step's update goes, replaced once the lines are listed. */
    private static final String UPDATE = "\u0000update";

    private Containerfile() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Renders the Containerfile for a project with no additional layers.
     *
     * @param project The project.
     * @return File content, ending in a line separator.
     */
    /**
     * Returns a short digest of everything about a project that decides its image.
     *
     * <p><strong>Deliberately not the whole project file.</strong> Egress, limits and the upstream
     * change what a task may do rather than what it is built from, and hashing them would report an
     * image as stale after an edit that could not have changed it - which teaches people to ignore
     * the word.
     *
     * <p>The agent is not in it either: which agent runs is chosen per task rather than per
     * project, so an image is not stale because somebody picked a different one.
     *
     * @param project The project.
     * @return Sixteen hex characters - enough to tell two recipes apart, short enough to read.
     */
    public static String fingerprint(Project project) {
        // Porelo's own half of the recipe is in here too, and it was not always: the digest used to
        // cover the project's answers alone, so a Porelo release that changed what every image
        // contains produced no drift anywhere and existing images silently kept the old recipe.
        // Found on 2026-09-12, when adding a UTF-8 locale to the image fixed nothing on a machine
        // that already had one built - which is the shape of defect this label exists to prevent.
        //
        // Rendered without the agent's layers, so the rule above still holds: which agent runs is
        // chosen per task, and an image is not stale because somebody picked a different one.
        final String recipe =
                project.baseImage()
                        + "\u0000"
                        + (project.imageSnippet() == null ? "" : project.imageSnippet())
                        + "\u0000"
                        + body(project, ImageLayers.none());
        try {
            final byte[] digest =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(recipe.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder text = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                text.append(String.format("%02x", digest[index]));
            }
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", ex);
        }
    }

    public static String render(Project project) {
        return render(project, ImageLayers.none());
    }

    /**
     * Renders the Containerfile for a project.
     *
     * @param project The project.
     * @param layers What the agent and the project contribute.
     * @return File content, ending in a line separator.
     */
    public static String render(Project project, ImageLayers layers) {

        final List<String> lines = new ArrayList<>(List.of(body(project, layers).split("\n", -1)));
        // Trailing empty element from the split; the labels follow immediately.
        lines.removeLast();

        lines.add("");
        lines.add("LABEL org.fuin.porelo.project=\"" + project.name() + "\"");
        // What this image was built from, so an image built before the project file changed can be
        // told from one that is simply absent. Without it "prepared" says only that something
        // exists, and "this will not be what you expect" is discoverable only by starting work.
        lines.add("LABEL org.fuin.porelo.recipe=\"" + fingerprint(project) + "\"");
        lines.add(
                "LABEL org.fuin.porelo.security-class=\""
                        + project.securityClass().name().toLowerCase()
                        + "\"");
        // Last, so every layer above stays cached. It holds no time and nothing of the task: a
        // value that changed between two starts would give the same image a new id each time.
        // Empty when nothing is recorded, which covers a label that came through FROM.
        final ImageSettings settings = layers.settings();
        lines.add(
                "LABEL "
                        + ImageSettings.LABEL
                        + "=\""
                        + (settings == null ? "" : settings.rendered())
                        + "\"");

        return String.join("\n", lines) + "\n";
    }

    /**
     * Renders everything above the labels.
     *
     * <p>Separate because {@link #fingerprint(Project)} hashes it and the labels carry the
     * fingerprint: one method that did both would have to hash its own output.
     *
     * @param project The project.
     * @param layers What the agent and the project contribute.
     * @return The recipe without its labels, ending in a line separator.
     */
    /** Porelo's own root in a task's container, for content handed to the task. */
    public static final String HAND_IN_ROOT = "/porelo";

    /** Where a handed-in file appears, complete, and nothing else is. */
    public static final String HAND_IN_FILES = HAND_IN_ROOT + "/files";

    /**
     * Where a handed-in file is while it arrives, out of the agent's sight, on the same filesystem.
     */
    public static final String HAND_IN_INCOMING = HAND_IN_ROOT + "/.incoming";

    private static String packageSourcePattern(List<String> sources) {
        final List<String> archives = new ArrayList<>();
        for (final String source : sources) {
            final String path = source.replaceFirst("^https?://[^/]*", "").replaceFirst("/+$", "");
            if (path.isEmpty()) {
                return "^URIs:.*";
            }
            final String archive = path.substring(path.lastIndexOf('/') + 1);
            final StringBuilder escaped = new StringBuilder();
            for (final char character : archive.toCharArray()) {
                if ("\\.^$|?*+()[]{}".indexOf(character) >= 0) {
                    escaped.append('\\');
                }
                escaped.append(character);
            }
            archives.add(escaped.toString());
        }
        return ("^URIs:.*[[:space:]]https?://[^/[:space:]]+(/[^/[:space:]]+)*/("
                        + String.join("|", archives)
                        + ")/*([[:space:]].*)?$")
                .replace("|", "\\|");
    }

    /** The sed command that points every stanza serving a source's archive at that source alone. */
    private static String pointAt(String source) {
        return "sed -E -i 's|"
                + packageSourcePattern(List.of(source))
                + "|URIs: "
                + source
                + "|' \"$f\"; \\";
    }

    /** The mirrors an update goes on to, the project's own first, each once. */
    private static List<String> mirrors(Project project) {
        final java.util.LinkedHashSet<String> mirrors =
                new java.util.LinkedHashSet<>(project.effectivePackageSources());
        mirrors.addAll(FALLBACK_PACKAGE_SOURCES);
        return List.copyOf(mirrors);
    }

    /**
     * The first line of an apt step: downloaded packages kept in a build cache shared by the host's
     * builds of the same base image, locked so two builds do not write it at once.
     */
    private static String aptRun(Project project) {
        return "RUN --mount=type=cache,id=porelo-apt-"
                + project.baseImage().replaceAll("[^A-Za-z0-9._-]", "-")
                + ",target=/var/cache/apt/archives,sharing=locked set -eux; \\";
    }

    /**
     * An apt-get update that goes on to the next mirror when one fails, one at a time.
     *
     * <p>apt answers a mirror that refuses with a warning and exit 0 (measured with podman 5.7 on
     * debian:trixie-slim, 2026-11-11), so the output is read too, and a warning moves on as a
     * failure does. A mirror that would change no stanza is skipped rather than asked again.
     *
     * <p>When every mirror only warned, the step does not fail: it points the sources back at the
     * first that warned, updates again for its lists, and installs, as the recipe did before the
     * fallback. That mirror's lists are the most complete, and the install's own error is the
     * honest answer where they are not enough. Only when every mirror failed outright does the step
     * fail.
     */
    private static List<String> aptUpdate(Project project) {
        final List<String> lines = new ArrayList<>();
        lines.add(
                "        porelo_update() { code=0; out=$(apt-get update 2>&1) || code=$?; printf '%s\\n' \"$out\"; \\");
        lines.add("            [ \"$code\" = 0 ] || return 1; \\");
        lines.add(
                "            printf '%s\\n' \"$out\" | grep -q 'index files failed to download' || return 0; \\");
        lines.add("            [ -d " + WARNED + " ] || { mkdir -p " + WARNED + "; \\");
        lines.add(
                "                cp /etc/apt/sources.list.d/*.sources "
                        + WARNED
                        + "/ 2>/dev/null || :; }; \\");
        lines.add("            return 1; }; \\");
        lines.add(
                "        porelo_next() { before=$(cat /etc/apt/sources.list.d/*.sources 2>/dev/null | cksum); \\");
        lines.add(
                "            for f in /etc/apt/sources.list.d/*.sources; do [ -e \"$f\" ] || continue; \\");
        lines.add("                sed -E -i \"s|$2|URIs: $1|\" \"$f\"; done; \\");
        lines.add(
                "            [ \"$(cat /etc/apt/sources.list.d/*.sources 2>/dev/null | cksum)\" != \"$before\" ] || return 1; \\");
        lines.add(
                "            echo \"porelo: apt-get update failed; trying the next mirror, $1\" >&2; porelo_update; }; \\");
        lines.add("        porelo_warned() { [ -d " + WARNED + " ] \\");
        lines.add(
                "            || { echo 'porelo: apt-get update failed on every mirror' >&2; return 1; }; \\");
        lines.add(
                "            echo 'porelo: every mirror warned; installing with the lists of the first that answered' >&2; \\");
        lines.add(
                "            cp "
                        + WARNED
                        + "/*.sources /etc/apt/sources.list.d/ 2>/dev/null || :; apt-get update || :; }; \\");
        lines.add("        porelo_update \\");
        for (final String mirror : mirrors(project)) {
            lines.add(
                    "            || porelo_next '"
                            + mirror
                            + "' '"
                            + packageSourcePattern(List.of(mirror))
                            + "' \\");
        }
        lines.add("            || porelo_warned; \\");
        lines.add("        rm -rf " + WARNED + "; \\");
        return lines;
    }

    private static String body(Project project, ImageLayers layers) {

        // One URI a line: the first source that serves a stanza's archive, the rest kept in reserve
        // for an update that fails. Applied last to first, so the first one is what stays.
        final List<String> pointing = new ArrayList<>();
        for (final String source : project.effectivePackageSources().reversed()) {
            pointing.add("            " + pointAt(source));
        }
        // Only a project that ASKED for sources is refused on a base with no apt. One that asked
        // for nothing gets the default and must still work on fedora and alpine, which is most of
        // them - refusing there would break every non-Debian base for a key nobody wrote.
        final String refusal = project.declaresPackageSources() ? "true" : "false";

        final List<String> lines =
                new ArrayList<>(
                        List.of(
                                "# Generated by Porelo. Do not edit; changes are overwritten on the next build.",
                                "FROM " + project.baseImage(),
                                "",
                                "# curl, CA certificates, git and an ssh client: every pinned agent download needs the",
                                "# first two, the workspace is cloned with the third, and an online project pushes to",
                                "# a real upstream over ssh with the fourth. A base image cannot be assumed to have",
                                "# any of them. Written for the three package managers Porelo's supported bases use;",
                                "# a base with none of them must ship these itself, and will fail loudly here rather",
                                "# than three layers further down. tmux is here so a session survives leaving it:",
                                "# 'porelo task attach' runs it, and a closed window leaves the session running.",
                                "USER root",
                                "",
                                "# A mirror serves only its archive: match the last path segment so Debian security",
                                "# keeps its own URI and the Ubuntu default leaves Debian alone. A source without",
                                "# an archive path, such as a cache, replaces every URIs line. Suites, components",
                                "# and signing keys stay because they belong to the base image.",
                                "#",
                                "# Measured 2026-09-11 on a rented machine of the kind this build runs on, pulling a",
                                "# 19 MB package index while Ubuntu's archive was disrupted:",
                                "#",
                                "#   azure.archive.ubuntu.com   19.3 MB in  0.08s   230 MB/s",
                                "#   archive.ubuntu.com          2.2 MB in 60.00s    37 kB/s  (timed out)",
                                "#",
                                "# One source rather than a list: apt spreads requests over the URIs it is given",
                                "# instead of keeping the second in reserve, so a crawling mirror beside a fast one",
                                "# costs every build. With both, apt-get update took 4m03s for 65 MB of indices and",
                                "# the build hit Porelo's ten-minute cap. Somebody whose network prefers another",
                                "# mirror names it with build.package_sources. The others wait: an apt step whose",
                                "# update fails goes on to the next, one at a time, and says so.",
                                "#",
                                "# Downloaded packages are kept, in a build cache the apt steps mount; the lists",
                                "# are not, so an image installs against today's archive.",
                                "#",
                                "# http, not https: Ubuntu's mirrors serve no TLS, and apt takes its integrity from",
                                "# the signed Release file rather than from the transport.",
                                "RUN set -eux; \\",
                                "    if command -v apt-get >/dev/null 2>&1; then \\",
                                "        printf '%s\\n' 'Acquire::http::Timeout \"20\";' \\",
                                "            'Acquire::https::Timeout \"20\";' 'Acquire::Retries \"2\";' \\",
                                "            > /etc/apt/apt.conf.d/99-porelo-timeouts; \\",
                                "        printf '%s\\n' 'Binary::apt::APT::Keep-Downloaded-Packages \"true\";' \\",
                                "            > /etc/apt/apt.conf.d/99-porelo-keep-packages; \\",
                                "        rm -f /etc/apt/apt.conf.d/docker-clean; \\",
                                "        for f in /etc/apt/sources.list.d/*.sources; do \\",
                                "            [ -e \"$f\" ] || continue; \\",
                                POINTING,
                                "        done; \\",
                                "    elif " + refusal + "; then \\",
                                "        echo 'this project names build.package_sources, and "
                                        + project.baseImage()
                                        + " has no apt to apply them to' >&2; exit 1; \\",
                                "    fi",
                                "",
                                aptRun(project),
                                "    if command -v curl >/dev/null 2>&1 && command -v git >/dev/null 2>&1 \\",
                                "        && command -v ssh >/dev/null 2>&1 && command -v tmux >/dev/null 2>&1 \\",
                                "        && command -v setpriv >/dev/null 2>&1; then :; \\",
                                "    elif command -v apt-get >/dev/null 2>&1; then \\",
                                UPDATE,
                                "        apt-get install -y --no-install-recommends curl ca-certificates git openssh-client tmux util-linux \\",
                                "        && rm -rf /var/lib/apt/lists/* /var/cache/apt/*.bin; \\",
                                "    elif command -v apk >/dev/null 2>&1; then apk add --no-cache curl ca-certificates git openssh-client tmux setpriv; \\",
                                "    elif command -v dnf >/dev/null 2>&1; then dnf install -y curl ca-certificates git openssh-clients tmux util-linux && dnf clean all; \\",
                                "    else echo 'no curl/git/ssh/tmux/setpriv and no known package manager in "
                                        + project.baseImage()
                                        + "' >&2; exit 1; \\",
                                "    fi",
                                "",
                                "# Python 3 for the agents that evaluate it, added where the image has a package manager. Not one of",
                                "# the tools above that a task needs: an image without it, and without a way to add it, still builds.",
                                aptRun(project),
                                "    if command -v python3 >/dev/null 2>&1; then :; \\",
                                "    elif command -v apt-get >/dev/null 2>&1; then \\",
                                UPDATE,
                                "        apt-get install -y --no-install-recommends python3 \\",
                                "        && rm -rf /var/lib/apt/lists/* /var/cache/apt/*.bin; \\",
                                "    elif command -v apk >/dev/null 2>&1; then apk add --no-cache python3; \\",
                                "    elif command -v dnf >/dev/null 2>&1; then dnf install -y python3 && dnf clean all; \\",
                                "    else echo 'no python3 in "
                                        + project.baseImage()
                                        + " and no package manager to add it' >&2; \\",
                                "    fi",
                                "",
                                "",
                                "# How much a session remembers, pinned rather than inherited. What 'porelo task",
                                "# attach' can honestly claim on returning is exactly this many lines, and a",
                                "# figure nobody chose is a figure nobody can state. Written as a file so it is",
                                "# readable in the image rather than hidden in a command line.",
                                "#",
                                "# The terminal a program INSIDE the session sees is tmux's, not the one outside,",
                                "# so passing a 256-colour TERM into the container is only half of it: tmux's own",
                                "# default is 'screen', which is eight. Chosen at build time by asking this image",
                                "# what it can resolve, because a default-terminal naming an entry the image lacks",
                                "# is worse than the eight colours it was meant to replace.",
                                "#",
                                "# Extended keys always, so a modified Enter reaches an agent: with them off, agents",
                                "# in a task warned at every start that Shift+Enter may not work, and 'on' passed them",
                                "# only to a program asking in a way tmux 3.4 knows, which the agents do not. '-q',",
                                "# because a tmux older than the option would otherwise complain about the line.",
                                "# And xterm-like clients are told they take modified keys: tmux asks a client for",
                                "# them only when its terminal-features say so, and a client that answers the",
                                "# terminal queries plainly - the interface's does - is otherwise never asked.",
                                "#",
                                "# The rest makes a session feel like the terminal outside, each line for a difference",
                                "# measured on 2026-10-09 with tmux 3.4 behind 'podman exec -it': Esc arrived 501 ms",
                                "# late; Shift+Enter and focus events never arrived; truecolour, the clipboard, links",
                                "# and the title were dropped on the way out; and the wheel reached an agent in the",
                                "# alternate screen as nothing it had asked for. Shift+PageUp scrolls the session's",
                                "# history where the agent writes into it, and goes to the agent where it draws its",
                                "# own screen; Shift+PageDown scrolls back down and leaves the history at the bottom.",
                                "# Options an older tmux lacks are set with '-q'.",
                                "#",
                                "# 'latest' gives the window the size of the terminal acted in last, so a large view",
                                "# is not held to the size of a smaller terminal still attached. Two terminals of",
                                "# different sizes then hand the size back and forth, and the agent redraws each",
                                "# time; a person's own file may set 'smallest' to keep one size.",
                                "#",
                                "# Then a person's own file, copied in when a task starts, and last what Porelo itself",
                                "# depends on, so nothing in that file changes it unawares: among it the way to leave",
                                "# without ending the task, on the status line under the prefix in force, since people",
                                "# who forget it press Ctrl+z and leave the task stopped in their terminal.",
                                "RUN mkdir -p /etc/porelo \\",
                                "    && printf '%s\\n' \\",
                                "        'set -sg escape-time 10' \\",
                                "        'set -gq extended-keys always' \\",
                                "        'set -g focus-events on' \\",
                                "        'set -g set-clipboard on' \\",
                                "        'set -gq allow-passthrough on' \\",
                                "        'set -asq terminal-features \"xterm*:RGB:clipboard:hyperlinks:focus:title:extkeys\"' \\",
                                "        'set -g set-titles on' \\",
                                "        'set -g set-titles-string \"#T\"' \\",
                                "        'set -g mouse on' \\",
                                "        'bind -n S-PPage if -F \"#{alternate_on}\" \"send-keys S-PPage\" \"copy-mode -eu\"' \\",
                                "        'bind -n S-NPage if -F \"#{pane_in_mode}\" \"send-keys -X page-down\" \"send-keys S-NPage\"' \\",
                                "        'set -g window-size latest' \\",
                                "        'source-file -q " + ACCOUNT_TMUX_CONF + "' \\",
                                "        'source-file " + REQUIRED_TMUX_CONF + "' \\",
                                "        > " + TMUX_CONF + " \\",
                                "    && printf 'set -g history-limit "
                                        + SCROLLBACK
                                        + "\\n' > "
                                        + REQUIRED_TMUX_CONF
                                        + " \\",
                                "    && printf '%s\\n' \\",
                                "        'set -g status on' \\",
                                "        'bind d detach-client' \\",
                                "        'set -g default-command \"exec " + AS_AGENT + "\"' \\",
                                "        'set -g status-right-length 200' \\",
                                "        'set -ga status-right \" #{s/C-/Ctrl+/:prefix} d: leave, the task goes on\"' \\",
                                "        >> " + REQUIRED_TMUX_CONF + " \\",
                                "    && if infocmp tmux-256color >/dev/null 2>&1; then \\",
                                "        printf 'set -g default-terminal \"tmux-256color\"\\n' >> "
                                        + REQUIRED_TMUX_CONF
                                        + "; \\",
                                "    elif infocmp screen-256color >/dev/null 2>&1; then \\",
                                "        printf 'set -g default-terminal \"screen-256color\"\\n' >> "
                                        + REQUIRED_TMUX_CONF
                                        + "; \\",
                                "    fi",
                                "",
                                "# The agent never runs as root. A rootless podman user namespace already maps this",
                                "# to an unprivileged host uid, so this is defense in depth rather than the only line.",
                                "# No uid is pinned: 1000 is already taken on several common base images.",
                                "RUN id -u agent >/dev/null 2>&1 || useradd --create-home --shell /bin/bash agent",
                                "",
                                "# Porelo's tmux server runs as root, in a directory only root reaches, so the agent",
                                "# can neither connect to it nor plant what the next session starts with. Each pane",
                                "# leaves root through this script: tmux's own variables cleared, the agent's home,",
                                "# user and shell set, and root given up with setpriv - which needs the two",
                                "# capabilities a task container adds back, and leaves the agent holding none.",
                                "RUN command -v setpriv >/dev/null 2>&1 \\",
                                "        || { echo 'porelo: no setpriv in "
                                        + project.baseImage()
                                        + "' >&2; exit 1; } \\",
                                "    && printf '%s\\n' \\",
                                "        '#!/bin/sh' \\",
                                "        'unset TMUX TMUX_PANE' \\",
                                "        'entry=$(grep \"^agent:\" /etc/passwd) || exit 1' \\",
                                "        'home=${entry#*:*:*:*:*:}; home=${home%%:*}' \\",
                                "        'shell=${entry##*:}; [ -x \"$shell\" ] || shell=/bin/sh' \\",
                                "        'export HOME=\"$home\" USER=agent LOGNAME=agent SHELL=\"$shell\"' \\",
                                "        'drop=$(PATH=/usr/sbin:/usr/bin:/sbin:/bin; command -v setpriv) || exit 1' \\",
                                "        'case $# in 0) set -- \"$shell\" -l ;; 1) set -- \"$shell\" -c \"$1\" ;; esac' \\",
                                "        'exec \"$drop\" --reuid=agent --regid=agent --init-groups -- \"$@\"' \\",
                                "        > " + AS_AGENT + " \\",
                                "    && chmod 0755 " + AS_AGENT + " \\",
                                "    && mkdir -p "
                                        + TMUX_DIRECTORY
                                        + " && chmod 0700 "
                                        + TMUX_DIRECTORY,
                                "",
                                "RUN mkdir -p /workspace && chown agent:agent /workspace",
                                "",
                                "# Files handed to a running task land in /porelo/files, which holds nothing else, so an",
                                "# agent can be told to read all of it. /porelo is Porelo's: a base image that already has",
                                "# it is refused rather than shared with. A file arrives in /porelo/.incoming, where the",
                                "# agent cannot look, and is renamed into place whole. Both belong to root, so the agent",
                                "# reads what it was given and cannot change or remove it.",
                                "RUN if [ -e " + HAND_IN_ROOT + " ]; then \\",
                                "        echo 'porelo: the base image "
                                        + project.baseImage()
                                        + " already has "
                                        + HAND_IN_ROOT
                                        + ", which Porelo keeps for files handed to a task - use a base image without it' >&2; \\",
                                "        exit 1; \\",
                                "    fi \\",
                                "    && mkdir -p " + HAND_IN_FILES + " " + HAND_IN_INCOMING + " \\",
                                "    && chmod 0755 "
                                        + HAND_IN_ROOT
                                        + " "
                                        + HAND_IN_FILES
                                        + " && chmod 0700 "
                                        + HAND_IN_INCOMING));

        // Everything above is the base image and the packages every task needs. Everything below is
        // the agent's. This ARG is the seam between them: podman invalidates its cache from the
        // line whose text changed, so passing a different value here rebuilds the agent's layers
        // and keeps the package layer - which is the difference between a rebuild that takes
        // seconds and one that downloads a distribution again.
        //
        // It is declared even when no agent contributes anything, so the seam is in the image
        // rather than appearing only when something happens to sit below it.
        final int at = lines.indexOf(POINTING);
        lines.remove(at);
        lines.addAll(at, pointing);
        for (int index = lines.indexOf(UPDATE); index >= 0; index = lines.indexOf(UPDATE)) {
            lines.remove(index);
            lines.addAll(index, aptUpdate(project));
        }
        lines.add("");
        // Keep the packages cached when the CPU limit changes; ARG reaches RUN, not Config.Env.
        lines.add("ARG OMP_NUM_THREADS");
        lines.add("ARG " + LAYER_EPOCH + "=0");

        if (!layers.asRoot().isEmpty()) {
            lines.add("");
            lines.addAll(layers.asRoot());
        }

        lines.add("");
        lines.add("USER agent");
        lines.add("WORKDIR /workspace");
        lines.add("");
        lines.add("# Some agents install into ~/.local/bin, which is not on PATH for a non-login");
        lines.add("# shell - and 'podman exec' is one. The agent owns that folder, so it comes");
        lines.add("# last: a binary the agent leaves there never shadows a root-owned one.");
        lines.add("ENV PATH=" + TaskVariables.fixed().get("PATH"));
        lines.add("");
        // Measured on the Ubuntu VM, 2026-09-12: without this LANG is unset and LC_CTYPE is POSIX,
        // because neither the base image nor 'podman exec' sets one. An agent's terminal interface
        // then loses every character outside ASCII - its banner, its prompt markers and its spinner
        // all arrive as underscores - and the shell echoes what somebody types as octal escapes.
        // C.UTF-8 costs nothing: it is built into glibc and musl, so no locale has to be generated
        // and no package added.
        //
        // LANG rather than LC_ALL on purpose. LANG is the fallback every LC_* category uses when it
        // is not set itself, so this makes UTF-8 the default without taking away the ability to set
        // a category. LC_ALL would override anything the project or the agent chose.
        lines.add("# Without a UTF-8 locale an agent's interface arrives as underscores.");
        lines.add("ENV LANG=" + TaskVariables.fixed().get("LANG"));

        if (!layers.asAgent().isEmpty()) {
            lines.add("");
            lines.addAll(layers.asAgent());
        }

        return String.join("\n", lines) + "\n";
    }
}
