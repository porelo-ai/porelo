package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The login a transport is given. Harm guarded: a transport suffixes every account it makes with
 * it, so a login its own rule refuses, or one that differs from another only in case, would make
 * accounts nobody can tell apart or none at all.
 */
class TransportLoginTest {

    @ParameterizedTest
    @ValueSource(strings = {"core", "a", "build-2", "a2345678901234567890123456789012"})
    void takesALoginAsEveryOtherLoginRuleOfPoreloDoes(final String login) throws Exception {
        assertThat(TransportLifecycle.login(login)).as(login).isEqualTo(login);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Core",
                "1core",
                "core_2",
                "-core",
                "co.re",
                "",
                "a23456789012345678901234567890123"
            })
    void refusesAnyOtherLoginWithTheRefusalsExitCode(final String login) {
        assertThatThrownBy(() -> TransportLifecycle.login(login))
                .as(login)
                .isInstanceOfSatisfying(
                        TransportLifecycle.Refused.class,
                        refused ->
                                assertThat(refused.code())
                                        .as("the exit code a refused login has")
                                        .isEqualTo(78));
    }
}
