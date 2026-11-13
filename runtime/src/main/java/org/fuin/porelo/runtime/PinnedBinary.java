package org.fuin.porelo.runtime;

import java.util.List;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.CommandResult;

/**
 * The program a task's image is built to start, resolved once at build and checked at each start.
 *
 * <p>A start that looked a bare name up on {@code PATH} ran whatever the agent's user had left
 * there, or written over, in an earlier task. The image records the absolute path and its resolved
 * target where only root writes, and each start runs that path, refused when the agent's user could
 * change it or any directory above it.
 *
 * <p><strong>A file, not a label.</strong> A {@code LABEL} holds what the Containerfile spells, and
 * this is known only once the layers above have run. The start reads the file in the same root exec
 * that checks it, so nothing is read from where the agent writes.
 */
public final class PinnedBinary {

    /** The root-owned directory holding one record per program, named after it. */
    public static final String RECORDS = "/etc/porelo/binary";

    /** A program name as it can be looked up on {@code PATH} and name a file of its own. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");

    /** The check's {@code $0}: what names it among a container's execs. */
    public static final String CHECKED_BY = "porelo-pinned-binary";

    /** What each line of a clean answer starts with, the word it gives after it. */
    private static final String RUN = "run\t";

    /** What the check exits with when it refuses, its reason and path on standard output. */
    private static final int REFUSED = 3;

    /**
     * Run as the container's root, with the program's name as {@code $1}: prints, one {@code
     * run<TAB>word} line each, the words a start runs before the agent's own arguments - the
     * recorded path, or the recorded interpreter, its argument and that path. Exits {@value
     * #REFUSED} with the reason and the path instead when the agent's user could change what they
     * run.
     *
     * <p>Checked for the program and its interpreter alike: every link it passes through, every
     * directory any resolution of any of them passes through, up to {@code /}, and the record
     * itself. Asked of the kernel as the agent's user ({@code test -w} under {@code setpriv}, as
     * each pane drops to it), so owner, group and other bits and ACLs answer as they would for the
     * agent; an owner can change the bits, so a path the agent owns is refused whatever they are.
     */
    static final String CHECK =
            String.join(
                    "\n",
                    "unset BASH_ENV ENV",
                    "PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH",
                    "refuse() { printf '%s\\t%s\\n' \"$1\" \"$2\"; exit " + REFUSED + "; }",
                    "record=" + RECORDS + "/$1",
                    "[ -f \"$record\" ] && [ ! -L \"$record\" ] || refuse unrecorded \"$1\"",
                    "path=; target=; interp=; itarget=; iarg=",
                    "{ IFS= read -r path; IFS= read -r target; IFS= read -r interp;"
                            + " IFS= read -r itarget; IFS= read -r iarg; } < \"$record\"",
                    "absolute() { case $1 in /*) ;; *) refuse unrecorded \"$2\" ;; esac; }",
                    "absolute \"$path\" \"$1\"; absolute \"$target\" \"$1\"",
                    "[ -z \"$interp\" ] || { absolute \"$interp\" \"$1\"; absolute \"$itarget\" \"$1\"; }",
                    "uid=$(id -u agent) || refuse unchecked agent",
                    "changeable() {",
                    "    owner=$(stat -L -c %u -- \"$1\" 2>/dev/null) || return 2",
                    "    [ \"$owner\" = \"$uid\" ] && return 0",
                    "    setpriv --reuid=agent --regid=agent --init-groups -- /bin/sh -c \\",
                    "        'if test -w \"$1\"; then exit 10; else exit 11; fi' sh \"$1\"",
                    "    case $? in 10) return 0 ;; 11) return 1 ;; *) return 2 ;; esac",
                    "}",
                    "above() {",
                    "    d=$1",
                    "    while :; do",
                    "        changeable \"$d\"",
                    "        case $? in 0) refuse changeable \"$d\" ;; 1) ;; *) refuse unchecked \"$d\" ;; esac",
                    "        [ \"$d\" = / ] && return 0",
                    "        d=$(dirname -- \"$d\")",
                    "    done",
                    "}",
                    // Every prefix resolved, so a directory reached through a link in the middle of
                    // the path is checked where it really is, with every directory above it.
                    "through() {",
                    "    rest=${1#/}; p=",
                    "    while [ -n \"$rest\" ]; do",
                    "        part=${rest%%/*}",
                    "        case $rest in */*) rest=${rest#*/} ;; *) rest= ;; esac",
                    "        [ -n \"$part\" ] || continue",
                    "        p=$p/$part",
                    "        r=$(readlink -f -- \"$p\") || refuse unchecked \"$p\"",
                    "        above \"$r\"",
                    "    done",
                    "}",
                    "pinned() {",
                    "    now=$(readlink -f -- \"$1\") || refuse moved \"$1\"",
                    "    [ \"$now\" = \"$2\" ] || refuse moved \"$1\"",
                    "    hop=$1; n=0",
                    "    while :; do",
                    "        above \"$hop\"; through \"$hop\"",
                    "        [ -L \"$hop\" ] || break",
                    "        n=$((n + 1)); [ \"$n\" -le 40 ] || refuse unchecked \"$hop\"",
                    "        next=$(readlink -- \"$hop\") || refuse unchecked \"$hop\"",
                    "        case $next in /*) hop=$next ;;"
                            + " *) hop=$(readlink -f -- \"$(dirname -- \"$hop\")\")/$next ;; esac",
                    "    done",
                    "    above \"$2\"; through \"$2\"",
                    "}",
                    "script() { [ \"$(head -c 2 -- \"$1\" 2>/dev/null)\" = '#!' ]; }",
                    // A record from before interpreters were recorded must not run a script by its
                    // shebang, which the kernel would look up as it reads it.
                    "[ -n \"$interp\" ] || ! script \"$target\" || refuse unrecorded \"$1\"",
                    "pinned \"$path\" \"$target\"",
                    "if [ -n \"$interp\" ]; then",
                    "    pinned \"$interp\" \"$itarget\"",
                    "    ! script \"$itarget\" || refuse unchecked \"$interp\"",
                    "fi",
                    "above \"$record\"; through \"$record\"",
                    "if [ -n \"$interp\" ]; then",
                    "    printf 'run\\t%s\\n' \"$interp\"",
                    "    [ -z \"$iarg\" ] || printf 'run\\t%s\\n' \"$iarg\"",
                    "fi",
                    "printf 'run\\t%s\\n' \"$path\"");

