package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void holdsThisTreesTaskProfileToAbiFive() throws IOException {
        assertThat(trees().get("porelo-task").lines().map(String::strip))
                .as("the task's abstract socket peer rules use ABI 5.0")
                .contains("abi <abi/5.0>,");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abi <abi/4.0>,", "", "# abi <abi/5.0>,"})
    void failsATaskProfileWithoutAbiFive(String abi) throws IOException {
        AppArmorProfiles.task(taskReplacing("abi <abi/5.0>,", abi), out.report);

        assertThat(out.text())
                .as("a missing or older ABI must not silently drop the socket lock")
                .contains("FAIL  porelo-task needs abi <abi/5.0>,");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "# unix (connect) peer=(label=porelo),",
                "unix (connect),",
                "unix (connect) peer=(label=unconfined),",
                "unix (connect) peer=(label=porelo*),"
            })
    void failsAMissingOrChangedPoreloPeer(String rule) throws IOException {
        AppArmorProfiles.task(
                taskReplacing("unix (connect) peer=(label=porelo),", rule), out.report);

        assertThat(out.text())
                .as("the broker's peer must be exactly porelo")
                .contains("FAIL  porelo-task socket rules must be exactly ");
    }

    @Test
    void failsAMissingTaskPeer() throws IOException {
        AppArmorProfiles.task(
                taskReplacing("unix (connect) peer=(label=porelo-task),", ""), out.report);

        assertThat(out.text())
                .as("the task must still reach its own sockets")
                .contains("FAIL  porelo-task socket rules must be exactly ");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "network,",
                "network unix,",
                "unix,",
                "unix (connect),",
                "audit unix (connect) peer=(label=unconfined),",
                "signal, unix (connect) peer=(label=unconfined),",
                "unix (connect) peer=(label=porelo//&unconfined),",
                "all,"
            })
    void failsAnAdditionalSocketGrant(String rule) throws IOException {
        AppArmorProfiles.task(taskReplacing("signal,", rule), out.report);

        assertThat(out.text())
                .as("another grant must not bypass the two allowed peers: %s", rule)
                .contains("FAIL  porelo-task socket rules must be exactly ");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "change_profile -> porelo,",
                "/** px,",
                "/** cx,",
                "/** rPx,",
                "/** rCx,",
                "/** pxr,",
                "/** cxrw,",
                "/** pix,",
                "/** cix,",
                "/** ux,",
                "/** rmPUx,"
            })
    void failsATaskProfileThatCanLeaveItsLabel(String rule) throws IOException {
        final String before = rule.startsWith("/") ? "/{,**} rwlkmix," : "signal,";
        AppArmorProfiles.task(taskReplacing(before, rule), out.report);

        assertThat(out.text())
                .as("exec and explicit profile changes must keep the task's label: %s", rule)
                .contains("FAIL  porelo-task allows a profile transition: ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unconfined", "complain"})
    void failsATaskProfileThatDoesNotEnforceItsRules(String flag) throws IOException {
        AppArmorProfiles.task(
                taskReplacing("attach_disconnected, mediate_deleted", flag), out.report);

        assertThat(out.text())
                .as("the peer rules require an enforcing task profile")
                .contains("FAIL  porelo-task declaration must be ");
    }

    private static String taskReplacing(String before, String after) throws IOException {
        final String task = trees().get("porelo-task");
        assertThat(task).as("the mutation must change an existing rule").contains(before);
        return task.replace(before, after);
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
                .contains("FAIL  /etc/apparmor.d/porelo-task has a rule with no comment: unix,")
                .contains("FAIL  porelo-task needs abi <abi/5.0>,")
                .contains("FAIL  porelo-task socket rules must be exactly ");
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
