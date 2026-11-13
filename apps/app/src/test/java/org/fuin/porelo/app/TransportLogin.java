package org.fuin.porelo.app;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Runs each test of a class under a login a transport takes, whoever runs the suite, and puts the
 * real one back after it.
 *
 * <p>A transport takes a login of lowercase letters, digits and '-', and Porelo refuses any other
 * before a transport runs. A test that took the login of whoever ran it failed under {@code Alex}
 * or {@code runner_1}, which are logins a person or a CI runner may well have.
 */
final class TransportLogin implements BeforeEachCallback, AfterEachCallback {

    /** The login each test runs under: one every transport takes. */
    static final String LOGIN = "porelo-test";

    private static final ExtensionContext.Namespace HERE =
            ExtensionContext.Namespace.create(TransportLogin.class);

    @Override
    public void beforeEach(final ExtensionContext context) {
        final String login = System.getProperty("user.name");
        if (login != null) {
            context.getStore(HERE).put("login", login);
        }
        System.setProperty("user.name", LOGIN);
    }

    @Override
    public void afterEach(final ExtensionContext context) {
        // A login that was not set is cleared again, not left as an empty one.
        final String login = context.getStore(HERE).remove("login", String.class);
        if (login == null) {
            System.clearProperty("user.name");
        } else {
            System.setProperty("user.name", login);
        }
    }
}
