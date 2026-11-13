package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.fuin.porelo.machines.Ssh;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;
import org.opentest4j.TestAbortedException;

/**
 * What a scenario can say about the package that put Porelo, or an agent, on the machine.
 *
 * <p>These began as shell in each agent repository's {@code acceptance.sh}, three copies differing
 * by a package name. Here once, with the name coming from the scenario.
 */
public class PackageSteps {

    private final World world;

    /**
     * Constructor with the scenario's state.
     *
     * @param world What the scenario holds.
     */
    public PackageSteps(World world) {
        this.world = world;
    }

    /** What {@link #MACHINE_WIDE} prints for a package the package manager calls installed. */
    static final String PACKAGE_INSTALLED = "package installed";

    /**
     * Asks the machine where Porelo comes from: whether its package is installed, and which file a
     * script runs for {@code porelo} and {@code porelod}, with every link resolved.
     */
    static final String MACHINE_WIDE =
            String.join(
                    "\n",
                    "if dpkg-query -W -f='${Status}' porelo 2>/dev/null | grep -q 'ok installed'"
                            + " || rpm -q porelo >/dev/null 2>&1;"
                            + " then echo '"
                            + PACKAGE_INSTALLED
                            + "'; else echo 'package absent'; fi",
                    "for b in porelo porelod; do"
                            + " p=$(command -v \"$b\") && p=$(readlink -f \"$p\") || p=none;"
                            + " echo \"$b $p\"; done");

    /**
     * Skips a scenario on a machine that does not run AppArmor.
     *
     * <p>There the task's profile has nothing to hold, and SELinux or nothing holds the lock
     * instead: a scenario about the profile has nothing to prove, which is not the same as having
     * proved it.
     *
     * @throws IOException If the machine cannot be asked.
     */
    @Given("the machine runs AppArmor")
    public void theMachineRunsAppArmor() throws IOException {
        final Ssh.Output output =
                world.run("cat /sys/module/apparmor/parameters/enabled 2>/dev/null || true");
        if (!output.out().strip().startsWith("Y")) {
            throw new TestAbortedException("Skipped: this machine does not run AppArmor");
        }
    }

    /**
     * Asserts that the Porelo this account runs is the package's, installed for the whole machine:
     * the package is installed, and the {@code porelo} and {@code porelod} every script here runs
     * are its files under {@code /usr/bin}.
     *
     * <p>Only those wear the AppArmor label {@code porelo} by the path they lie at. A copy in the
     * account's own directories shadowing them would make every later step about the account's
     * install while the scenario says machine-wide.
     *
     * @throws IOException If the machine cannot be asked.
     */
    @Given("Porelo is installed machine-wide by its package")
    public void poreloIsInstalledMachineWide() throws IOException {
        final Ssh.Output output = world.run(MACHINE_WIDE);
        world.output(output);
        final String refused = notMachineWide(output.out());
        if (refused != null) {
            throw new AssertionError(refused + ":\n" + output.all().strip());
        }
    }

    /**
     * Says why what {@link #MACHINE_WIDE} printed is not a machine-wide install, or nothing when it
     * is.
     *
     * @param said What the machine answered.
     * @return The reason, or {@code null}.
     */
    static @Nullable String notMachineWide(String said) {
        final List<String> lines = said.lines().map(String::strip).toList();
        if (!lines.contains(PACKAGE_INSTALLED)) {
            return "Porelo's package is not installed on this machine";
        }
        for (final String binary : List.of("porelo", "porelod")) {
            if (!lines.contains(binary + " /usr/bin/" + binary)) {
                return "the " + binary + " a script runs is not the package's /usr/bin/" + binary;
            }
        }
        return null;
    }

