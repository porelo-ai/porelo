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

    /** The accounts the filter was installed into in this run. */
    private static final Set<String> INSTALLED = new HashSet<>();

    /** Why an account's install failed, so every later scenario of it fails for the same reason. */
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
        install(Accounts.forThisThread());
    }

    private static synchronized void install(String account) throws IOException {
        if (INSTALLED.contains(account)) {
            return;
        }
        final String failed = FAILED.get(account);
        if (failed != null) {
            throw new AssertionError(failed);
        }
        final Source source;
        try {
            source = source(pin(), System.getenv());
        } catch (AssertionError ex) {
            FAILED.put(account, Objects.requireNonNullElse(ex.getMessage(), FILTER));
            throw ex;
        }
        final Ssh.Output output = Machine.shared().run(command(source));
        if (output.status() != 0) {
            final String failure = failure(source, account, output.all().strip());
            FAILED.put(account, failure);
            throw new AssertionError(failure);
        }
        INSTALLED.add(account);
    }

    /**
     * Reads the pin the kit carries.
     *
     * @return The pin's properties, empty when the kit carries none.
     */
    static Properties pin() {
        final Properties pin = new Properties();
        try (InputStream in = MessageFilter.class.getClassLoader().getResourceAsStream(PIN)) {
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
        final String path = environment.getOrDefault(OVERRIDE, "").strip();
        final String pathSha256 = environment.getOrDefault(OVERRIDE_SHA256, "").strip();
        if (!path.isEmpty() || !pathSha256.isEmpty()) {
            if (!path.startsWith("/") || !SHA256.matcher(pathSha256).matches()) {
                throw new AssertionError(
                        "The message filter "
                                + FILTER
                                + " cannot be installed: set both "
                                + OVERRIDE
                                + " (an absolute path on the machine) and "
                                + OVERRIDE_SHA256
                                + " (its sha256, 64 lower-case hex digits), or neither.");
            }
            return new Override(path, pathSha256);
        }
        return new Pinned(pinned(pin, "deb"), pinned(pin, "rpm"));
    }

    /** Reads one format's pin, refusing one that names no https address and sha256. */
    private static Package pinned(Properties pin, String format) {
        final String url = pin.getProperty(format + ".url", "").strip();
        final String sha256 = pin.getProperty(format + ".sha256", "").strip();
        if (!url.startsWith("https://") || !SHA256.matcher(sha256).matches()) {
            throw new AssertionError(
                    "The message filter "
                            + FILTER
                            + " has no pin: "
                            + PIN
                            + " names no https "
                            + format
                            + ".url and "
                            + format
                            + ".sha256 for its package, and "
                            + OVERRIDE
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
                                    + PIN
                                    + "; a snapshot can leave the pool, then update the pin there"
                                    + " to the packages' newer addresses and digests";
                    case Override local ->
                            OVERRIDE + " " + local.path() + " with sha256 " + local.sha256();
                };
        return "The message filter "
                + FILTER
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
        final String inPackage = "\"$work/root/usr/libexec/porelo/" + FILTER + "\"";
        final String start =
                "fail() { printf '%s: %s\\n' '"
                        + FILTER
                        + "' \"$1\" >&2; exit 1; };"
                        + " umask 077; dir=\"${XDG_DATA_HOME:-$HOME/.local/share}/porelo/filter\";"
                        + " target=\"$dir/"
                        + FILTER
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
                                    + " ] || fail 'the package holds no usr/libexec/porelo/"
                                    + FILTER
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
                + FILTER
                + "' \"$target\"";
    }
}
