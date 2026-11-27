package org.fuin.porelo.acceptance;

import io.cucumber.java.Before;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.machines.Ssh;

/**
 * Installs the message filter of porelo-message-sluice into each account of the run, before its
 * first scenario.
 *
 * <p><strong>Without it a task's outbox never empties.</strong> Porelo sends nothing a filter has
 * not passed, so a run without one fails the first scenario that talks, as something else. So the
 * run stops at its start instead, naming the filter.
 *
 * <p><strong>Per account, never system-wide.</strong> The filter goes where Porelo looks first,
 * {@code $XDG_DATA_HOME/porelo/filter/porelo-message-sluice-filter}, executable by the account
 * alone; the machine's own setup is left as it is.
 *
 * <p><strong>Pinned in one place.</strong> The packages' https addresses and sha256 are in the
 * kit's {@code message-filter.properties}: a .deb where {@code dpkg-deb} is, an .rpm where {@code
 * rpm2cpio} and {@code cpio} are. The digest is checked before the package is unpacked, and a
 * package that differs leaves nothing executable. {@code PORELO_ACCEPTANCE_FILTER} and {@code
 * PORELO_ACCEPTANCE_FILTER_SHA256} name an executable on the machine and its digest instead, for a
 * filter not yet published.
 */
public final class MessageFilter {

    /** The filter's file name, as Porelo looks for it. */
    static final String FILTER = "porelo-message-sluice-filter";

    /** The resource the pin is written in, the one place. */
    static final String PIN = "org/fuin/porelo/acceptance/message-filter.properties";

    /** The environment variable naming an executable on the machine to install instead. */
    static final String OVERRIDE = "PORELO_ACCEPTANCE_FILTER";

    /** The environment variable holding that executable's sha256. */
    static final String OVERRIDE_SHA256 = "PORELO_ACCEPTANCE_FILTER_SHA256";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * A program installed into each account from a pinned package, the way the filter is.
     *
     * @param label What it is, as a failure names it.
     * @param name Its file name.
     * @param pin The resource its pin is written in.
     * @param override The environment variable naming an executable on the machine instead.
     * @param overrideSha256 The environment variable holding that executable's sha256.
     * @param packaged Where the packages hold it, from their root.
     * @param directory The account's directory it goes into, for a POSIX shell.
     */
    record Tool(
            String label,
            String name,
            String pin,
            String override,
            String overrideSha256,
            String packaged,
            String directory) {}

    /** The message filter, where Porelo looks for it first. */
    static final Tool THE_FILTER =
            new Tool(
                    "The message filter",
                    FILTER,
                    PIN,
                    OVERRIDE,
                    OVERRIDE_SHA256,
                    "usr/libexec/porelo/" + FILTER,
                    "${XDG_DATA_HOME:-$HOME/.local/share}/porelo/filter");

    /** The accounts each tool was installed into in this run, as tool and account. */
    private static final Set<String> INSTALLED = new HashSet<>();

    /** Why a tool's install failed, so every later scenario of the account fails the same way. */
    private static final Map<String, String> FAILED = new HashMap<>();

    /** Where the filter comes from. */
    sealed interface Source permits Pinned, Override {}

    /**
     * One pinned package.
     *
     * @param url Its https address.
     * @param sha256 The package's digest.
     */
    record Package(String url, String sha256) {}

    /**
     * The pinned packages, one build in both formats; the machine's tools choose which one.
     *
     * @param deb The .deb, unpacked with {@code dpkg-deb}.
     * @param rpm The .rpm, unpacked with {@code rpm2cpio} and {@code cpio}.
     */
    record Pinned(Package deb, Package rpm) implements Source {}

    /**
     * An executable already on the machine.
     *
     * @param path Its absolute path there.
     * @param sha256 The executable's digest.
     */
    record Override(String path, String sha256) implements Source {}

    /**
     * Installs the filter into the account this scenario runs as, the first time that account is
     * seen in the run.
     *
     * @throws IOException If the machine cannot be reached.
     * @throws AssertionError If the filter cannot be installed; it names the filter and the pin.
     */
    @Before(order = 0)
    public void installIntoTheAccount() throws IOException {
        install(THE_FILTER, Accounts.forThisThread());
    }

