package org.fuin.porelo.agent.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What an agent says about the providers it can drive.
 *
 * <p>Everything here is a property of the agent, not of whoever serves the models: which wire
 * format it speaks, which provider it uses unless told otherwise, and what shape of endpoint it is
 * able to address. The provider's own half - upstream, header, prefix, path - is a {@link
 * ProviderDefinition} and is declared once, elsewhere.
 *
 * <p><strong>The endpoint shape belongs here for a reason that cost a day to learn.</strong> The
 * same provider is reached over a unix socket by one agent and only as a URL by another, and the
 * two are satisfied very differently: a socket is a file the container mounts, while a URL needs
 * something listening inside the container's own network namespace.
 *
 * @param dialect Wire format the agent speaks, or {@code native} when it speaks whatever the
 *     provider's own is.
 * @param defaultProvider Provider used when the operator names none, or {@code null} when the agent
 *     has no default and one must be chosen.
 * @param endpoint What the agent is able to address the proxy as.
 * @param socketEnvironment Variable naming the unix socket, or {@code null}.
 * @param baseUrlEnvironment Variable naming the endpoint URL, or {@code null}.
 * @param addsPath Whether the agent adds the dialect's path to its base URL itself, so {@code
 *     baseUrlEnvironment} is given the broker's address alone.
 * @param dialects Other wire formats the agent speaks, in preference order after {@code dialect},
 *     each with its own base URL variable and {@code adds_path}.
 * @param knows The providers a {@code native} agent recognizes by name, from {@code
 *     provider.knows}. A {@code native} agent is told only a provider's name and endpoint, so one
 *     it does not recognize would start and then fail inside the task; with a list, it is paired
 *     only with the providers in it. Empty means the agent did not say, and is paired with every
 *     provider, as before the list existed.
 */
