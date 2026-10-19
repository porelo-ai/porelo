package org.fuin.porelo.packagecheck;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Checks the built {@code .deb} and {@code .rpm} against each other and against a real install.
 *
 * <p>Run after {@code -Pdist verify}, from the tree's root, with {@code ./mvnw -pl
 * dist/package-check compile exec:java@package-check}. Needs {@code dpkg-deb}, {@code dpkg} and
 * podman, and pulls the ubuntu and fedora images the pom pins.
 */
public final class Main {

    private static final String BILL_DIR = "/usr/share/porelo/sbom/";

    /** Where both packages put the setup script, and where this tree keeps it. */
    static final String SETUP_SCRIPT = "/usr/share/porelo/setup/porelo-setup.sh";

    static final String SETUP_SOURCE = "dist/dist-setup/porelo-setup.sh";

    /** What porelo's bill must name: the modules the package is built from, and what they link. */
    static final Set<String> SHIPS = Set.of("porelo-app", "porelod", "porelo-core", "picocli");

    /** What it must not: the acceptance kit's and the release tooling's, which never ship. */
    static final Set<String> NEVER =
            Set.of(
                    "cucumber-core",
                    "junit-platform-engine",
                    "sshj",
                    "porelo-acceptance-kit",
                    "porelo-machines",
                    "porelo-release");

    /** The image the .rpm is read and installed in, by its registry and its digest. */
    static final String FEDORA = image("fedora");

    /** The image the .deb is installed in, by its registry and its digest. */
    static final String UBUNTU = image("ubuntu");

    /** The oldest Debian supported, with the lowest C library the packages may need. */
    static final String DEBIAN = image("debian");

    /** The oldest Fedora supported. */
    static final String FEDORA_OLDEST = image("fedora-oldest");

