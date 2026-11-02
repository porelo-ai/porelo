package org.fuin.porelo.organization.gittree;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.vault.HostCard;
import org.jspecify.annotations.Nullable;

/** A host card's services as an answer names them. */
public final class TreeServices {

    private TreeServices() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a card's services: one taken off is {@code null}, never a port nobody listens on.
     *
     * @param card The card.
     * @return {@code forge} and {@code matrix}.
     */
    public static Map<String, @Nullable Object> of(final Organization.Card card) {
        final Map<String, @Nullable Object> services = new LinkedHashMap<>();
        services.put(
                "forge",
                card.forgeWebPort() == HostCard.NO_SERVICE
                        ? null
                        : Map.of("web_port", card.forgeWebPort(), "git_port", card.forgeGitPort()));
        services.put(
                "matrix",
                card.matrixPort() == HostCard.NO_SERVICE
                        ? null
                        : Map.of("port", card.matrixPort()));
        return services;
    }
}
