package org.fuin.porelo.packagecheck;

import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Installs the packages in a clean container and asks the installed binary about itself.
 *
 * <p>Metadata can be right while the package does not install, so both are installed, including
 * that the agent package's dependency on porelo resolves. The commands run inside the container,
 * where a shell is the interface.
 *
 * @param deb the porelo deb
 * @param rpm the porelo rpm
 * @param agentDeb the agent deb; the agent rpm is found beside it
 * @param run the command runner, keeping both output streams
 */
record Install(
        Path deb,
        Path rpm,
        Path agentDeb,
        BiFunction<List<String>, Duration, Commands.Result> run) {

    Install(Path deb, Path rpm, Path agentDeb) {
        this(deb, rpm, agentDeb, Commands::runMerged);
    }

    // Bound a trickling mirror before trying the independent archive in a fresh container.
    private static final Duration UBUNTU_LIMIT = Duration.ofMinutes(3);

    // Debian has no mirror fallback, so retain its existing allowance for slow apt archives.
    private static final Duration DEBIAN_LIMIT = Duration.ofMinutes(10);

    private static final String AZURE = "http://azure.archive.ubuntu.com/ubuntu/";

    private static final String ARCHIVE = "http://archive.ubuntu.com/ubuntu/";

    private static final String STEP = "STEP: ";

    /** What the container prints for each step that worked, and what that step proves. */
    static final Map<String, String> MARKERS = markers();

    private static final String REPORTED = "REPORTED:";

    /**
     * What a step that failed prints before its own last lines, so a report says why rather than
     * only what.
     */
    static final String WHY = "FAILED:";

    /**
     * Says which step failed and the last lines it wrote. A step's output used to go to {@code
     * /dev/null}, so an install that failed on a mirror answered only that every later check had
     * nothing to report (2026-09-30). And {@code apt-get update} succeeds when it reaches no mirror
     * at all, so it is told {@code --error-on=any}: otherwise the install after it fails with "held
     * broken packages", which names the wrong cause - measured.
     */
    private static final String FAILED =
            """
            failed() { echo "FAILED: $1"; tail -n 6 /tmp/step.log | sed 's/^/FAILED:   /'; }
            """;

    private static final String COMMON =
            """
            echo "STEP: porelo --version"
            porelo --version >/dev/null 2>&1 && echo PORELO-OK
            echo "STEP: reporting the installed version"
            echo "REPORTED:$(porelo --version 2>/dev/null)"
            echo "STEP: porelo setup"
            porelo setup >/dev/null 2>&1 && echo SETUP-OK
            echo "STEP: checking the packaged hook descriptors"
            grep -qho "/usr/libexec/porelo/hooks/porelo-hook-nft" \\
                /root/.config/containers/oci/hooks.d/* 2>/dev/null && echo HOOKS-PACKAGED
            echo "STEP: porelo agents"
            porelo agents 2>/dev/null | grep -q stub && echo DISCOVERY-OK
            echo "STEP: porelo agents --verbose"
            porelo agents --verbose 2>/dev/null | grep -q "found by: /usr/share/porelo/agents.d/" && echo DESCRIBED
            echo "STEP: checking the installed agent filenames"
            ls /usr/libexec/porelo/agents/porelo-agent-* >/dev/null 2>&1 || echo NO-PORELO-NAME
            echo "STEP: running the packaged setup script"
            /usr/share/porelo/setup/porelo-setup.sh --show --user x 2>&1 \
                | grep -q "as the installed porelo $(porelo --version 2>/dev/null | cut -d' ' -f2)" \
                && echo SETUP-SCRIPT-RUNS
            """;

    private static Map<String, String> markers() {
        final Map<String, String> markers = new LinkedHashMap<>();
        markers.put("PORELO-OK", "the porelo package installs and the binary runs");
        markers.put("AGENT-OK", "the agent package installs; its dependency on porelo resolves");
        markers.put("SETUP-OK", "porelo setup writes the podman hook descriptors");
        markers.put("HOOKS-PACKAGED", "the descriptors point at the packaged hook binaries");
        markers.put("DISCOVERY-OK", "porelo discovers the agent it was never linked against");
        markers.put(
                "DESCRIBED",
                "the agent is found by its description under /usr/share/porelo/agents.d");
        markers.put(
                "NO-PORELO-NAME",
                "no file of Porelo's name is installed for it under /usr/libexec/porelo/agents");
        markers.put(
                "SETUP-SCRIPT-RUNS",
                "the packaged setup script runs and takes the channel of the porelo installed");
        return Collections.unmodifiableMap(markers);
    }

    /**
     * The install on Ubuntu using the selected mirror.
     *
     * @param mirror the Ubuntu archive URL
     * @return the script
     */
    String ubuntu(String mirror) {
        return apt(
                """
                echo "STEP: selecting the Ubuntu mirror"
                sed -i 's|^URIs:.*|URIs: %s|' \\
                    /etc/apt/sources.list.d/*.sources 2>/dev/null || true
                """
                        .formatted(mirror));
    }

    /** Checks Ubuntu, retrying only a timed-out mirror in a fresh container. */
    void checkUbuntu(String label, String version, Report report) {
        report.section("installs on " + label);
        Commands.Result result = runIn(Main.UBUNTU, ubuntu(AZURE), report);
        String mirror = AZURE;
        if (result.exit() == Commands.TIMED_OUT) {
            report.info(
                    atLimit(label, " on " + URI.create(AZURE).getHost(), result)
                            + "; tried again on "
                            + URI.create(ARCHIVE).getHost());
            lastOutput(result.output(), report);
            result = runIn(Main.UBUNTU, ubuntu(ARCHIVE), report);
            mirror = ARCHIVE;
        }
        checked(label, " on " + URI.create(mirror).getHost(), result, version, report);
    }

    /**
     * The install on Debian, from its own mirrors: Ubuntu's would serve it the wrong release.
     *
     * @return the script
     */
    String debian() {
        return apt("");
    }

    private static String apt(final String sources) {
        return FAILED
                + """
                logged() {
                    # Keep apt's status because POSIX pipelines answer for their last command.
                    { "$@"; echo "$?" > /tmp/step.exit; } 2>&1 | while IFS= read -r line || [ -n "$line" ]; do
                        printf '%s %s\\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$line"
                    done | tee /tmp/step.log
                    return "$(cat /tmp/step.exit)"
                }
                echo "STEP: selecting noninteractive apt"
                export DEBIAN_FRONTEND=noninteractive
                echo "STEP: configuring apt timeouts"
                printf '%s\\n' 'Acquire::http::Timeout "20";' 'Acquire::Retries "2";' \\
                    > /etc/apt/apt.conf.d/99-porelo-timeouts
                """
                + sources
                + """
                echo "STEP: apt-get update"
                logged apt-get update -qq --error-on=any || failed "apt-get update"
                echo "STEP: installing porelo"
                logged apt-get install -y -qq /deb/porelo_*.deb || failed "installing porelo"
                echo "STEP: installing the agent"
                logged apt-get install -y -qq /agent/porelo-agent-stub_*.deb && echo AGENT-OK \
                    || failed "installing the agent"
                """
                + COMMON;
    }

    /**
     * The install on Fedora's side.
     *
     * @return the script
     */
    String fedora() {
        return FAILED
                + """
                echo "STEP: installing porelo"
                dnf install -y -q /rpm/porelo-0*.rpm >/tmp/step.log 2>&1 || failed "installing porelo"
                echo "STEP: installing the agent"
                dnf install -y -q /agent/porelo-agent-stub-*.rpm >/tmp/step.log 2>&1 && echo AGENT-OK \
                    || failed "installing the agent"
                """
                + COMMON;
    }

    /**
     * Installs in one image and checks what it printed.
     *
     * @param label the distribution, for the report
     * @param image the image to install in
     * @param script what to run there
     * @param version the version the package carries
     * @param report where the verdicts go
     */
    void check(String label, String image, String script, String version, Report report) {
        report.section("installs on " + label);
        checked(label, "", runIn(image, script, report), version, report);
    }

    private Commands.Result runIn(String image, String script, Report report) {
        final String name = "porelo-package-check-" + UUID.randomUUID();
        final Duration limit =
                image.equals(Main.UBUNTU)
                        ? UBUNTU_LIMIT
                        : image.equals(Main.DEBIAN) ? DEBIAN_LIMIT : Commands.LIMIT;
        final Commands.Result result =
                run.apply(
                        List.of(
                                "podman",
                                "run",
                                "--rm",
                                "--name",
                                name,
                                "-v",
                                deb.getParent() + ":/deb:ro,Z",
                                "-v",
                                rpm.getParent() + ":/rpm:ro,Z",
                                "-v",
                                agentDeb.getParent() + ":/agent:ro,Z",
                                image,
                                "sh",
                                "-c",
                                script),
                        limit);
        if (result.exit() == Commands.TIMED_OUT) {
            remove(name, report);
        }
        return result;
    }

    private void remove(String name, Report report) {
        // Killing the podman client does not stop its container, even with --rm.
        try {
            final Commands.Result cleanup =
                    run.apply(List.of("podman", "rm", "-f", name), Duration.ofSeconds(30));
            if (!cleanup.ok()) {
                report.info("could not remove " + name + " (exit " + cleanup.exit() + ")");
                lastOutput(cleanup.output(), report);
            }
        } catch (UncheckedIOException ex) {
            report.info("could not remove " + name + ": " + ex.getMessage());
        }
    }

    private static void checked(
            String label, String mirror, Commands.Result result, String version, Report report) {
        if (result.exit() == Commands.TIMED_OUT) {
            report.fail(atLimit(label, mirror, result));
            lastOutput(result.output(), report);
        }
        verdicts(label, result.output(), version, report);
    }

    private static String atLimit(String label, String mirror, Commands.Result result) {
        return label
                + " ended at its limit"
                + mirror
                + lastValue(result.output(), STEP)
                        .map(last -> " during " + last)
                        .orElse(" before the first step");
    }

    private static void lastOutput(String output, Report report) {
        final List<String> lines = output.lines().filter(line -> !line.startsWith(STEP)).toList();
        lines.subList(Math.max(0, lines.size() - 6), lines.size()).forEach(report::info);
    }

    /**
     * Reads what an install printed.
     *
     * <p>What the binary says about itself has to be what the package says about it. It was not:
     * every build answered {@code 0.1.0-SNAPSHOT} while its package carried a build number, because
     * this check ran {@code porelo --version} and threw the answer away - it proved the binary
     * starts, which is not the same question.
     *
     * @param label the distribution, for the report
     * @param output what the container printed
     * @param version the version the package carries
     * @param report where the verdicts go
     */
    static void verdicts(String label, String output, String version, Report report) {
        final List<String> lines = output.lines().filter(line -> !line.startsWith(STEP)).toList();
        // Why comes first and once: every check after a failed install fails for the same reason.
        lines.stream().filter(line -> line.startsWith(WHY)).forEach(report::info);
        for (final Map.Entry<String, String> marker : MARKERS.entrySet()) {
            if (lines.contains(marker.getKey())) {
                report.pass(marker.getValue());
            } else {
                report.fail(marker.getKey() + " on " + label);
                lines.subList(Math.max(0, lines.size() - 5), lines.size()).forEach(report::info);
            }
        }
        final String reported = lastValue(output, REPORTED).orElse("");
        if (reported.equals("porelo " + version)) {
            report.pass("the binary names the build it came from (" + version + ")");
        } else {
            report.fail(
                    "the package is "
                            + version
                            + " but the binary says '"
                            + (reported.isEmpty() ? "nothing" : reported)
                            + "'");
            report.info(
                    "an interface cannot tell two daemons apart when every build answers the same");
        }
    }

    private static Optional<String> lastValue(String output, String prefix) {
        return output.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .reduce((first, second) -> second);
    }
}
