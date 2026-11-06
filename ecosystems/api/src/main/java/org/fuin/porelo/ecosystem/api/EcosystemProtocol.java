package org.fuin.porelo.ecosystem.api;

import java.util.List;

/**
 * The contract between Porelo and a build-tool plugin.
 *
 * <p>Porelo and the plugins are packaged, versioned and installed separately, so a machine will one
 * day hold a combination nobody tested. A plugin states which version of the contract it was built
 * against, and Porelo refuses one older than it needs rather than misread its answers.
 *
 * <p><strong>{@code Ecosystem1} only grows.</strong> A method or an optional field may be added,
 * and neither side has to release for it: a plugin built against an older contract goes on working,
 * a method it lacks answers {@code org.varlink.service.MethodNotFound}, and Porelo says of that
 * "this plugin cannot say", never "nothing found". A renamed, removed or reinterpreted method or
 * field raises {@link #LOWEST}.
 */
public final class EcosystemProtocol {

    /** Varlink interface both sides implement. */
    public static final String INTERFACE = "org.fuin.porelo.Ecosystem1";

    /**
     * Version of this contract: what a plugin built against it reports in {@code Describe}.
     *
     * <p>Raised with every addition, so that a plugin says how much of the contract it knows.
     */
    public static final int VERSION = 2;

    /**
     * The lowest version this Porelo needs.
     *
     * <p>A plugin at or above it is accepted, a newer one included, since Porelo calls only methods
     * it knows. Raised only when a method or field is renamed, removed or changes its meaning.
     */
    public static final int LOWEST = 1;

    /** Returns the ecosystem a plugin serves and the protocol version it speaks. */
    public static final String DESCRIBE = INTERFACE + ".Describe";

    /** Returns what a plugin recognizes of its tool in a directory. */
    public static final String DETECT = INTERFACE + ".Detect";

    /** Returns the settings files a plugin's tool needs in a task. */
    public static final String CONFIGURE = INTERFACE + ".Configure";

    /**
     * Looks up what a plugin's own {@code Detect} read: the one method that may open a connection,
     * asked only when a project file is made.
     */
    public static final String RESOLVE = INTERFACE + ".Resolve";

    /**
     * How long one request of a plugin's {@code Resolve} may take, which the plugin keeps itself.
     * An unmeasured first figure.
     */
    public static final long RESOLVE_REQUEST_SECONDS = 5;

    /** How long Porelo waits for a whole {@code Resolve}. An unmeasured first figure. */
    public static final long RESOLVE_CALL_SECONDS = 20;

    /**
     * The variables a plugin is started with for {@code Resolve}, and for nothing else, where the
     * person has them set: a proxy is how a machine reaches the vendors at all.
     */
    public static final List<String> PROXY_VARIABLES =
            List.of(
                    "HTTP_PROXY",
                    "HTTPS_PROXY",
                    "NO_PROXY",
                    "http_proxy",
                    "https_proxy",
                    "no_proxy");

    private EcosystemProtocol() {
        throw new UnsupportedOperationException("Constants");
    }

    /**
     * Returns a refusal's fully qualified error name.
     *
     * @param reason One of {@link EcosystemRefused.Reason}.
     * @return The name on the wire.
     */
    static String error(final EcosystemRefused.Reason reason) {
        return INTERFACE + "." + reason.wire();
    }
}