public record AgentProvider(
        String dialect,
        @Nullable String defaultProvider,
        ProviderRoute.Endpoint endpoint,
        @Nullable String socketEnvironment,
        @Nullable String baseUrlEnvironment,
        boolean addsPath,
        Map<String, Dialect> dialects,
        List<String> knows) {

    /**
     * How the agent addresses one of the wire formats it speaks.
     *
     * @param baseUrlEnvironment Variable naming the endpoint URL, or {@code null}.
     * @param addsPath Whether the agent adds the dialect's path to its base URL itself.
     */
    public record Dialect(@Nullable String baseUrlEnvironment, boolean addsPath) {

        /** Compact constructor. */
        public Dialect {
            if (baseUrlEnvironment != null && baseUrlEnvironment.isBlank()) {
                throw new AgentException("provider dialect base_url_env must not be blank");
            }
            if (addsPath && baseUrlEnvironment == null) {
                throw new AgentException(
                        "'provider.adds_path' needs 'provider.base_url_env' beside it");
            }
        }
    }

    /**
     * Constructor for an agent whose base URL includes the dialect's path, as every agent did
     * before {@code adds_path}.
     *
     * @param dialect Wire format spoken.
     * @param defaultProvider Provider used when none is named.
     * @param endpoint Endpoint shape the agent can address.
     * @param socketEnvironment Variable naming the socket.
     * @param baseUrlEnvironment Variable naming the URL.
     */
    public AgentProvider(
            String dialect,
            @Nullable String defaultProvider,
            ProviderRoute.Endpoint endpoint,
            @Nullable String socketEnvironment,
            @Nullable String baseUrlEnvironment) {
        this(
                dialect,
                defaultProvider,
                endpoint,
                socketEnvironment,
                baseUrlEnvironment,
                false,
                Map.of());
    }

    /**
     * Constructor for an agent speaking one dialect.
     *
     * @param dialect Wire format spoken.
     * @param defaultProvider Provider used when none is named.
     * @param endpoint Endpoint shape the agent can address.
     * @param socketEnvironment Variable naming the socket.
     * @param baseUrlEnvironment Variable naming the URL.
     * @param addsPath Whether the agent adds the dialect's path to its base URL itself.
     */
    public AgentProvider(
            String dialect,
            @Nullable String defaultProvider,
            ProviderRoute.Endpoint endpoint,
            @Nullable String socketEnvironment,
            @Nullable String baseUrlEnvironment,
            boolean addsPath) {
        this(
                dialect,
                defaultProvider,
                endpoint,
                socketEnvironment,
                baseUrlEnvironment,
                addsPath,
                Map.of());
    }

    /**
     * Constructor for an agent that lists no providers it knows.
     *
     * @param dialect Wire format spoken.
     * @param defaultProvider Provider used when none is named.
     * @param endpoint Endpoint shape the agent can address.
     * @param socketEnvironment Variable naming the socket.
     * @param baseUrlEnvironment Variable naming the URL.
     * @param addsPath Whether the agent adds the dialect's path to its base URL itself.
     * @param dialects Other wire formats spoken, in preference order.
     */
    public AgentProvider(
            String dialect,
            @Nullable String defaultProvider,
            ProviderRoute.Endpoint endpoint,
            @Nullable String socketEnvironment,
            @Nullable String baseUrlEnvironment,
            boolean addsPath,
            Map<String, Dialect> dialects) {
        this(
                dialect,
                defaultProvider,
                endpoint,
                socketEnvironment,
                baseUrlEnvironment,
                addsPath,
                dialects,
                List.of());
    }

    /**
     * Constructor with all data.
     *
     * @param dialect Wire format spoken.
     * @param defaultProvider Provider used when none is named.
     * @param endpoint Endpoint shape the agent can address.
     * @param socketEnvironment Variable naming the socket.
     * @param baseUrlEnvironment Variable naming the URL.
     * @param addsPath Whether the agent adds the dialect's path to its base URL itself.
     * @param dialects Other wire formats spoken, in preference order.
     * @param knows Providers a {@code native} agent recognizes, or empty when it does not say.
     */
    public AgentProvider {
        if (dialect == null || dialect.isBlank()) {
            throw new AgentException("An agent must say which dialect it speaks, or 'native'");
        }
        if (endpoint == ProviderRoute.Endpoint.SOCKET
                && socketEnvironment == null
                && baseUrlEnvironment == null) {
            // An agent that can be pointed at a socket but names no variable for it cannot actually
            // be pointed anywhere, and would fail by talking straight to the provider.
            throw new AgentException(
                    "An agent addressing a socket must name the variable" + " carrying it");
        }
        if (addsPath && (baseUrlEnvironment == null || baseUrlEnvironment.isBlank())) {
            throw new AgentException(
                    "'provider.adds_path' needs 'provider.base_url_env' beside it");
        }
        final Map<String, Dialect> ordered = new LinkedHashMap<>();
        final Map<String, String> variables = new LinkedHashMap<>();
        if (baseUrlEnvironment != null && !baseUrlEnvironment.isBlank()) {
            variables.put(baseUrlEnvironment, dialect);
        }
        if (dialects != null) {
            for (final Map.Entry<String, Dialect> entry : dialects.entrySet()) {
                final String name = entry.getKey();
                final Dialect value =
                        Objects.requireNonNull(entry.getValue(), "provider.dialects entry");
                if (name == null || name.isBlank()) {
                    throw new AgentException("provider.dialects key must not be blank");
                }
                if (name.equals(dialect)) {
                    throw new AgentException(
                            "'provider.dialects' lists '"
                                    + name
                                    + "' again, which is already 'provider.dialect'");
                }
                final String variable = value.baseUrlEnvironment();
                if (variable == null || variable.isBlank()) {
                    throw new AgentException(
                            "'provider.dialects." + name + "' must name 'base_url_env'");
                }
                final String previous = variables.put(variable, name);
                if (previous != null) {
                    throw new AgentException(
                            "base_url_env '"
                                    + variable
                                    + "' is named for two dialects: '"
                                    + previous
                                    + "' and '"
                                    + name
                                    + "'");
                }
                ordered.put(name, value);
            }
        }
        dialects = Collections.unmodifiableMap(ordered);
        knows = knows == null ? List.of() : List.copyOf(knows);
        if (!knows.isEmpty() && !ProviderDefinition.NATIVE_DIALECT.equals(dialect)) {
            // A list beside a dialect would be ignored in silence: such an agent is paired by the
            // wire format, which says everything the list could.
            throw new AgentException(
                    "'provider.knows' is for an agent that speaks '"
                            + ProviderDefinition.NATIVE_DIALECT
                            + "', not '"
                            + dialect
                            + "'");
        }
        for (final String name : knows) {
            if (name == null || name.isBlank()) {
                throw new AgentException("'provider.knows' must not list a blank name");
            }
        }
        if (defaultProvider != null && !knows.isEmpty() && !knows.contains(defaultProvider)) {
            throw new AgentException(
                    "'provider.default' names '"
                            + defaultProvider
                            + "', which is not in 'provider.knows'");
        }
    }

    /**
     * Returns whether this agent recognizes a provider by its name.
     *
     * <p>Only a {@code native} agent can fail to: it is told the provider's name and endpoint and
     * nothing else. An agent that lists nothing is taken to recognize every provider.
     *
     * @param provider The provider.
     * @return {@code false} only for a {@code native} agent whose list leaves the provider out.
     */
    public boolean knows(final ProviderDefinition provider) {
        return !ProviderDefinition.NATIVE_DIALECT.equals(dialect)
                || knows.isEmpty()
                || knows.contains(provider.name());
    }

    /**
     * Chooses the first dialect this agent speaks that the provider serves: {@link #dialect()}
     * first, then {@link #dialects()} in declaration order. {@code native} is chosen only for a
     * provider the agent {@link #knows(ProviderDefinition) knows}.
     *
     * @param provider The provider.
     * @return The chosen dialect name, or {@code null} when the provider serves none of them.
     */
    public @Nullable String dialectFor(final ProviderDefinition provider) {
        if (provider.serves(dialect) && knows(provider)) {
            return dialect;
        }
        for (final String candidate : dialects.keySet()) {
            if (provider.serves(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns the base-URL settings for one of the dialects this agent speaks.
     *
     * @param chosenDialect Dialect name.
     * @return Settings for that dialect.
     * @throws AgentException If this agent does not speak {@code chosenDialect}.
     */
    public Dialect settingsFor(final String chosenDialect) {
        if (dialect.equals(chosenDialect)) {
            return new Dialect(baseUrlEnvironment, addsPath);
        }
        final Dialect found = dialects.get(chosenDialect);
        if (found == null) {
            throw new AgentException("Agent does not speak dialect '" + chosenDialect + "'");
        }
        return found;
    }

    /**
     * Returns whether this agent can drive the given provider.
     *
     * @param provider The provider.
     * @return {@code true} when the provider serves a dialect this agent speaks, and for {@code
     *     native}, when the agent knows it.
     */
    public boolean canDrive(ProviderDefinition provider) {
        return dialectFor(provider) != null;
    }
}
