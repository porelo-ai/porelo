package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.host.HostReadFixture.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class HostReadRefusalsTest {
    private static final String ORG = "/etc/porelo-host/organization/";
    @TempDir Path directory;
    private HostReadFixture fixture;

    @BeforeEach
    void prepareTheHost() throws Exception {
        fixture = new HostReadFixture(directory);
    }

    private void refuses(int code, String words) {
        assertThat(fixture.run(Map.of(), "org", "status", "--json"))
                .as("bad host state refuses org status with the code and the words that name it")
                .isEqualTo(new Answer(code, "", "porelo-host-person: " + words + "\n"));
    }

    @Test
    void aMissingConfigurationNamesPreparationAsTheNextAction() throws Exception {
        final Path conf = fixture.path("/etc/porelo-host/forge.conf");
        Files.delete(conf);
        refuses(69, conf + " is missing; prepare the host first");
    }

    @Test
    void anAmbiguousHostSettingIsRefused() throws Exception {
        fixture.put("/etc/porelo-host/forge.conf", "host=one\nhost=two\n");
        refuses(69, "/etc/porelo-host/forge.conf names no single valid host");
    }

    @Test
    void anInvalidHostNameIsRefused() throws Exception {
        fixture.put("/etc/porelo-host/forge.conf", "host=../other\n");
        refuses(69, "forge.conf names no valid host");
    }

    @Test
    void anUnknownPeopleEntryIsRefused() throws Exception {
        fixture.put("/etc/porelo-host/people/unknown", "");
        refuses(65, "the people directory holds an unknown record");
    }

    static Stream<Arguments> invalidPeople() {
        final String valid = "locked=false\nssh=" + KEY + "\nexpires=\n";
        return Stream.of(
                Arguments.of(
                        "member=alpha owner\n" + valid, "a record holds an invalid membership"),
                Arguments.of(
                        "member=alpha developer\nmember=alpha project-admin\n" + valid,
                        "the record for alice has unordered memberships"),
                Arguments.of(
                        "member=alpha developer\nmember=alpha developer\n" + valid,
                        "the record for alice has unordered memberships"),
                Arguments.of("locked=false\n", "the record for alice is malformed or unsupported"),
                Arguments.of(
                        valid + "reason=unknown\n",
                        "the person record holds an invalid lock reason"),
                Arguments.of(
                        valid + "locked_on=2028-02-30\n",
                        "the person record holds an invalid lock day"),
                Arguments.of(
                        valid.replace(KEY, "ssh-ed25519 bad"),
                        "the record for alice has no valid card key"),
                Arguments.of(
                        valid.replace("expires=", "expires=2030-02-30"), "invalid card expiry"));
    }

    @ParameterizedTest
    @MethodSource("invalidPeople")
    void aNoncanonicalPersonRecordIsRefused(String record, String reason) throws Exception {
        fixture.put("/etc/porelo-host/people/alice.conf", record);
        refuses(65, reason);
    }

    @Test
    void aMalformedPurgedListIsRefused() throws Exception {
        fixture.put("/etc/porelo-host/people/purged", "alice\nalice\n");
        refuses(65, "the purged login list is malformed");
    }

    @Test
    void anInvalidPinIsRefusedEvenIfItsFirstKeyIsValid() throws Exception {
        fixture.put(ORG + "pin", "alice " + KEY + "\nbob bad\n");
        refuses(65, "the organization pin holds no valid host admin keys");
    }

    @Test
    void anInvalidLegacyPinIsRefused() throws Exception {
        final Path path = fixture.put(ORG + "pin.conf", "admin=alice\nkey=bad\n");
        refuses(65, path + " holds no valid pinned host admin");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{",
                "{}",
                "{\"commit\":\"bad\",\"projects\":[],\"persons\":[],\"revoked\":[]}"
            })
    void aMalformedAppliedOrganizationIsRefused(String record) throws Exception {
        final Path path = fixture.put(ORG + "applied.json", record);
        refuses(65, path + " holds no valid applied organization");
    }

    @Test
    void anUnreadableProgressDocumentIsRefused() throws Exception {
        fixture.put(ORG + "host-projects.json", "{");
        refuses(65, "the host project progress does not read");
    }

    @Test
    void anUnknownProgressStateIsRefused() throws Exception {
        fixture.put(ORG + "host-projects.json", "{\"alpha\":{\"state\":\"unknown\"}}");
        refuses(65, "the host project progress is malformed");
    }

    @Test
    void aMissingProgressStateIsRefused() throws Exception {
        fixture.put(ORG + "host-projects.json", "{\"alpha\":{}}");
        refuses(65, "the host project progress is malformed");
    }

    @Test
    void aProjectNameCannotTraverseOutOfTheCentralDirectory() throws Exception {
        fixture.put(ORG + "host-projects.json", "{\"../other\":{\"state\":\"carried\"}}");
        refuses(65, "the host project progress is malformed");
    }

    @Test
    void aGroupWritableCentralMeasurementIsRefusedRatherThanHidden() throws Exception {
        fixture.put(
                ORG + "host-projects.json",
                "{\"alpha\":{\"state\":\"carried\",\"taken\":[\"git@example.org:acme/Code.git\"],\"file\":{\"repositories\":{\"code\":{\"central\":\"git@example.org:acme/Code.git\"}}}}}");
        final Path path = fixture.put(ORG + "central/alpha.json", "{}");
        fixture.overrides.put(path, Map.of("mode", 0100660));
        refuses(69, path + " is not a plain file of root's alone");
    }

    @Test
    void aPeopleDirectoryOwnedByAnotherAccountIsRefused() throws Exception {
        fixture.person("alice", "member=host-admin\n", "");
        final Path path = fixture.path("/etc/porelo-host/people");
        fixture.overrides.put(path, Map.of("uid", 1000));
        refuses(69, path + " is not a directory of root's alone");
    }

    @Test
    void invalidUtf8IsRefusedWithoutDisclosingTheContents() throws Exception {
        final Path path = fixture.path("/etc/porelo-host/forge.conf");
        Files.write(path, new byte[] {(byte) 0xc3, (byte) 0x28});
        refuses(69, "cannot read " + path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{}"})
    void aMalformedFollowRefusalCannotLookLikeNoRefusal(String content) throws Exception {
        fixture.put(ORG + "refused.json", content);
        refuses(73, "cannot read or change the host's files");
    }
}