    /** What a start makes of the check. */
    public sealed interface Answer permits Pinned, Unpinned {}

    /**
     * The program may be started, by these words.
     *
     * @param name The name it was looked up by.
     * @param run What a start runs before the agent's own arguments, checked: the recorded absolute
     *     path, or the recorded interpreter, its one argument where it has one, and that path.
     */
    public record Pinned(String name, List<String> run) implements Answer {

        /**
         * Constructor.
         *
         * @param name The name it was looked up by.
         * @param run What a start runs before the agent's arguments, never empty.
         */
        public Pinned {
            run = List.copyOf(run);
            if (run.isEmpty()) {
                throw new IllegalArgumentException("A pinned program runs something");
            }
        }

        /**
         * Returns a command with its program replaced by the checked words.
         *
         * @param command A command whose first word is {@link #name()}.
         * @return The same command, starting with {@link #run()}.
         * @throws IllegalArgumentException If the command starts with another program, which
         *     nothing has checked.
         */
        public List<String> running(List<String> command) {
            if (command.isEmpty() || !command.getFirst().equals(name)) {
                throw new IllegalArgumentException(
                        "The command starts with "
                                + (command.isEmpty() ? "nothing" : command.getFirst())
                                + ", not with "
                                + name
                                + ", the program its image pinned");
            }
            final List<String> pinned = new java.util.ArrayList<>(run);
            pinned.addAll(command.subList(1, command.size()));
            return List.copyOf(pinned);
        }
    }

    /**
     * The program must not be started.
     *
     * @param reason Why.
     * @param path The path the reason is about, or "" where there is none.
     */
    public record Unpinned(Reason reason, String path) implements Answer {}

    /** Why a program is not started. */
    public enum Reason {
        /** The agent's user could write the path, a directory above it or what it links to. */
        CHANGEABLE("can be written by the agent's user, who could replace what the start runs"),
        /** The path no longer resolves to the target the build recorded. */
        MOVED("no longer resolves to the file the image was built with"),
        /**
         * The container was made from an image built before images recorded the program; its image
         * cannot change, and a new task's start builds one that records it.
         */
        UNRECORDED(
                "is not recorded in the image this task's container was made from, which"
                        + " predates the check; a new task's start builds an image that records it"),
        /** The check could not answer, which is not an answer of yes. */
        UNCHECKED("could not be checked");

        private final String said;

        Reason(final String said) {
            this.said = said;
        }

        /**
         * Returns the reason in a person's words, after the path.
         *
         * @return The words.
         */
        public String said() {
            return said;
        }
    }

    private PinnedBinary() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Tells whether a name can be pinned: a bare program name, nothing a shell or a path reads.
     *
     * @param name The name.
     * @return {@code true} when it can.
     */
    public static boolean pinnable(String name) {
        return NAME.matcher(name).matches();
    }