    /**
     * Installs a tool into an account, the first time that account is seen for it in the run.
     *
     * @param tool The tool.
     * @param account The account the scenario runs as.
     * @throws IOException If the machine cannot be reached.
     * @throws AssertionError If the tool cannot be installed; it names the tool and the pin.
     */
    static synchronized void install(Tool tool, String account) throws IOException {
        final String key = tool.name() + " " + account;
        if (INSTALLED.contains(key)) {
            return;
        }
        final String failed = FAILED.get(key);
        if (failed != null) {
            throw new AssertionError(failed);
        }
        final Source source;
        try {
            source = source(tool, pin(tool), System.getenv());
        } catch (AssertionError ex) {
            FAILED.put(key, Objects.requireNonNullElse(ex.getMessage(), tool.name()));
            throw ex;
        }
        final Ssh.Output output = Machine.shared().run(command(tool, source));
        if (output.status() != 0) {
            final String failure = failure(tool, source, account, output.all().strip());
            FAILED.put(key, failure);
            throw new AssertionError(failure);
        }
        INSTALLED.add(key);
    }

    /**
     * Reads the pin the kit carries.
     *
     * @return The pin's properties, empty when the kit carries none.
     */
    static Properties pin() {
        return pin(THE_FILTER);
    }

    /**
     * Reads the pin the kit carries for a tool.
     *
     * @param tool The tool.
     * @return The pin's properties, empty when the kit carries none.
     */
    static Properties pin(Tool tool) {
        final Properties pin = new Properties();
        try (InputStream in =
                MessageFilter.class.getClassLoader().getResourceAsStream(tool.pin())) {
            if (in != null) {
                pin.load(in);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return pin;
    }

    /**
     * Decides where the filter comes from: the override when it is set, the pin otherwise.
     *
     * @param pin The pin's properties.
     * @param environment The run's environment.
     * @return The source.
     * @throws AssertionError If neither names a filter, or one names it only in part.
     */
    static Source source(Properties pin, Map<String, String> environment) {
        return source(THE_FILTER, pin, environment);
    }

    /**
     * Decides where a tool comes from: the override when it is set, the pin otherwise.
     *
     * @param tool The tool.
     * @param pin The pin's properties.
     * @param environment The run's environment.
     * @return The source.
     * @throws AssertionError If neither names the tool, or one names it only in part.
     */
    static Source source(Tool tool, Properties pin, Map<String, String> environment) {
        final String path = environment.getOrDefault(tool.override(), "").strip();
        final String pathSha256 = environment.getOrDefault(tool.overrideSha256(), "").strip();
        if (!path.isEmpty() || !pathSha256.isEmpty()) {
            if (!path.startsWith("/") || !SHA256.matcher(pathSha256).matches()) {
                throw new AssertionError(
                        tool.label()
                                + " "
                                + tool.name()
                                + " cannot be installed: set both "
                                + tool.override()
                                + " (an absolute path on the machine) and "
                                + tool.overrideSha256()
                                + " (its sha256, 64 lower-case hex digits), or neither.");
            }
            return new Override(path, pathSha256);
        }
        return new Pinned(pinned(tool, pin, "deb"), pinned(tool, pin, "rpm"));
    }

    /** Reads one format's pin, refusing one that names no https address and sha256. */
    private static Package pinned(Tool tool, Properties pin, String format) {
        final String url = pin.getProperty(format + ".url", "").strip();
        final String sha256 = pin.getProperty(format + ".sha256", "").strip();
        if (!url.startsWith("https://") || !SHA256.matcher(sha256).matches()) {
            throw new AssertionError(
                    tool.label()
                            + " "
                            + tool.name()
                            + " has no pin: "
                            + tool.pin()
                            + " names no https "
                            + format
                            + ".url and "
                            + format
                            + ".sha256 for its package, and "
                            + tool.override()
                            + " is not set.");
        }
        return new Package(url, sha256);
    }

    /**
     * Says why the install failed, with the pin, so a snapshot that left the pool reads as such.
     *
     * @param source Where the filter was to come from.
     * @param account The account it was to be installed into.
     * @param output What the install wrote.
     * @return The failure's text.
     */
    static String failure(Source source, String account, String output) {
        return failure(THE_FILTER, source, account, output);
    }

    /**
     * Says why a tool's install failed, with the pin, so a snapshot that left the pool reads as
     * such.
     *
     * @param tool The tool.
     * @param source Where the tool was to come from.
     * @param account The account it was to be installed into.
     * @param output What the install wrote.
     * @return The failure's text.
     */
    static String failure(Tool tool, Source source, String account, String output) {
        final String from =
                switch (source) {
                    case Pinned pinned ->
                            "the pin "
                                    + pinned.deb().url()
                                    + " with sha256 "
                                    + pinned.deb().sha256()
                                    + " or "
                                    + pinned.rpm().url()
                                    + " with sha256 "
                                    + pinned.rpm().sha256()
                                    + " in "
                                    + tool.pin()
                                    + "; a snapshot can leave the pool, then update the pin there"
                                    + " to the packages' newer addresses and digests";
                    case Override local ->
                            tool.override() + " " + local.path() + " with sha256 " + local.sha256();
                };
        return tool.label()
                + " "
                + tool.name()
                + " could not be installed into account "
                + account
                + " from "
                + from
                + ": "
                + output;
    }

    /**
     * Builds the shell command that installs the filter into the account it runs as.
     *
     * <p>Everything happens in a private directory beside the target, and only a checked file is
     * made executable and moved into place; an older filter is removed first, so a failure leaves
     * none.
     *
     * @param source Where the filter comes from.
     * @return The command, for a POSIX shell.
     */
    static String command(Source source) {
        return command(THE_FILTER, source);
    }

    /**
     * Builds the shell command that installs a tool into the account it runs as.
     *
     * @param tool The tool.
     * @param source Where it comes from.
     * @return The command, for a POSIX shell.
     */
    static String command(Tool tool, Source source) {
        final String inPackage = "\"$work/root/" + tool.packaged() + "\"";
        final String start =
                "fail() { printf '%s: %s\\n' '"
                        + tool.name()
                        + "' \"$1\" >&2; exit 1; };"
                        + " umask 077; dir=\""
                        + tool.directory()
                        + "\";"
                        + " target=\"$dir/"
                        + tool.name()
                        + "\"; mkdir -p \"$dir\" || fail \"cannot create $dir\";"
                        + " rm -f \"$target\" || fail \"cannot remove the older $target\";"
                        + " work=$(mktemp -d \"$dir/.install.XXXXXX\") || fail 'no work directory';"
                        + " trap 'rm -rf \"$work\"' EXIT;"
                        + " digest() { sha256sum \"$1\" | cut -d' ' -f1; }; ";
        final String fetch =
                switch (source) {
                    case Pinned pinned ->
                            "if command -v dpkg-deb >/dev/null 2>&1; then format=deb; url="
                                    + Shell.quote(pinned.deb().url())
                                    + "; sha256="
                                    + Shell.quote(pinned.deb().sha256())
                                    + "; elif command -v rpm2cpio >/dev/null 2>&1"
                                    + " && command -v cpio >/dev/null 2>&1; then format=rpm; url="
                                    + Shell.quote(pinned.rpm().url())
                                    + "; sha256="
                                    + Shell.quote(pinned.rpm().sha256())
                                    + "; else fail 'neither dpkg-deb nor rpm2cpio with cpio is"
                                    + " installed on this machine, and the pinned packages are a .deb"
                                    + " and an .rpm unpacked with them'; fi;"
                                    + " curl -fsSL --proto '=https' --tlsv1.2 -o \"$work/package\""
                                    + " \"$url\" || fail \"the download of $url failed\";"
                                    + " [ \"$(digest \"$work/package\")\" = \"$sha256\" ] || fail"
                                    + " \"the $format package's sha256 differs from the pin; nothing"
                                    + " was unpacked\";"
                                    + " mkdir \"$work/root\" || fail 'no directory to unpack into';"
                                    + " if [ \"$format\" = deb ]; then dpkg-deb -x \"$work/package\""
                                    + " \"$work/root\"; else (cd \"$work/root\" && rpm2cpio"
                                    + " \"$work/package\" | cpio -idm --quiet); fi"
                                    + " || fail \"the $format package could not be unpacked\";"
                                    + " [ -f "
                                    + inPackage
                                    + " ] || fail 'the package holds no "
                                    + tool.packaged()
                                    + "'; cp "
                                    + inPackage
                                    + " \"$work/filter\" || fail 'cannot copy it out of the package';"
                                    + " chmod 0600 \"$work/filter\"; ";
                    case Override local ->
                            "cp "
                                    + Shell.quote(local.path())
                                    + " \"$work/filter\" || fail "
                                    + Shell.quote("cannot read " + local.path())
                                    + "; chmod 0600 \"$work/filter\";"
                                    + " [ \"$(digest \"$work/filter\")\" = "
                                    + Shell.quote(local.sha256())
                                    + " ] || fail "
                                    + Shell.quote(
                                            "the sha256 of "
                                                    + local.path()
                                                    + " differs from "
                                                    + OVERRIDE_SHA256)
                                    + "; ";
                };
        return start
                + fetch
                + "chmod 0700 \"$work/filter\" && mv -f \"$work/filter\" \"$target\""
                + " || fail \"cannot place $target\"; printf '%s installed at %s\\n' '"
                + tool.name()
                + "' \"$target\"";
    }
}