    /**
     * Asserts that the installed version is a snapshot the next build supersedes.
     *
     * <p>Asked of dpkg or rpm rather than reasoned about: a flat {@code ~SNAPSHOT} is the same
     * version every build, so {@code apt upgrade} has nothing to do and whoever installed yesterday
     * stays there. The run number is what distinguishes one from the next, and {@code 9 < 10} has
     * to be a numeric comparison or the scheme stops working at the tenth build. A machine with
     * neither tool says so rather than answering.
     *
     * @param pkg The package name.
     * @throws IOException If the machine cannot be asked.
     */
    @Then("the installed package {string} is a snapshot that the next build supersedes")
    public void installedSnapshotOrders(String pkg) throws IOException {
        final String script =
                String.join(
                        "\n",
                        "set -u",
                        "version_lt() {",
                        "  if command -v dpkg >/dev/null 2>&1; then dpkg --compare-versions \"$1\" lt \"$2\";",
                        "  else [ \"$(rpm --eval \"%{lua:print(rpm.vercmp('$1','$2'))}\")\" = \"-1\" ]; fi",
                        "}",
                        "V=\"$(dpkg-query -W -f='${Version}' "
                                + Shell.quote(pkg)
                                + " 2>/dev/null"
                                + " || rpm -q --qf '%{VERSION}' "
                                + Shell.quote(pkg)
                                + " 2>/dev/null)\"",
                        "case \"$V\" in",
                        "  '') echo \"NO VERSION: " + pkg + " reports none\"; exit 1;;",
                        "  *-SNAPSHOT) echo \"WRONG: $V sorts ABOVE the release\"; exit 1;;",
                        "  *~SNAPSHOT) echo \"WRONG: $V never supersedes the last build\"; exit 1;;",
                        "  *~snapshot.*) ;;",
                        "  *) echo \"RELEASE: $V\"; exit 0;;",
                        "esac",
                        "if ! command -v dpkg >/dev/null 2>&1 && ! command -v rpm >/dev/null 2>&1; then",
                        "  echo 'CANNOT CHECK: neither dpkg nor rpm is here'; exit 1; fi",
                        "R=\"${V%%~*}\"; N=\"${V##*~snapshot.}\"",
                        "version_lt \"$V\" \"$R\" && version_lt \"$V\" \"$R~snapshot.$((N + 1))\""
                                + " && version_lt \"$R~snapshot.9\" \"$R~snapshot.10\""
                                + " && echo \"OK: $V\" || { echo \"WRONG: $V does not order\"; exit 1; }");
        final Ssh.Output output = world.run(script);
        assertThat(output.status()).as("%s", output.all().strip()).isZero();
    }

    /**
     * Asserts that the package's bill of materials is there and names a component.
     *
     * <p>Checked where it matters: on a machine that installed the package rather than in the build
     * that made it. An update gate diffs this against the published one, so a package that ships
     * none, or one describing something else, breaks that gate silently rather than loudly.
     *
     * <p>Only the file is read on the machine; it is parsed here. The machine under test needs
     * nothing beyond {@code cat} - it once needed {@code python3}, which none of the packages
     * installs - and a failure names what the bill held.
     *
     * @param path Where the package installs its bill.
     * @param component A component the bill must name.
     * @throws IOException If the machine cannot be asked.
     */
    @Then("the bill at {string} names {string}")
    public void billNames(String path, String component) throws IOException {
        assertThat(components(bill(path))).as("the bill at %s", path).contains(component);
    }

    /**
     * Asserts that the package's bill of materials does not name a component.
     *
     * <p>For what must not ship: a test framework or a build tool in the bill says it was made from
     * the build rather than from the package, and an operator reading it learns nothing true.
     *
     * @param path Where the package installs its bill.
     * @param component A component the bill must not name.
     * @throws IOException If the machine cannot be asked.
     */
    @Then("the bill at {string} does not name {string}")
    public void billDoesNotName(String path, String component) throws IOException {
        assertThat(components(bill(path)))
                .as("the bill at %s", path)
                .isNotEmpty()
                .doesNotContain(component);
    }

    private String bill(String path) throws IOException {
        final Ssh.Output output = world.run("cat -- " + Shell.quote(path));
        assertThat(output.status()).as("reading %s: %s", path, output.err().strip()).isZero();
        return output.out();
    }

    /**
     * Reads the names of every component in a CycloneDX bill, nested ones included.
     *
     * @param bill The bill's JSON.
     * @return The names, sorted, so a failure lists what the bill held.
     * @throws AssertionError If the text is not a CycloneDX bill.
     */
    static Set<String> components(String bill) {
        final Object parsed;
        try {
            parsed = Json.parse(bill);
        } catch (JsonException ex) {
            throw new AssertionError("not JSON: " + ex.getMessage(), ex);
        }
        if (!(parsed instanceof Map<?, ?> bom) || !"CycloneDX".equals(bom.get("bomFormat"))) {
            throw new AssertionError("not a CycloneDX bill");
        }
        final Set<String> names = new TreeSet<>();
        collect(bom.get("components"), names);
        return names;
    }

    private static void collect(@Nullable Object components, Set<String> names) {
        if (components instanceof List<?> list) {
            for (final Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    if (map.get("name") instanceof String name) {
                        names.add(name);
                    }
                    collect(map.get("components"), names);
                }
            }
        }
    }
}