    private Main() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads one pinned image, as the build filtered it from the pom.
     *
     * @param name {@code ubuntu}, {@code debian}, {@code fedora} or {@code fedora-oldest}
     * @return the image, with its registry and its digest
     */
    private static String image(String name) {
        final Properties images = new Properties();
        try (InputStream in = Main.class.getResourceAsStream("images.properties")) {
            if (in == null) {
                throw new IllegalStateException("images.properties is not on the classpath");
            }
            images.load(in);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        final String image = images.getProperty(name, "");
        if (image.isBlank() || image.contains("${")) {
            throw new IllegalStateException(
                    "the " + name + " image is '" + image + "' - was images.properties filtered?");
        }
        return image;
    }

    /** Where the build writes the {@code .deb}: under the module that makes it, in this tree. */
    static final String DEB = "dist/dist-deb/target";

    /** Where the build writes the {@code .rpm}. */
    static final String RPM = "dist/dist-rpm/target";

    /** Where the stub agent's binary and both its packages are written. */
    static final String AGENT = "agents/stub/target";

    /** The native binary the packages carry. */
    static final String BINARY = "apps/app/target/porelo";

    /** Modules that must not leave a package: only agents are packaged. */
    static final List<String> STRAYS = List.of("agents/target", "agents/api/target");

    /**
     * Runs every check.
     *
     * <p>Throws rather than exiting: this runs inside Maven's own JVM, and an exit would end Maven.
     *
     * @param args the repository root, or nothing for the current directory
     */
    public static void main(String[] args) {
        final Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        final int code = run(root, System.getenv("GITHUB_RUN_ID") != null, System.out);
        if (code != 0) {
            throw new IllegalStateException(
                    code == 2 ? "no packages to check" : "the packages failed their check");
        }
    }

    /**
     * Runs every check against the packages under one repository.
     *
     * @param root the repository root
     * @param inCi whether this runs in CI
     * @param out where the report goes
     * @return the exit code
     */
    static int run(Path root, boolean inCi, PrintStream out) {
        out.println("== packages ==");
        final Path deb = only(root.resolve(DEB), "porelo_*.deb", out);
        final Path rpm = only(root.resolve(RPM), "porelo-[0-9]*.rpm", out);
        // The stub: the real agents live in their own repositories, and the packaging is the same.
        final Path agentDeb = only(root.resolve(AGENT), "porelo-agent-stub_*.deb", out);
        final Path agentRpm = only(root.resolve(AGENT), "porelo-agent-stub-*.rpm", out);
        if (deb == null || rpm == null || agentDeb == null || agentRpm == null) {
            out.println("run: JAVA_HOME=<graalvm> ./mvnw -Pnative,dist clean verify -DskipTests");
            out.println("note the phase: 'package' builds the binaries and no packages at all");
            return 2;
        }
        final Report report = new Report(out);

        report.section("freshness");
        final Map<Path, Path> carried = new LinkedHashMap<>();
        carried.put(deb, root.resolve(BINARY));
        carried.put(rpm, root.resolve(BINARY));
        carried.put(agentDeb, root.resolve(AGENT).resolve("porelo-agent-stub"));
        carried.put(agentRpm, root.resolve(AGENT).resolve("porelo-agent-stub"));
        Build.freshness(carried, inCi, report);

        report.section("only agents are packaged");
        Build.strays(STRAYS.stream().map(root::resolve).toList(), report);

        report.section("deb and rpm agree");
        Parity.check(debFiles(deb), rpmFiles(rpm), report);

        // Registered as a vendor's agent is: under a name of its own, by one description, which the
        // installs below find.
        report.section("the agent is packaged as a vendor's would be");
        AgentLayout.check("the agent deb", debFiles(agentDeb), report);
        AgentLayout.check("the agent rpm", rpmFiles(agentRpm), report);

        report.section("bills of materials");
        final String poreloVersion = debField(deb, "Version");
        final String poreloBill = Bill.mavenVersion(poreloVersion);
        final String agentBill = Bill.mavenVersion(debField(agentDeb, "Version"));
        new Bill("the porelo deb", "porelo-dist-deb", poreloBill, SHIPS, NEVER)
                .check(debFile(deb, billPath("porelo")), billPath("porelo"), report);
        new Bill("the porelo rpm", "porelo-dist-deb", poreloBill, SHIPS, NEVER)
                .check(rpmFile(rpm, billPath("porelo")), billPath("porelo"), report);
        final String stub = "porelo-agent-stub";
        new Bill("the agent deb", stub, agentBill, Set.of(), Set.of())
                .check(debFile(agentDeb, billPath(stub)), billPath(stub), report);
        new Bill("the agent rpm", stub, agentBill, Set.of(), Set.of())
                .check(rpmFile(agentRpm, billPath(stub)), billPath(stub), report);

        // A host gets the script through the package, checked by apt; it must be the one this tree
        // keeps, not an older copy left in a build directory.
        report.section("the setup script");
        final String source = read(root.resolve(SETUP_SOURCE));
        sameScript("the deb", debFile(deb, SETUP_SCRIPT), source, report);
        sameScript("the rpm", rpmFile(rpm, SETUP_SCRIPT), source, report);

        report.section("versions");
        final String rpmVersion =
                inFedora(rpm, "rpm", "-qp", "--qf", "%{VERSION}", "/pkg/" + rpm.getFileName())
                        .output()
                        .strip();
        report.check(
                poreloVersion.equals(rpmVersion),
                "both packages are version " + poreloVersion,
                "version disagreement: deb says " + poreloVersion + ", rpm says " + rpmVersion);
        VersionOrder.check(poreloVersion, inCi, Main::dpkgGreater, report);

        final Install install = new Install(deb, rpm, agentDeb);
        install.check("Ubuntu 26.04", UBUNTU, install.ubuntu(), poreloVersion, report);
        install.check("Debian 13", DEBIAN, install.debian(), poreloVersion, report);
        install.check("Fedora", FEDORA, install.fedora(), rpmVersion, report);
        install.check("Fedora 43", FEDORA_OLDEST, install.fedora(), rpmVersion, report);

        out.println();
        out.println(
                report.failures() == 0
                        ? "== all checks passed =="
                        : "== " + report.failures() + " check(s) failed ==");
        return report.failures() == 0 ? 0 : 1;
    }

    private static String billPath(String name) {
        return BILL_DIR + name + ".cdx.json";
    }

    /**
     * Finds the one package matching a pattern.
     *
     * <p>More than one is refused rather than picked from: {@code target} keeps every package a
     * build without {@code clean} made, and a check of the wrong one passes on a binary nobody
     * meant to test.
     */
    private static @Nullable Path only(Path directory, String glob, PrintStream out) {
        final List<Path> found = new ArrayList<>();
        if (Files.isDirectory(directory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, glob)) {
                stream.forEach(found::add);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        if (found.size() == 1) {
            return found.getFirst();
        }
        out.println(
                found.isEmpty()
                        ? "no " + glob + " under " + directory
                        : found.size()
                                + " packages match "
                                + glob
                                + " under "
                                + directory
                                + " - which one was just built cannot be told");
        return null;
    }

    private static List<String> debFiles(Path deb) {
        final Commands.Result listing = Commands.run(List.of("dpkg-deb", "-c", deb.toString()));
        if (!listing.ok()) {
            throw new IllegalStateException("dpkg-deb could not list " + deb);
        }
        return listing.output()
                .lines()
                .filter(line -> !line.startsWith("d"))
                .map(line -> line.split("\\s+"))
                .filter(fields -> fields.length > 5)
                .map(fields -> fields[5].substring(1))
                .sorted()
                .toList();
    }

    private static List<String> rpmFiles(Path rpm) {
        final Commands.Result listing = inFedora(rpm, "rpm", "-qlp", "/pkg/" + rpm.getFileName());
        if (!listing.ok()) {
            throw new IllegalStateException("rpm could not list " + rpm + ": " + listing.output());
        }
        return listing.output().lines().filter(line -> !line.isBlank()).sorted().toList();
    }

    private static String debField(Path deb, String field) {
        return Commands.run(List.of("dpkg-deb", "-f", deb.toString(), field)).output().strip();
    }

    static void sameScript(String label, @Nullable String packaged, String source, Report report) {
        report.check(
                source.equals(packaged),
                label + " holds " + SETUP_SCRIPT + " as " + SETUP_SOURCE + " is",
                packaged == null
                        ? label + " holds no " + SETUP_SCRIPT
                        : label + "'s " + SETUP_SCRIPT + " is not " + SETUP_SOURCE);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static @Nullable String debFile(Path deb, String path) {
        final Commands.Result file =
                Commands.pipe(
                        List.of(
                                List.of("dpkg-deb", "--fsys-tarfile", deb.toString()),
                                List.of("tar", "-xO", "." + path)),
                        false);
        return file.ok() ? file.output() : null;
    }

    private static @Nullable String rpmFile(Path rpm, String path) {
        // rpm2archive, not rpm2cpio: the fedora image has no cpio, and that pipe read as a missing
        // file.
        final Commands.Result file =
                inFedora(
                        rpm,
                        "sh",
                        "-c",
                        "rpm2archive -n - < '/pkg/"
                                + rpm.getFileName()
                                + "' | tar -xO '."
                                + path
                                + "'");
        return file.ok() ? file.output() : null;
    }

    private static Commands.Result inFedora(Path rpm, String... command) {
        final List<String> line =
                new ArrayList<>(
                        List.of(
                                "podman",
                                "run",
                                "--rm",
                                "-v",
                                rpm.getParent() + ":/pkg:ro,Z",
                                FEDORA));
        line.addAll(Arrays.asList(command));
        return Commands.run(line);
    }

    private static boolean dpkgGreater(String higher, String lower) {
        return Commands.run(List.of("dpkg", "--compare-versions", higher, "gt", lower)).ok();
    }
}
