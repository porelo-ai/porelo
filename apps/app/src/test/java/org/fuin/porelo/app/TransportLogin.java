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
        context.getStore(HERE).put("login", System.getProperty("user.name", ""));
        System.setProperty("user.name", LOGIN);
    }

    @Override
    public void afterEach(final ExtensionContext context) {
        System.setProperty("user.name", context.getStore(HERE).get("login", String.class));
    }
}
