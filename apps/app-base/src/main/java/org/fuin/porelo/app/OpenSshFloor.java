package org.fuin.porelo.app;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;

/**
 * The lowest OpenSSH Porelo works with, asked of the machine.
 *
 * <p>Porelo signs and verifies with {@code ssh-keygen -Y}, and calls {@code match-principals},
 * which OpenSSH 8.9 adds. Below it a machine would otherwise learn this from whichever call fails
 * first. {@code ssh-keygen} has no option that prints its version, so {@code ssh -V} answers for
 * the package the two come from.
 *
 * <p>A version that cannot be read refuses nothing: an ssh that words its version differently is no
 * evidence of an old one, and a machine that works must not stop over it.
 */
public final class OpenSshFloor {

    /** What {@code porelo doctor} reports this under. */
    public static final String NAME = "openssh";

    /** The lowest OpenSSH Porelo works with, as it is said. */
    public static final String MINIMUM = "8.9";

    private static final int MINIMUM_MAJOR = 8;

    private static final int MINIMUM_MINOR = 9;

    /**
     * The start of what {@code ssh -V} writes: {@code OpenSSH_10.2p1 Ubuntu-...}. Only these digits
     * ever reach a line, and their bound keeps them a number.
     */
    private static final Pattern BANNER =
            Pattern.compile("^OpenSSH_(\\d{1,6})\\.(\\d{1,6})(p\\d{1,6})?(?=[\\s,]|$)");

    private OpenSshFloor() {}

    /** What the machine's OpenSSH answered. */
    public sealed interface Found permits Enough, TooOld, Unread {}

    /**
     * At the floor or above it.
     *
     * @param version The version found, such as {@code 10.2p1}.
     */
    public record Enough(String version) implements Found {}

    /**
     * Below the floor.
     *
     * @param version The version found, such as {@code 8.8p1}.
     */
    public record TooOld(String version) implements Found {

        /**
         * Says which version was found and which is needed.
         *
         * @return The sentence.
         */
        public String refusal() {
            return "OpenSSH "
                    + version
                    + " is too old; Porelo needs OpenSSH "
                    + MINIMUM
                    + " or newer, because it signs and verifies with 'ssh-keygen -Y' and an older"
                    + " one lacks an operation it calls";
        }
    }

    /**
     * No version could be read.
     *
     * @param reason Why, in words of Porelo's own, never what ssh wrote.
     */
    public record Unread(String reason) implements Found {

        /**
         * Says that the version is not known and that nothing is refused for it.
         *
         * @return The sentence.
         */
        public String sentence() {
            return "could not read the OpenSSH version ("
                    + reason
                    + "); Porelo needs "
                    + MINIMUM
                    + " or newer, and a start is not refused for this";
        }
    }

    /**
     * Asks the machine, through its runner.
     *
     * @param runner Runs {@code ssh -V}.
     * @return What it answered.
     */
    public static Found ask(final CommandRunner runner) {
        final CommandResult result;
        try {
            result = runner.run(Command.of("ssh", "-V"));
        } catch (final RuntimeException ex) {
            return new Unread("ssh could not be run");
        }
        if (!result.successful()) {
            return new Unread("'ssh -V' exited " + result.exitCode());
        }
        // To standard error, where a loader's warning may come first.
        return result.standardError()
                .lines()
                .map(BANNER::matcher)
                .filter(Matcher::find)
                .findFirst()
                .map(OpenSshFloor::judged)
                .or(() -> Optional.of(new Unread("'ssh -V' names no version Porelo knows")))
                .orElseThrow();
    }

    private static Found judged(final Matcher banner) {
        final int major = Integer.parseInt(banner.group(1));
        final int minor = Integer.parseInt(banner.group(2));
        final String portable = banner.group(3);
        final String version = major + "." + minor + (portable == null ? "" : portable);
        return major > MINIMUM_MAJOR || (major == MINIMUM_MAJOR && minor >= MINIMUM_MINOR)
                ? new Enough(version)
                : new TooOld(version);
    }
}
