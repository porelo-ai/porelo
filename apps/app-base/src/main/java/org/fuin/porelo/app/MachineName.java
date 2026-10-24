package org.fuin.porelo.app;

/** The machine identity used by transports and cleanup notices. */
public final class MachineName {
    private MachineName() {}

    /**
     * Returns the local host name, or localhost when it cannot be resolved.
     *
     * @return The machine's name.
     */
    public static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException ex) {
            return "localhost";
        }
    }
}
