package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AppArmorProfiles}: what the deb carries of the two AppArmor profiles and the
 * scripts that load and unload them, held against this tree.
 */
class AppArmorProfilesTest {

    /** The repository's root, from this module. */
    private static final Path ROOT = Path.of("..", "..");

    private static final Path CONTROL = ROOT.resolve("dist/dist-deb/src/deb/control");

    private final Reports out = new Reports();

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** The two profiles as this tree keeps them, by the path the deb installs each at. */
    private static Map<String, String> trees() throws IOException {
        final Map<String, String> files = new LinkedHashMap<>();
        for (final String name : AppArmorProfiles.NAMES) {
            files.put(name, read(ROOT.resolve(AppArmorProfiles.TREE).resolve(name)));
        }
        return files;
    }

    @Test
    void findsACommentOverEveryRuleOfThisTreesProfiles() throws IOException {
        for (final Map.Entry<String, String> profile : trees().entrySet()) {
            assertThat(AppArmorProfiles.uncommented(profile.getValue()))
                    .as(profile.getKey())
                    .isEmpty();
        }
    }

    @Test
    void namesARuleNoCommentStandsOver() {
        final String profile =
                """
                # Why the profile exists.
                profile x flags=(unconfined) {

                  # What this is for.
                  userns,
                  signal,

                  capability,
                }
                """;

        assertThat(AppArmorProfiles.uncommented(profile)).containsExactly("capability,");
    }

    @Test
    void takesNoCommentAcrossABlankLine() {

        // A comment belongs to the lines under it, up to the next blank one.
        assertThat(AppArmorProfiles.uncommented("# A heading.\n\nnetwork,\n"))
                .containsExactly("network,");
    }

    @Test
    void passesTheDebHoldingThisTreesProfiles() throws IOException {
        AppArmorProfiles.profiles(packaged(trees()), ROOT, out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
        assertThat(out.text()).contains("/etc/apparmor.d/porelo-task");
    }

    @Test
    void failsAProfileTheDebLacksOrHoldsOlder() throws IOException {
        final Map<String, String> files = packaged(trees());
        files.remove("/etc/apparmor.d/porelo");
        files.put("/etc/apparmor.d/porelo-task", "profile porelo-task {\n  unix,\n}\n");

        AppArmorProfiles.profiles(files, ROOT, out.report);

        assertThat(out.text())
                .contains("FAIL  the deb holds no /etc/apparmor.d/porelo")
                .contains("FAIL  the deb's /etc/apparmor.d/porelo-task is not apparmor/porelo-task")
                .contains("FAIL  /etc/apparmor.d/porelo-task has a rule with no comment: unix,");
    }

    @Test
    void passesThisTreesMaintainerScripts() throws IOException {
        AppArmorProfiles.scripts(
                read(CONTROL.resolve("postinst")),
                read(CONTROL.resolve("postrm")),
                "/etc/apparmor.d/porelo\n/etc/apparmor.d/porelo-task\n",
                out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
    }

    @Test
    void failsScriptsThatNeitherLoadNorUnloadAndProfilesThatAreNoConffiles() {
        AppArmorProfiles.scripts("#!/bin/sh\nexit 0\n", null, "", out.report);

        assertThat(out.text())
                .contains("FAIL  the deb's postinst loads no AppArmor profile")
                .contains("FAIL  the deb has no postrm")
                .contains("FAIL  /etc/apparmor.d/porelo is no conffile of the deb")
                .contains("FAIL  /etc/apparmor.d/porelo-task is no conffile of the deb");
    }

    @Test
    void failsAPostrmThatLeavesTheTaskProfileLoaded() {
        AppArmorProfiles.scripts(
                "apparmor_parser -r -W /etc/apparmor.d/porelo /etc/apparmor.d/porelo-task",
                "for profile in porelo; do printf '%s' \"$profile\" >"
                        + " /sys/kernel/security/apparmor/.remove; done",
                "/etc/apparmor.d/porelo\n/etc/apparmor.d/porelo-task\n",
                out.report);

        assertThat(out.text()).contains("FAIL  the deb's postrm does not unload porelo-task");
    }

    private static Map<String, String> packaged(Map<String, String> tree) {
        final Map<String, String> files = new LinkedHashMap<>();
        tree.forEach((name, text) -> files.put(AppArmorProfiles.INSTALLED + name, text));
        return files;
    }
}
