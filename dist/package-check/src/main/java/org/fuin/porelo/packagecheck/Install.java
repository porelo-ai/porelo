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
import org.jspecify.annotations.Nullable;

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
 * @param cache where each image's package lists and packages are kept between checks, or {@code
 *     null} for none
 */
record Install(
        Path deb,
        Path rpm,
        Path agentDeb,
        BiFunction<List<String>, Duration, Commands.Result> run,
        @Nullable Path cache) {

    /** Names the directory a build keeps between its runs, as the build's cache restores it. */
    static final String CACHE_VARIABLE = "PORELO_PACKAGE_CHECK_CACHE";

    Install(Path deb, Path rpm, Path agentDeb) {
        this(deb, rpm, agentDeb, Commands::runMerged, cacheFrom(System.getenv(CACHE_VARIABLE)));
    }

    Install(
            Path deb,
            Path rpm,
            Path agentDeb,
            BiFunction<List<String>, Duration, Commands.Result> run) {
        this(deb, rpm, agentDeb, run, null);
    }

    static @Nullable Path cacheFrom(final @Nullable String named) {
        return named == null || named.isBlank() ? null : Path.of(named).toAbsolutePath();
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

    /** Checks of the separate host deb, which has no RPM counterpart. */
    static final Map<String, String> HOST_MARKERS =
            Map.of(
                    "HOST-VERSION", "porelo-host answers the installed porelo version",
                    "HOST-NO-RUNTIME-OPTIONS",
                            "porelo-host refuses runtime options in its arguments",
                    "HOST-ENV-REFUSED", "porelo-host refuses JAVA_TOOL_OPTIONS with exit 78",
                    "HOST-MAN", "the host package installs its section-8 man page");

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

    // The apt script and the container carry names the privileged program deliberately refuses.
    static final String HOST =
            """
            echo "STEP: comparing porelo-host --version with porelo --version"
            porelo_version=$(porelo --version 2>/dev/null) \\
                && host_version=$(env -i PATH="$PATH" porelo-host --version 2>/dev/null) \\
                && [ "$host_version" = "porelo-host ${porelo_version#porelo }" ] && echo HOST-VERSION
            echo "STEP: refusing host runtime arguments"
            env -i PATH="$PATH" porelo-host -Dporelo.probe=1 --version >/dev/null 2>&1
            [ "$?" -ne 0 ] && echo HOST-NO-RUNTIME-OPTIONS
            echo "STEP: refusing the host runtime environment"
            env -i PATH="$PATH" JAVA_TOOL_OPTIONS=-Xmx1k porelo-host --version >/dev/null 2>&1
            [ "$?" -eq 78 ] && echo HOST-ENV-REFUSED
            echo "STEP: finding the host man page"
            (man -w porelo-host >/dev/null 2>&1 || [ -f /usr/share/man/man8/porelo-host.8 ]) && echo HOST-MAN
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

    /**
     * A fetch the mirror itself refused: a file its index names that its pool does not hold yet, or
     * one that arrived different, as a mirror caught mid-sync answers right after {@code apt-get
     * update}. Never a dependency or a package of ours, which stays red.
     */
    private static final java.util.regex.Pattern MIRROR_MISSED =
            java.util.regex.Pattern.compile(
                    "(?m)^FAILED: .*E: Failed to fetch \\S+\\s+(404\\s+Not Found|Hash Sum mismatch)");

    /**
     * Checks Ubuntu, retrying only an attempt the mirror failed - timed out, or missing a file it
     * names - each in a fresh container: the other mirror, then the first again, since one slow or
     * half-synced moment of a mirror failed a Build on its own.
     */
    void checkUbuntu(String label, String version, Report report) {
        report.section("installs on " + label);
        final List<String> mirrors = List.of(AZURE, ARCHIVE, AZURE);
        String mirror = mirrors.getFirst();
        Commands.Result result = runIn(Main.UBUNTU, ubuntu(mirror), slug(label, mirror), report);
        for (int next = 1; next < mirrors.size() && mirrorFailed(result); next++) {
            final String host = " on " + URI.create(mirror).getHost();
            report.info(
                    (result.exit() == Commands.TIMED_OUT
                                    ? atLimit(label, host, result)
                                    : label + host + " could not fetch a file its index names")
                            + "; tried again on "
                            + URI.create(mirrors.get(next)).getHost());
            lastOutput(result.output(), report);
            mirror = mirrors.get(next);
            result = runIn(Main.UBUNTU, ubuntu(mirror), slug(label, mirror), report);
        }
        checked(label, " on " + URI.create(mirror).getHost(), result, version, report);
    }

    private static boolean mirrorFailed(Commands.Result result) {
        return result.exit() == Commands.TIMED_OUT || MIRROR_MISSED.matcher(result.output()).find();
    }

    /**
     * The install on Debian, from its own mirrors: Ubuntu's would serve it the wrong release.
     *
     * @return the script
     */
    String debian() {
        return apt("");
    }

    /**
     * What a check's cache holds: the package lists of the last update that worked and the packages
     * the last install fetched, per image and mirror, in a directory mounted at {@code /cache}.
     * Copied in and out rather than used in place, so what apt writes as its own user never lands
     * there, and a failed update never leaves half a set of lists behind.
     */
    static final String CACHE = "/cache";

    /**
     * What a cached update is given before its lists are used instead: the rest is the install's.
     */
    static final Duration CACHED_UPDATE_LIMIT = Duration.ofSeconds(90);

    /** Said when the lists of the last update that worked stand in for one that failed. */
    static final String CACHED = "CACHED:";

    /**
     * Restores the cache before the update. With lists to fall back on, the update is bounded, so a
     * mirror that hangs leaves time to install from them; without, it runs as before and a mirror
     * that hangs is tried again elsewhere at the container's limit.
     */
    private static final String APT_CACHE_IN =
            """
            cache=%s
            lists=/var/lib/apt/lists
            archives=/var/cache/apt/archives
            bounded=""
            echo "STEP: restoring the cached package lists"
            if [ -d "$cache/lists" ] && [ -n "$(ls -A "$cache/lists" 2>/dev/null)" ]; then
                cp -R "$cache/lists/." "$lists/" && bounded="timeout %d"
            fi
            if [ -d "$cache/archives" ]; then
                cp "$cache/archives/"*.deb "$archives/" 2>/dev/null || true
            fi
            # Docker's images delete each package after its install; the cache keeps them.
            rm -f /etc/apt/apt.conf.d/docker-clean
            printf '%%s\n' 'Binary::apt::APT::Keep-Downloaded-Packages "true";' \
                > /etc/apt/apt.conf.d/99-porelo-keep
            keep_lists() {
                [ -d "$cache" ] || return 0
                rm -rf "$cache/lists.new" && mkdir "$cache/lists.new" \
                    && cp -R "$lists/." "$cache/lists.new/" \
                    && rm -rf "$cache/lists.new/partial" "$cache/lists.new/lock" \
                    && rm -rf "$cache/lists" && mv "$cache/lists.new" "$cache/lists"
            }
            reuse_lists() {
                echo "CACHED: apt-get update failed; installing from the package lists of the last update that worked"
                rm -rf "$lists/"* && cp -R "$cache/lists/." "$lists/"
            }
            """
                    .formatted(CACHE, CACHED_UPDATE_LIMIT.toSeconds());

    /** Keeps what this install fetched, and nothing older: only the newest set stays. */
    private static final String APT_CACHE_OUT =
            """
            echo "STEP: keeping the fetched packages"
            if [ -d "$cache" ]; then
                # Only what the lists still name: an older version is dropped, so the set never grows.
                apt-get autoclean -qq >/dev/null 2>&1 || true
                rm -rf "$cache/archives.new" && mkdir "$cache/archives.new" \
                    && { cp "$archives/"*.deb "$cache/archives.new/" 2>/dev/null || true; } \
                    && rm -rf "$cache/archives" && mv "$cache/archives.new" "$cache/archives"
            fi
            """;

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
                printf '%s\\n' 'Acquire::http::Timeout "20";' 'Acquire::Retries "5";' \\
                    > /etc/apt/apt.conf.d/99-porelo-timeouts
                """
                + sources
                + APT_CACHE_IN
                + """
                echo "STEP: apt-get update"
                if logged $bounded apt-get update -qq --error-on=any; then
                    keep_lists
                elif [ -n "$bounded" ]; then
                    reuse_lists
                else
                    failed "apt-get update"
                fi
                echo "STEP: installing porelo"
                logged apt-get install -y -qq --no-install-recommends /deb/porelo_*.deb || failed "installing porelo"
                echo "STEP: installing porelo-host"
                logged apt-get install -y -qq --no-install-recommends /deb/porelo-host_*.deb || failed "installing porelo-host"
                echo "STEP: installing the agent"
                logged apt-get install -y -qq --no-install-recommends /agent/porelo-agent-stub_*.deb && echo AGENT-OK \
                    || failed "installing the agent"
                """
                + APT_CACHE_OUT
                + COMMON
                + HOST;
    }

    /**
     * dnf's metadata and packages kept in the cache directory itself, which dnf writes as root. An
     * install that fails online is tried once more from the cache alone, where there is one; and
     * only the packages installed now are kept.
     */
    private static final String DNF_CACHE =
            """
            cache=%s
            cached=""
            if [ -d "$cache" ]; then
                mkdir -p "$cache/dnf"
                cached="--setopt=cachedir=$cache/dnf --setopt=keepcache=True"
            fi
            dnf_install() {
                dnf install -y -q $cached "$@" >/tmp/step.log 2>&1 && return 0
                [ -n "$cached" ] && [ -n "$(ls -A "$cache/dnf" 2>/dev/null)" ] || return 1
                echo "CACHED: dnf could not install online; installing from the metadata and packages of the last install that worked"
                dnf install -y -q -C $cached "$@" >/tmp/step.log 2>&1
            }
            dnf_keep() {
                [ -n "$cached" ] || return 0
                rpm -qa --qf '%%{NAME}-%%{VERSION}-%%{RELEASE}.%%{ARCH}.rpm\n' > /tmp/installed.list
                find "$cache/dnf" -name '*.rpm' | while IFS= read -r kept; do
                    grep -qxF "${kept##*/}" /tmp/installed.list || rm -f "$kept"
                done
            }
            """
                    .formatted(CACHE);

    /**
     * The install on Fedora's side.
     *
     * @return the script
     */
    String fedora() {
        return FAILED
                + """
                """
                + DNF_CACHE
                + """
                echo "STEP: installing porelo"
                dnf_install /rpm/porelo-0*.rpm || failed "installing porelo"
                echo "STEP: installing the agent"
                dnf_install /agent/porelo-agent-stub-*.rpm && echo AGENT-OK \
                    || failed "installing the agent"
                echo "STEP: keeping the fetched packages"
                dnf_keep
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
        checked(label, "", runIn(image, script, slug(label, ""), report), version, report);
    }

    /**
     * The name of an image's cache: its label, and the mirror an Ubuntu attempt takes, since the
     * lists apt keeps are named after the mirror they came from.
     */
    static String slug(final String label, final String mirror) {
        final String host = mirror.isEmpty() ? "" : "-" + URI.create(mirror).getHost();
        return (label + host).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9.]+", "-");
    }

    /** Mounts an image's own cache directory at {@link #CACHE}, made when it is missing. */
    private List<String> cacheMount(final String slug, final Report report) {
        if (cache == null) {
            return List.of();
        }
        final Path own = cache.resolve(slug);
        try {
            java.nio.file.Files.createDirectories(own);
        } catch (java.io.IOException ex) {
            report.info(
                    "the cache "
                            + own
                            + " cannot be made, so this check runs without it: "
                            + ex.getMessage());
            return List.of();
        }
        return List.of("-v", own + ":" + CACHE + ":Z");
    }

    private Commands.Result runIn(String image, String script, String slug, Report report) {
        final String name = "porelo-package-check-" + UUID.randomUUID();
        final Duration limit =
                image.equals(Main.UBUNTU)
                        ? UBUNTU_LIMIT
                        : image.equals(Main.DEBIAN) ? DEBIAN_LIMIT : Commands.LIMIT;
        final List<String> command =
                new java.util.ArrayList<>(
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
                                agentDeb.getParent() + ":/agent:ro,Z"));
        command.addAll(cacheMount(slug, report));
        command.addAll(List.of(image, "sh", "-c", script));
        final Commands.Result result = run.apply(List.copyOf(command), limit);
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
        // Passed from what the cache kept: said, so a mirror that keeps failing is seen.
        lines.stream().filter(line -> line.startsWith(CACHED)).forEach(report::info);
        final Map<String, String> expected = new LinkedHashMap<>(MARKERS);
        if (label.startsWith("Debian") || label.startsWith("Ubuntu")) {
            expected.putAll(HOST_MARKERS);
        }
        for (final Map.Entry<String, String> marker : expected.entrySet()) {
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
