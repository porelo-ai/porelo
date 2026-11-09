package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostPersonTest {
    private static final String ACCOUNT = "alice:x:1000:1000:Alice:/home/alice:/bin/bash\n";
    private static final Map<String, String> SUDO =
            Map.of("SUDO_USER", "alice", "SUDO_UID", "1000");

    private void refuses(Map<String, String> environment, String account, String reason) {
        assertThatThrownBy(() -> HostPerson.from(environment, login -> account))
                .as(reason)
                .isInstanceOfSatisfying(
                        HostRefusal.class,
                        refusal ->
                                assertThat(refusal.exitCode())
                                        .as("an unproven person is EX_NOPERM")
                                        .isEqualTo(77));
    }

    @Test
    void thePersonComesOnlyFromSudoAndTheMatchingAccountRecord() {
        assertThat(
                        HostPerson.from(
                                Map.of(
                                        "SUDO_USER",
                                        "alice",
                                        "SUDO_UID",
                                        "1000",
                                        "USER",
                                        "mallory",
                                        "LOGNAME",
                                        "mallory",
                                        "HOME",
                                        "/untrusted"),
                                login -> {
                                    assertThat(login)
                                            .as("only SUDO_USER selects the account lookup")
                                            .isEqualTo("alice");
                                    return ACCOUNT;
                                }))
                .as("other identity and path variables cannot choose the person")
                .isEqualTo(new HostPerson("alice", 1000));
    }

    @Test
    void aMissingSudoUserIsRefused() {
        // A root unit has no person to act for unless sudo authenticated one.
        refuses(
                Map.of("USER", "alice", "SUDO_UID", "1000"),
                ACCOUNT,
                "USER is not proof of the caller");
    }

    @Test
    void anEmptySudoUserIsRefused() {
        // An empty identity must not turn into a database enumeration.
        refuses(
                Map.of("SUDO_USER", "", "SUDO_UID", "1000"),
                ACCOUNT,
                "an empty caller has no identity");
    }

    @Test
    void rootCannotBeThePerson() {
        // A person verb must not operate on root's account.
        refuses(Map.of("SUDO_USER", "root", "SUDO_UID", "0"), ACCOUNT, "root is never the person");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "-alice",
                "Alice",
                "alice.name",
                "alice/name",
                "alice\n",
                "alice;id",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            })
    void anInvalidLoginIsRefusedBeforeTheDatabaseIsAsked(String login) {
        // Login syntax must not inject getent options or select arbitrary paths.
        assertThatThrownBy(
                        () ->
                                HostPerson.from(
                                        Map.of("SUDO_USER", login, "SUDO_UID", "1000"),
                                        name -> {
                                            throw new AssertionError(
                                                    "invalid logins must never reach getent");
                                        }))
                .as("the login allow-list precedes account lookup")
                .isInstanceOf(HostRefusal.class);
    }

    @Test
    void anUnknownPersonIsRefused() {
        // Sudo metadata alone cannot invent a system account.
        refuses(SUDO, "", "an unknown login is not a person");
    }

    @Test
    void aDisagreeingSudoUidIsRefused() {
        // A forged or stale login-to-uid binding must not select another account's data.
        refuses(
                Map.of("SUDO_USER", "alice", "SUDO_UID", "1001"),
                ACCOUNT,
                "sudo and NSS must agree");
    }

    @Test
    void aMissingSudoUidIsRefused() {
        // The login must be bound to the uid sudo authenticated.
        refuses(Map.of("SUDO_USER", "alice"), ACCOUNT, "a login alone is insufficient");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-1", "+1000", "1000 ", "4294967295", "999999999999999999999"})
    void anInvalidSudoUidIsRefused(String uid) {
        // Ambiguous or overflowing uid text must not identify a different account.
        refuses(
                Map.of("SUDO_USER", "alice", "SUDO_UID", uid),
                ACCOUNT,
                "uids must be valid decimal numbers");
    }

    @Test
    void anAliasForUidZeroIsRefused() {
        // Renaming root must not make it a person account.
        refuses(
                Map.of("SUDO_USER", "alice", "SUDO_UID", "0"),
                "alice:x:0:0::/:/bin/sh",
                "uid zero is never a person");
    }

    @Test
    void aDifferentAccountRecordIsRefused() {
        // A database response must not silently substitute another login.
        refuses(SUDO, "mallory:x:1000:1000::/:/bin/sh", "the returned name must match");
    }

    @Test
    void multipleAccountRecordsAreRefused() {
        // An ambiguous database answer must not choose the first identity.
        refuses(SUDO, ACCOUNT + ACCOUNT, "exactly one account must answer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice:x:1000", "alice:x:invalid:1000::/:/bin/sh"})
    void aMalformedAccountRecordIsRefused(String account) {
        // Malformed records must not supply a guessed identity.
        refuses(SUDO, account, "the account record must prove the uid");
    }

    @Test
    void aFailedDatabaseIsUnavailableWithoutLeakingItsOutput() {
        // A failing dependency is distinct from an unknown account and may carry secrets.
        assertThatThrownBy(
                        () ->
                                HostPerson.from(
                                        SUDO,
                                        login -> {
                                            throw new IOException("private-marker");
                                        }))
                .as("failed NSS is EX_UNAVAILABLE with a safe diagnostic")
                .isInstanceOfSatisfying(
                        HostRefusal.class,
                        refusal ->
                                assertThat(refusal.exitCode())
                                        .as("lookup failure is unavailable")
                                        .isEqualTo(69))
                .hasMessageNotContaining("private-marker");
    }
}
