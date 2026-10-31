package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards against interpreting retired settings or malformed identities as access grants. */
class AccessPersonsSchemaTest {
    private static final String PERSON =
            "persons: {alice: {fingerprint: \"SHA256:"
                    + "A".repeat(43)
                    + "\", role: admin, hosts: [example-host]}}";

    @Test
    void personsNeedNoVpnOrLaptop() {
        assertThatCode(() -> AccessFile.read(new StringReader(PERSON), "access.yml"))
                .as("a person is pinned by a card's key")
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "_alice",
                "alice_name",
                "Alice",
                "1alice",
                "alice/other",
                "porelo",
                "porelo-admin",
                "abcdefghijklmnopqrstuvwxyzabcdefg"
            })
    void aLoginMustBeOneACardCanCarry(final String login) {
        assertThatThrownBy(
                        () ->
                                AccessFile.read(
                                        new StringReader(PERSON.replace("alice:", login + ":")),
                                        "access.yml"))
                .as("an access name cannot escape the card path or take a reserved account")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("persons." + login);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[]",
                "null",
                "text",
                "[Walk6m]",
                "[bad.host]",
                "[-host]",
                "[host-]",
                "[true]"
            })
    void aPersonNeedsAtLeastOneValidHost(final String hosts) {
        assertThatThrownBy(
                        () ->
                                AccessFile.read(
                                        new StringReader(PERSON.replace("[example-host]", hosts)),
                                        "access.yml"))
                .as("a malformed host cannot name an account target")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("persons.alice.hosts");
    }

    @ParameterizedTest
    @ValueSource(strings = {"'true'", "null", "1", "[]"})
    void aLockIsABoolean(final String locked) {
        assertThatThrownBy(
                        () ->
                                AccessFile.read(
                                        new StringReader(
                                                PERSON.replace(
                                                        "role: admin",
                                                        "locked: " + locked + ", role: admin")),
                                        "access.yml"))
                .as("an ambiguous lock cannot be treated as unlocked")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("persons.alice.locked");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHA256:short", "MD5:1234", "42"})
    void aPinMustBeASha256Fingerprint(final String pin) {
        assertThatThrownBy(
                        () ->
                                AccessFile.read(
                                        new StringReader(
                                                PERSON.replace("SHA256:" + "A".repeat(43), pin)),
                                        "access.yml"))
                .as("a malformed pin cannot grant an identity")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("persons.alice.fingerprint");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[42]", "[SHA256:short]"})
    void aRevocationListMustHoldFingerprints(final String revoked) {
        assertThatThrownBy(
                        () ->
                                AccessFile.read(
                                        new StringReader("revoked: " + revoked), "access.yml"))
                .as("a malformed revocation list cannot be skipped")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("revoked")
                .hasMessageContaining("fingerprints");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "developers",
                "laptops",
                "wireguard_public_key",
                "endpoint",
                "network",
                "ca_sha256",
                "vpn_address"
            })
    void retiredKeysAreRefusedEvenInsideTransportSettings(final String key) {
        assertThatThrownBy(
                        () ->
                                AccessFile.read(
                                        new StringReader(
                                                "transports: {room: {nested: [{"
                                                        + key
                                                        + ": value}]}}"),
                                        "access.yml"))
                .as("retired access settings cannot be hidden in nested maps")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining(key);
    }
}
