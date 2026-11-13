package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Holds that a suite run under a login no transport takes, such as {@code Alex}, still runs the
 * transport tests under one it does: without {@link TransportLogin} this class fails.
 */
@ExtendWith(TransportLogin.class)
class TransportLoginExtensionTest {

    private static String runner;

    @BeforeAll
    static void runByAlex() {
        runner = System.getProperty("user.name");
        System.setProperty("user.name", "Alex");
    }

    @AfterAll
    static void backToTheRunner() {
        if (runner == null) {
            System.clearProperty("user.name");
        } else {
            System.setProperty("user.name", runner);
        }
    }

    @Test
    void aTransportTakesTheLoginATestRunsUnder() throws Exception {
        assertThat(TransportLifecycle.login()).isEqualTo(TransportLogin.LOGIN);
    }
}
