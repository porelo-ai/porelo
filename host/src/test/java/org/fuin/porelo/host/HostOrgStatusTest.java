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
                .as(
                        "an unfollowed host answers every field in its fixed order, absent records as null")
                .isEqualTo(new Answer(0, EMPTY, ""));
    }

    @Test
    void theHumanFormNamesTheHostStateAndCounts() {
        assertThat(fixture.run(Map.of(), "org", "status"))
                .as("the short human form is stable and readable")
                .isEqualTo(new Answer(0, "workshop: no organization; 0 persons; 0 projects\n", ""));
    }

    /** What a root login over ssh carries beside what sudo and root's units carry. */
    private static final Map<String, String> ROOT_LOGIN =
            Map.of(
                    "DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/0/bus",
                    "SSH_CONNECTION", "192.0.2.1 50000 192.0.2.2 22",
                    "SSH_TTY", "/dev/pts/0",
                    "XDG_SESSION_ID", "7",
                    "SHLVL", "1",
                    "_", "/usr/bin/porelo-host");

    @Test
    void aRootLoginReadsTheStatusWhateverItsSessionCarries() {
        // An ssh login as root was refused for DBUS_SESSION_BUS_ADDRESS (2026-11-14), and sudo is
        // refused by the verb itself: no interactive caller could read the status.
        assertThat(fixture.run(ROOT_LOGIN, "org", "status", "--json"))
                .isEqualTo(new Answer(0, EMPTY, ""));
        assertThat(fixture.run(ROOT_LOGIN, "org", "status").code()).isZero();
    }

    @Test
    void aRootLoginsSessionStillRefusesEveryOtherVerb() {
        final Answer follow = fixture.run(ROOT_LOGIN, "org", "follow");

        assertThat(follow.code()).isEqualTo(78);
        assertThat(follow.err()).contains("refusing environment variable");
    }

    @Test
    void aStatusAskedThroughSudoWithASessionIsStillRefusedForTheEnvironment() {
        final Map<String, String> sudo = new java.util.HashMap<>(ROOT_LOGIN);
        sudo.put("SUDO_USER", "alice");

        final Answer answer = fixture.run(sudo, "org", "status", "--json");

        assertThat(answer.code())
                .as("refused for the environment, before the verb's own sudo refusal")
                .isEqualTo(78);
        assertThat(answer.err()).contains("refusing environment variable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--json extra", "--help", "--json=x", "-h"})
    void onlyTheStatusAsItIsWrittenPassesTheCheck(String more) {
        final java.util.List<String> args =
                new java.util.ArrayList<>(java.util.List.of("org", "status"));
        args.addAll(java.util.List.of(more.split(" ")));

        final Answer answer = fixture.run(ROOT_LOGIN, args.toArray(String[]::new));

        assertThat(answer.code()).isEqualTo(78);
        assertThat(answer.err()).contains("refusing environment variable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUDO_UID", "SUDO_TTY"})
    void anySudoVariableKeepsTheCheck(String name) {
        final Map<String, String> sudo = new java.util.HashMap<>(ROOT_LOGIN);
        sudo.put(name, "1000");

        assertThat(fixture.run(sudo, "org", "status", "--json").code()).isEqualTo(78);
    }

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
                                "porelo-host: org status is root's alone and never runs through sudo\n"));
    }

    /** What a person's own ssh login carries: no sudo, a session of their own. */
    private static final Map<String, String> PERSON_LOGIN =
            Map.of(
                    "DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus",
                    "SSH_CONNECTION", "192.0.2.1 50000 192.0.2.2 22",
                    "XDG_SESSION_ID", "8",
                    "XDG_RUNTIME_DIR", "/run/user/1000",
                    "SHLVL", "1");

    private static final PoreloHost.Uid PERSON = () -> 1000;
    private static final java.time.Clock FIVE_MINUTES_LATER =
            java.time.Clock.fixed(
                    java.time.Instant.parse("2026-11-14T10:05:00Z"), java.time.ZoneOffset.UTC);
    private static final String VIEW =
            "{\"host\":\"workshop\",\"organization\":\"following\",\"commit\":\""
                    + COMMIT
                    + "\",\"followed\":\"2026-11-14T10:00:00Z\",\"persons\":[{\"login\":\"alice\",\"state\":\"active\",\"host_admin\":true,\"projects\":[]}],\"projects\":[]}";

    private Path view(String text) throws Exception {
        final Path view = fixture.put(HostStatusView.PATH, text);
        Files.setPosixFilePermissions(
                view, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        return view;
    }

    private java.util.List<Path> tree() throws Exception {
        try (var paths = Files.walk(directory)) {
            return paths.sorted().toList();
        }
    }

    private Answer asPerson(Map<String, String> environment, String... args) {
        return fixture.run(PERSON, FIVE_MINUTES_LATER, environment, args);
    }

    @Test
    void aHostAdminReadsThePublicViewAsThemselves() throws Exception {
        // Was exit 77: the root gate refused every caller without root (2026-11-14).
        view(VIEW + "\n");
        final var before = tree();

        final Answer json = asPerson(PERSON_LOGIN, "org", "status", "--json");
        final Answer human = asPerson(PERSON_LOGIN, "org", "status");

        assertThat(json)
                .as("the follow's public view, as written, with its age")
                .isEqualTo(
                        new Answer(
                                0,
                                VIEW.substring(0, VIEW.length() - 1) + ",\"age_seconds\":300}\n",
                                ""));
        assertThat(human)
                .as("root's line, and when the follow that wrote it ran")
                .isEqualTo(
                        new Answer(
                                0,
                                "workshop: following; 1 persons; 0 projects; followed"
                                        + " 2026-11-14T10:00:00Z, 5 minutes ago\n",
                                ""));
        assertThat(tree()).as("a caller without root writes nothing").isEqualTo(before);
    }

    @Test
    void aMissingViewSaysNotFollowedYet() {
        assertThat(asPerson(PERSON_LOGIN, "org", "status"))
                .as("no follow wrote the view yet, and nothing more is said")
                .isEqualTo(new Answer(0, "not followed yet\n", ""));
        assertThat(asPerson(PERSON_LOGIN, "org", "status", "--json"))
                .as("the JSON form says the same as an absent follow")
                .isEqualTo(new Answer(0, "{\"followed\":null}\n", ""));
    }

    @Test
    void rootStillReadsItsWholeStatusBesideTheView() throws Exception {
        view(VIEW + "\n");
        assertThat(status())
                .as("root's answer is its own status, never the public view")
                .isEqualTo(new Answer(0, EMPTY, ""));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"org follow", "org refresh", "org status --help", "org status --json extra"})
    void aCallerWithoutRootIsStillRefusedEveryOtherCall(String call) throws Exception {
        view(VIEW + "\n");
        final var before = tree();

        final Answer answer = asPerson(Map.of(), call.split(" "));

        assertThat(answer.code()).as("only the status read gets past the root gate").isEqualTo(77);
        assertThat(answer.err()).contains("root uid required");
        assertThat(answer.out()).as("the view is not shown to another call").isEmpty();
        assertThat(tree()).as("a refused caller writes nothing").isEqualTo(before);
    }

    @Test
    void aStatusThroughSudoWithoutRootIsRefused() throws Exception {
        view(VIEW + "\n");
        final Answer answer = asPerson(SUDO, "org", "status", "--json");

        assertThat(answer.code()).as("sudo to another account is no way to the view").isEqualTo(77);
        assertThat(answer.out()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0100644, 0100660, 0100600})
    void aViewOfAnotherModeIsRefused(int mode) throws Exception {
        final Path view = view(VIEW + "\n");
        fixture.overrides.put(view, Map.of("mode", mode));

        final Answer answer = asPerson(PERSON_LOGIN, "org", "status");

        assertThat(answer)
                .as("only root's 0640 view is trusted")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: " + view + " is not a plain file of root's alone\n"));
    }

    @Test
    void aViewOwnedByAnotherAccountIsRefused() throws Exception {
        final Path view = view(VIEW + "\n");
        fixture.overrides.put(view, Map.of("uid", 1000));

        assertThat(asPerson(PERSON_LOGIN, "org", "status", "--json"))
                .as("a planted view is never shown")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: " + view + " is not a plain file of root's alone\n"));
    }

    @Test
    void aPersonOutsideTheAdminsGroupCannotReadTheView() throws Exception {
        final Path view = view(VIEW + "\n");
        Files.setPosixFilePermissions(view, java.util.Set.of());
        org.junit.jupiter.api.Assumptions.assumeFalse(
                Files.isReadable(view), "the test account reads any file");
        fixture.overrides.put(view, Map.of("mode", 0100640));

        final Answer answer = asPerson(PERSON_LOGIN, "org", "status");

        assertThat(answer.code()).as("not readable is no permission").isEqualTo(77);
        assertThat(answer.err())
                .as("the refusal names who reads the view")
                .contains(HostStatusView.ADMINS)
                .hasLineCount(1);
        assertThat(answer.out()).isEmpty();
    }

    @Test
    void aMalformedViewIsRefused() throws Exception {
        final Path view = view("{\"host\":\"workshop\",\"followed\":\"yesterday\"}\n");

        assertThat(asPerson(PERSON_LOGIN, "org", "status"))
                .isEqualTo(
                        new Answer(
                                65, "", "porelo-host: " + view + " holds no valid host status\n"));
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
                        "root's whole status: one state per person by precedence, sorted, purge seven days after the lock")
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
                                "porelo-host: " + file + " is not a plain file of root's alone\n"));
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
                                "porelo-host: " + file + " is not a plain file of root's alone\n"));
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
                                "porelo-host: "
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
                                "porelo-host: " + conf + " is not a plain file of root's alone\n"));
    }
}
