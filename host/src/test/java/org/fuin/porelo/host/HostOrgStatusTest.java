package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.host.HostReadFixture.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostOrgStatusTest {
    private static final String ORG = "/etc/porelo-host/organization/";
    private static final String COMMIT = "a".repeat(40);
    private static final String EMPTY =
            "{\"host\":\"workshop\",\"organization\":null,\"refused\":null,\"commit\":null,\"pinned\":null,\"persons\":[],\"projects\":[]}\n";
    @TempDir Path directory;
    private HostReadFixture fixture;

    @BeforeEach
    void prepareTheHost() throws Exception {
        fixture = new HostReadFixture(directory);
    }

    private Answer status() {
        return fixture.run(Map.of(), "org", "status", "--json");
    }

    private void applied(String persons, String projects, String extra) throws Exception {
        fixture.put(
                ORG + "applied.json",
                "{\"commit\":\""
                        + COMMIT
                        + "\",\"persons\":"
                        + persons
                        + ",\"projects\":"
                        + projects
                        + ",\"revoked\":[]"
                        + extra
                        + "}");
    }

    @Test
    void anUnfollowedHostAnswersTheExactEmptyStatus() {
        assertThat(status())
                .as("absent optional records retain Python's nulls and field order")
                .isEqualTo(new Answer(0, EMPTY, ""));
    }

    @Test
    void theHumanFormNamesTheHostStateAndCounts() {
        assertThat(fixture.run(Map.of(), "org", "status"))
                .as("the short human form is stable and readable")
                .isEqualTo(new Answer(0, "workshop: no organization; 0 persons; 0 projects\n", ""));
    }

    // py: test_org_status_is_roots_alone
    @ParameterizedTest
    @ValueSource(strings = {"alice", ""})
    void sudoCannotReadRootOrganizationStatus(String sudoUser) throws Exception {
        Files.delete(fixture.path("/etc/porelo-host/forge.conf"));
        assertThat(fixture.run(Map.of("SUDO_USER", sudoUser), "org", "status", "--json"))
                .as("even an empty but set SUDO_USER refuses before any host read")
                .isEqualTo(
                        new Answer(
                                65,
                                "",
                                "porelo-host-person: org status is root's alone and never runs through sudo\n"));
    }

    @Test
    void peopleStatesMembershipsAndPurgeDatesAreSortedAndComplete() throws Exception {
        fixture.person("zoe", "member=zulu developer\n", "lock=pending\n");
        fixture.person(
                "alice",
                "member=host-admin\nmember=alpha project-admin\nmember=alpha developer\n",
                "");
        fixture.put(
                "/etc/porelo-host/people/bob.conf",
                "locked=true\nssh="
                        + KEY
                        + "\nexpires=2030-01-02\nreason=membership\nlocked_on=2028-02-25\n");
        fixture.put("/etc/porelo-host/people/purged", "carol\nalice\n");
        fixture.put(ORG + "pin", "alice " + KEY + "\nbob " + KEY + "\n");
        fixture.put(ORG + "read/organization.added", "");
        fixture.put(ORG + "refused.json", "{\"words\":\"refused-state: changed pin\"}");
        applied(
                "[{\"login\":\"dave\"},{\"login\":\"alice\"}]",
                "[{\"project\":\"zulu\"},{\"project\":\"alpha\",\"since\":\"yesterday\"}]",
                "");
        final String expected =
                "{\"host\":\"workshop\",\"organization\":\"following\",\"refused\":\"refused-state: changed pin\",\"commit\":\""
                        + COMMIT
                        + "\",\"pinned\":\""
                        + PINNED
                        + "\",\"persons\":["
                        + "{\"login\":\"alice\",\"state\":\"active\",\"host_admin\":true,\"projects\":[{\"project\":\"alpha\",\"role\":\"project-admin\"},{\"project\":\"alpha\",\"role\":\"developer\"}]},"
                        + "{\"login\":\"bob\",\"state\":\"locked\",\"host_admin\":false,\"projects\":[],\"purge_on\":\"2028-03-03\"},"
                        + "{\"login\":\"carol\",\"state\":\"purged\",\"host_admin\":false,\"projects\":[]},"
                        + "{\"login\":\"dave\",\"state\":\"named\",\"host_admin\":false,\"projects\":[]},"
                        + "{\"login\":\"zoe\",\"state\":\"pending\",\"host_admin\":false,\"projects\":[{\"project\":\"zulu\",\"role\":\"developer\"}]}],"
                        + "\"projects\":[{\"project\":\"alpha\",\"state\":\"carried\",\"why\":null,\"since\":\"yesterday\",\"repositories\":[]},"
                        + "{\"project\":\"zulu\",\"state\":\"carried\",\"why\":null,\"since\":null,\"repositories\":[]}]}\n";
        assertThat(status())
                .as(
                        "root's entire status preserves Python's precedence, sorting and seven-day purge arithmetic")
                .isEqualTo(new Answer(0, expected, ""));
    }

    @Test
    void aPinWithoutTheReadKeyMarkerWaitsForTheToken() throws Exception {
        fixture.put(ORG + "pin", "alice " + KEY + "\n");
        assertThat(status())
                .as("a pin alone does not claim the host follows the organization")
                .isEqualTo(
                        new Answer(
                                0,
                                EMPTY.replace(
                                                "\"organization\":null",
                                                "\"organization\":\"waiting-for-token\"")
                                        .replace(
                                                "\"pinned\":null", "\"pinned\":\"" + PINNED + "\""),
                                ""));
    }

    @Test
    void aLegacyPinReportsItsFingerprintWithoutClaimingToFollow() throws Exception {
        fixture.put(ORG + "pin.conf", "admin=alice\nkey=" + KEY + "\n");
        assertThat(status())
                .as("only the new pin path establishes an organization state")
                .isEqualTo(
                        new Answer(
                                0,
                                EMPTY.replace("\"pinned\":null", "\"pinned\":\"" + PINNED + "\""),
                                ""));
    }

    @Test
    void everyRepositoryStateUsesTheSignedSelectionAndTheLastCentralMeasurement() throws Exception {
        final String progress =
                """
                {"zulu":{"state":"pending","why":"room deletion","since":"then"},
                 "alpha":{"state":"carried","since":"now","named":["ahead","same","moved","unknown","local","waiting"],
                   "migrated":true,"taken":["git@example.org:acme/Ahead.git","git@example.org:acme/Same.git","git@example.org:acme/Moved.git","git@example.org:acme/Unknown.git"],
                   "repositories_why":{"held":"work remains","waiting":"waits for a central token"},
                   "file":{"address":"https://example.org/acme/Alpha.git","repositories":{
                     "waiting":{"central":"https://example.org/acme/Waiting.git"},
                     "self":{"central":"https://example.org/acme/Alpha.git"},
                     "local":{"central":null},"excluded":{"central":null},
                     "held":{"central":"git@example.org:acme/Held.git"},
                     "ahead":{"central":"git@example.org:acme/Ahead.git"},
                     "same":{"central":"git@example.org:acme/Same.git"},
                     "moved":{"central":"git@example.org:acme/Moved.git"},
                     "unknown":{"central":"git@example.org:acme/Unknown.git"}}}}}
                """;
        fixture.put(ORG + "host-projects.json", progress);
        fixture.put(
                ORG + "central/alpha.json",
                """
                {"project":"alpha","repositories":[{"repository":"Ahead","state":"ahead","commits":2},
                 {"repository":"Same","state":"up-to-date"},{"repository":"Moved","state":"moved-on"}]}
                """);
        applied(
                "[]",
                "[{\"project\":\"beta\"}]",
                ",\"project_refusals\":{\"alpha\":\"project refusal\"}");
        final String expectedProjects =
                """
                [{"project":"alpha","state":"refused","why":"project refusal","since":"now","repositories":[
                {"repository":"ahead","state":"taken","why":null,"central":{"state":"ahead","commits":2}},
                {"repository":"excluded","state":"not-carried","why":null},
                {"repository":"held","state":"held","why":"work remains"},
                {"repository":"local","state":"no-central","why":"no central address, so this host cannot take it; name one in the project file for a task here"},
                {"repository":"moved","state":"taken","why":null,"central":{"state":"moved-on"}},
                {"repository":"same","state":"taken","why":null,"central":{"state":"up-to-date"}},
                {"repository":"self","state":"taken","why":null,"central":{"state":"cannot-say","why":"unreachable"}},
                {"repository":"unknown","state":"taken","why":null,"central":{"state":"cannot-say","why":"unreachable"}},
                {"repository":"waiting","state":"waiting","why":"waits for a central token"}]},
                {"project":"beta","state":"carried","why":null,"since":null,"repositories":[]},
                {"project":"zulu","state":"pending","why":"room deletion","since":"then","repositories":[]}]
                """
                        .replace("\n", "");
        final String expected =
                EMPTY.replace("\"commit\":null", "\"commit\":\"" + COMMIT + "\"")
                        .replace("\"projects\":[]", "\"projects\":" + expectedProjects);
        assertThat(status())
                .as(
                        "the host reports selections, interruptions, refusals and central work without a network lookup")
                .isEqualTo(new Answer(0, expected, ""));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{",
                "{}",
                "{\"project\":\"other\",\"repositories\":[]}",
                "{\"project\":\"alpha\",\"repositories\":[{\"repository\":\"Code\",\"state\":\"ahead\",\"commits\":0}]}",
                "{\"project\":\"alpha\",\"repositories\":[{\"repository\":\"Code\",\"state\":\"ahead\",\"commits\":2.0}]}",
                "{\"project\":\"alpha\",\"repositories\":[{\"repository\":\"Code\",\"state\":\"ahead\",\"commits\":2e0}]}",
                "{\"project\":\"alpha\",\"repositories\":[{\"repository\":\"Code\",\"state\":\"ahead\",\"commits\":true}]}",
                "{\"project\":\"alpha\",\"repositories\":[{\"repository\":\"Code\",\"state\":\"cannot-say\",\"why\":null}]}",
                "{\"project\":\"alpha\",\"repositories\":[{\"repository\":\"Code\",\"state\":\"up-to-date\",\"why\":null}]}"
            })
    void aMalformedCentralMeasurementCannotVouchForTheRepository(String central) throws Exception {
        fixture.put(
                ORG + "host-projects.json",
                "{\"alpha\":{\"state\":\"carried\",\"taken\":[\"git@example.org:acme/Code.git\"],\"file\":{\"repositories\":{\"code\":{\"central\":\"git@example.org:acme/Code.git\"}}}}}");
        fixture.put(ORG + "central/alpha.json", central);
        final String projects =
                "[{\"project\":\"alpha\",\"state\":\"carried\",\"why\":null,\"since\":null,\"repositories\":[{\"repository\":\"code\",\"state\":\"taken\",\"why\":null,\"central\":{\"state\":\"cannot-say\",\"why\":\"unreachable\"}}]}]";
        assertThat(status())
                .as("bad measurements fall back to cannot-say, never to up-to-date")
                .isEqualTo(
                        new Answer(
                                0,
                                EMPTY.replace("\"projects\":[]", "\"projects\":" + projects),
                                ""));
    }

    @Test
    void aGroupWritableConfigurationIsRefused() {
        final Path file = fixture.path("/etc/porelo-host/forge.conf");
        fixture.overrides.put(file, Map.of("mode", 0100660));
        assertThat(status())
                .as("the mutex and settings must belong to root alone")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host-person: "
                                        + file
                                        + " is not a plain file of root's alone\n"));
    }

    @Test
    void aConfigurationOwnedByAnotherAccountIsRefused() {
        final Path file = fixture.path("/etc/porelo-host/forge.conf");
        fixture.overrides.put(file, Map.of("uid", 1000));
        assertThat(status())
                .as("ownership is checked with an injected numeric attribute reader")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host-person: "
                                        + file
                                        + " is not a plain file of root's alone\n"));
    }

    @Test
    void aPeopleDirectoryWritableByOthersIsRefused() throws Exception {
        fixture.person("alice", "member=host-admin\n", "");
        final Path people = fixture.path("/etc/porelo-host/people");
        fixture.overrides.put(people, Map.of("mode", 040777));
        assertThat(status())
                .as("listing a directory requires the same trust boundary as reading its records")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host-person: "
                                        + people
                                        + " is not a directory of root's alone\n"));
    }

    @Test
    void aSymbolicLinkCannotSupplyConfiguration() throws Exception {
        final Path conf = fixture.path("/etc/porelo-host/forge.conf");
        final Path other = fixture.put("/etc/porelo-host/other.conf", "host=other\n");
        Files.delete(conf);
        Files.createSymbolicLink(conf, other);
        assertThat(status())
                .as("a root-owned link is still not a plain file")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host-person: "
                                        + conf
                                        + " is not a plain file of root's alone\n"));
    }
}
