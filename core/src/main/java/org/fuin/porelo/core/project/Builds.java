package org.fuin.porelo.core.project;

import java.net.URI;

/**
 * How an {@code online} project's tasks learn what the build of their own push did: which forge
 * runs it, and the vault entry that reads it there.
 *
 * <p>Only {@code online}: there a task pushes to the upstream and a build starts within seconds. In
 * {@code guarded} the forge builds nothing until a person approves the work at the gate, so
 * watching a build is waiting on a person; in {@code offline} there is no build at all.
 *
 * @param forge Which forge, by the name an installed build reader declares; Porelo names none
 *     itself.
 * @param credential The vault entry holding the forge token. It stays on the host and never enters
 *     a task.
 * @param api The forge API's address; "" for the forge's own public one.
 * @param logs Which jobs' logs reach the task: {@link #FAILURE}, each failed one, or {@link #ALL},
 *     every one once the verdict is final.
 */
public record Builds(String forge, String credential, String api, String logs) {

    /**
     * The log of each failed job: what a task needs to fix its push, at the fewest questions to the
     * forge.
     */
    public static final String FAILURE = "failure";

    /** The log of every job, the successful ones too. */
    public static final String ALL = "all";

    /**
     * Constructor with checks.
     *
     * @param forge Which forge.
     * @param credential The vault entry.
     * @param api The API's address, or "".
     * @param logs Which logs.
     */
    public Builds {
        if (!forge.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new ProjectException(
                    "'builds.forge' must name a build reader, such as the one its package"
                            + " installs, not '"
                            + forge
                            + "'");
        }
        if (credential.isBlank()) {
            throw new ProjectException(
                    "'builds.credential' must name the vault entry that reads the builds");
        }
        if (!FAILURE.equals(logs) && !ALL.equals(logs)) {
            throw new ProjectException(
                    "'builds.logs' is '" + logs + "'; it is '" + FAILURE + "' or '" + ALL + "'");
        }
        if (!api.isEmpty() && !permittedApi(api)) {
            throw new ProjectException(
                    "'builds.api' must be an https:// address, or http:// on this machine's"
                            + " loopback (127.0.0.1, ::1, localhost), not '"
                            + api
                            + "'");
        }
    }

    private static boolean permittedApi(final String api) {
        final URI uri;
        try {
            uri = URI.create(api);
        } catch (final IllegalArgumentException ex) {
            return false;
        }
        // A host is required for https too: "https:forge.example" names none to trust.
        return ("https".equals(uri.getScheme()) && uri.getHost() != null)
                || ("http".equals(uri.getScheme())
                        && uri.getUserInfo() == null
                        && ("127.0.0.1".equals(uri.getHost())
                                || "[::1]".equals(uri.getHost())
                                || "localhost".equalsIgnoreCase(uri.getHost())));
    }
}
