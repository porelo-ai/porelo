package org.fuin.porelo.app;

/**
 * Runs {@code porelo vault relay} alone, in a process of its own, for a test that starts it where a
 * task's start would: the command line's own entry opens the account's paths, which a test's
 * process is refused.
 */
final class RelayMain {

    private RelayMain() {}

    /**
     * Runs the relay.
     *
     * @param args What {@code porelo vault relay} takes.
     */
    public static void main(final String[] args) {
        System.exit(new picocli.CommandLine(new VaultRelayCommand()).execute(args));
    }
}