    /**
     * Returns the Containerfile lines that resolve a program on root's {@code PATH} and record it,
     * as root, after every layer that could install it; the build fails when it is not there.
     *
     * @param name The program's bare name.
     * @return The lines, ending with the switch back to the agent's user.
     * @throws IllegalArgumentException If the name is no bare program name.
     */
    static List<String> recording(String name) {
        if (!pinnable(name)) {
            throw new IllegalArgumentException(
                    "'" + name + "' is no bare program name, so it cannot be pinned");
        }
        final String record = RECORDS + "/" + name;
        final String says = "porelo: " + name;
        return List.of(
                "# The agent's program, resolved once after every layer that installs it and recorded",
                "# where only root writes, with the interpreter a script names: each start runs these",
                "# paths, checked again, never a name looked up on PATH.",
                "USER root",
                "RUN set -eu; \\",
                "    fail() { echo \"$*\" >&2; exit 1; }; \\",
                "    found=$(command -v '" + name + "') \\",
                "        || fail '"
                        + says
                        + " is not a program on PATH after the image layers'; \\",
                "    case \"$found\" in /*) ;; *) fail '"
                        + says
                        + " is no file on PATH' ;; esac; \\",
                "    target=$(readlink -f -- \"$found\"); interp=; itarget=; iarg=; \\",
                "    if [ \"$(head -c 2 -- \"$target\")\" = '#!' ]; then \\",
                "        line=$(head -n 1 -- \"$target\" | cut -c 3-); \\",
                "        line=${line#\"${line%%[! \t]*}\"}; \\",
                "        interp=${line%%[ \t]*}; \\",
                "        iarg=${line#\"$interp\"}; iarg=${iarg#\"${iarg%%[! \t]*}\"}; \\",
                "        iarg=${iarg%\"${iarg##*[! \t]}\"}; \\",
                "        case \"$interp\" in /*) ;; *) fail \""
                        + says
                        + " starts with #!$line, which names no absolute interpreter\" ;; esac; \\",
                "        if [ \"$(basename -- \"$interp\")\" = env ]; then \\",
                "            case \"$iarg\" in ''|-*|*[!A-Za-z0-9._+-]*) \\",
                "                fail \""
                        + says
                        + " starts with #!$line, which names no one program for env\" ;; esac; \\",
                "            interp=$(command -v -- \"$iarg\") \\",
                "                || fail \""
                        + says
                        + " runs $iarg, which is not a program on PATH\"; \\",
                "            case \"$interp\" in /*) ;; *) fail \""
                        + says
                        + " runs $iarg, which is no file\" ;; esac; \\",
                "            iarg=; \\",
                "        fi; \\",
                "        [ -f \"$interp\" ] && [ -x \"$interp\" ] || fail \""
                        + says
                        + " runs $interp, which cannot run\"; \\",
                "        itarget=$(readlink -f -- \"$interp\"); \\",
                "        [ \"$(head -c 2 -- \"$itarget\")\" != '#!' ] \\",
                "            || fail \"" + says + " runs $interp, which is a script itself\"; \\",
                "    fi; \\",
                "    mkdir -p " + RECORDS + "; chmod 0755 " + RECORDS + "; \\",
                "    printf '%s\\n' \"$found\" \"$target\" \"$interp\" \"$itarget\" \"$iarg\" > "
                        + record
                        + "; \\",
                "    chmod 0644 " + record,
                "USER agent");
    }

    /**
     * Reads what the check answered.
     *
     * @param name The name it was asked about.
     * @param result The check's result.
     * @return Pinned only on a clean answer of {@code run} lines, the first and last absolute;
     *     anything else is not.
     */
    static Answer read(String name, CommandResult result) {
        final String output = result.standardOutput().strip();
        if (result.exitCode() == REFUSED) {
            final int tab = output.indexOf('\t');
            if (tab > 0 && output.indexOf('\n') < 0) {
                final Reason reason =
                        switch (output.substring(0, tab)) {
                            case "changeable" -> Reason.CHANGEABLE;
                            case "moved" -> Reason.MOVED;
                            case "unrecorded" -> Reason.UNRECORDED;
                            default -> Reason.UNCHECKED;
                        };
                return new Unpinned(reason, output.substring(tab + 1));
            }
            return new Unpinned(Reason.UNCHECKED, "");
        }
        if (result.exitCode() != 0) {
            return new Unpinned(Reason.UNCHECKED, "");
        }
        final List<String> run = new java.util.ArrayList<>();
        for (final String line : output.split("\n", -1)) {
            final String word = line.startsWith(RUN) ? line.substring(RUN.length()) : "";
            if (word.isEmpty() || word.chars().anyMatch(character -> character < 0x20)) {
                return new Unpinned(Reason.UNCHECKED, "");
            }
            run.add(word);
        }
        if (!run.getFirst().startsWith("/") || !run.getLast().startsWith("/")) {
            return new Unpinned(Reason.UNCHECKED, "");
        }
        return new Pinned(name, run);
    }
}
