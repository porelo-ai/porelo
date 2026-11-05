package org.fuin.porelo.packagecheck;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that one line installs everything on each published channel, as a person types it: {@code
 * apt install 'porelo*' keepassxc}. It simulates; nothing is installed or published.
 *
 * <p>Each repository publishes into a channel on its own, so two packages that conflict are seen by
 * nothing else. This reads the channels as they stand, so a run can turn red with nothing changed
 * here.
 */
final class ChannelLine {

    /** The channels a person can name. */
    static final List<String> CHANNELS = List.of("releases", "snapshots");

    /** What the line must never offer: packages meant only for a test. */
    static final Set<String> NEVER_OFFERED = Set.of("porelo-agent-stub");

    private static final String KEY =
            "https://fuinorg.jfrog.io/artifactory/api/security/keypair/porelo-packages/public";

    private static final String FINGERPRINT = "BF27A2FA637F505CAFBE431768D19D175D904415";

    private static final String REPOSITORY = "https://fuinorg.jfrog.io/artifactory/porelo-dist-deb";

    /** Says that the line was asked at all, so a failure before it cannot read as a pass. */
    static final String ASKED = "LINE-ASKED";

    private static final Pattern INSTALLED = Pattern.compile("(?m)^Inst (\\S+)");

    private static final Duration LIMIT = Duration.ofMinutes(10);

    private final BiFunction<List<String>, Duration, Commands.Result> run;

    /**
     * Constructor.
     *
     * @param run runs a command within a limit; a test has its own
     */
    ChannelLine(final BiFunction<List<String>, Duration, Commands.Result> run) {
        this.run = run;
    }

    /**
     * Checks every channel on the distributions the packages support.
     *
     * <p>Throws rather than exiting: this runs inside Maven's own JVM.
     *
     * @param args the repository root, or nothing for the current directory
     */
    public static void main(final String[] args) {
        final Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        final boolean passed = new ChannelLine(Commands::runMerged).check(root, System.out);
        if (!passed) {
            throw new IllegalStateException("the line does not install everything");
        }
    }

    /**
     * Checks every channel.
     *
     * @param root the repository root
     * @param out where the report goes
     * @return whether all passed
     */
    boolean check(final Path root, final PrintStream out) {
        final Report report = new Report(out);
        report.section("what the line must never offer");
        neverOfferedIsBuiltHere(root, report);
        for (final String channel : CHANNELS) {
            for (final String image : List.of(Main.UBUNTU, Main.DEBIAN)) {
                report.section(channel + " on " + image);
                verdict(
                        channel,
                        run.apply(
                                List.of(
                                        "podman",
                                        "run",
                                        "--rm",
                                        image,
                                        "sh",
                                        "-c",
                                        script(channel)),
                                LIMIT),
                        report);
            }
        }
        return report.failures() == 0;
    }

    /**
     * Fails for a name in the list this repository no longer builds, which could guard nothing.
     *
     * @param root the repository root
     * @param report where the verdict goes
     */
    static void neverOfferedIsBuiltHere(final Path root, final Report report) {
        for (final String name : NEVER_OFFERED) {
            final Path pom = root.resolve("agents/stub/pom.xml");
            try {
                if (Files.isRegularFile(pom) && Files.readString(pom).contains(name)) {
                    report.pass(name + " is a package this repository builds");
                } else {
                    report.fail(name + " is named, and " + pom + " does not build it");
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /**
     * What is run on a clean machine: the page's own steps, then the line, simulated.
     *
     * @param channel the channel
     * @return the script
     */
    static String script(final String channel) {
        return """
                set -e
                export DEBIAN_FRONTEND=noninteractive
                apt-get update -qq
                apt-get install -y -qq ca-certificates curl gnupg >/dev/null
                curl -fsSL %s -o /tmp/porelo.asc
                gpg --show-keys --with-colons /tmp/porelo.asc | grep -q '^fpr:::::::::%s:'
                gpg --dearmor -o /usr/share/keyrings/porelo.gpg < /tmp/porelo.asc
                echo "deb [signed-by=/usr/share/keyrings/porelo.gpg] %s %s main" \\
                    > /etc/apt/sources.list.d/porelo.list
                apt-get update -qq --error-on=any
                echo %s
                apt-get -s install 'porelo*' keepassxc
                """
                .formatted(KEY, FINGERPRINT, REPOSITORY, channel, ASKED);
    }

    /**
     * Judges what a channel answered.
     *
     * @param channel the channel
     * @param result what the script printed and how it ended
     * @param report where the verdict goes
     */
    static void verdict(final String channel, final Commands.Result result, final Report report) {
        final String output = result.output();
        if (!output.contains(ASKED)) {
            report.fail(
                    "the line could not be asked of "
                            + channel
                            + " (exit "
                            + result.exit()
                            + "): "
                            + output);
            return;
        }
        final String answer = output.substring(output.indexOf(ASKED));
        if (!result.ok()) {
            report.fail("the line does not install on " + channel + ": " + answer);
            return;
        }
        final List<String> offered = new ArrayList<>();
        final Matcher installed = INSTALLED.matcher(answer);
        while (installed.find()) {
            offered.add(installed.group(1));
        }
        final List<String> wrong = offered.stream().filter(NEVER_OFFERED::contains).toList();
        if (wrong.isEmpty() && offered.stream().anyMatch("porelo"::equals)) {
            report.pass("the line installs on " + channel);
        } else if (wrong.isEmpty()) {
            report.fail("the line on " + channel + " brings no porelo: " + answer);
        } else {
            report.fail("the line on " + channel + " offers what is only for a test: " + wrong);
        }
    }
}
