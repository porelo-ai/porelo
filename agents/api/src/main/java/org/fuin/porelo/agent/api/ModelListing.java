package org.fuin.porelo.agent.api;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where a provider lists the models it offers, and in which shape: the {@code models} section of a
 * provider file.
 *
 * <p>Data, as the rest of a provider is: Porelo reads a shape it knows, never a provider it knows.
 * The list is asked with the credential the vault holds, in the header the provider declares for
 * it, so the path is on the provider's own upstream and names nothing else.
 *
 * @param path Path on the upstream the list is asked at, starting with {@code /}.
 * @param format The shape of the answer.
 * @param headers Credential type to the headers sent beside the credential, {@code _default} for
 *     every type that names none of its own.
 */
public record ModelListing(String path, Format format, Map<String, Map<String, String>> headers) {

    /** The shapes of a model list Porelo reads. */
    public enum Format {

        /**
         * {@code data[]} with each model's {@code id} and {@code display_name}, paged by {@code
         * has_more} and the {@code last_id} handed back as {@code after_id}.
         */
        LAST_ID_PAGES("last-id-pages"),

        /**
         * {@code data[]} with each model's {@code id}, its {@code name} where given and the efforts
         * it takes under {@code reasoning.supported_efforts}, paged by {@code links.next} where the
         * answer names one.
         */
        DATA("data");

        private final String value;

        Format(final String value) {
            this.value = value;
        }

        /**
         * Returns the format as a provider file names it.
         *
         * @return The name.
         */
        public String value() {
            return value;
        }

        /**
         * Returns the format a provider file names.
         *
         * @param declared The name.
         * @param origin Where it was read, for the message.
         * @return The format.
         * @throws AgentException If it names no format Porelo reads.
         */
        public static Format of(final String declared, final String origin) {
            for (final Format format : values()) {
                if (format.value.equals(declared.toLowerCase(Locale.ROOT))) {
                    return format;
                }
            }
            throw new AgentException(
                    origin
                            + ": 'models.format' must be 'last-id-pages' or 'data', not '"
                            + declared
                            + "'");
        }
    }

    /** Checks the path and the headers. */
    public ModelListing {
        if (!path.matches("/[A-Za-z0-9._/-]{0,200}")
                || path.contains("..")
                || path.contains("//")) {
            // A path and nothing else: a query, a host or a parent would send the credential
            // somewhere the provider does not serve its list.
            throw new AgentException(
                    "'models.path' must be a plain path starting with '/', not '" + path + "'");
        }
        final Map<String, Map<String, String>> copied = new LinkedHashMap<>();
        headers.forEach(
                (type, named) -> {
                    named.forEach(
                            (header, value) -> {
                                if (!header.matches("[A-Za-z0-9-]{1,64}")
                                        || !value.matches("[\\x20-\\x7e]{0,200}")) {
                                    throw new AgentException(
                                            "'models.headers."
                                                    + type
                                                    + "' holds a header that is not one name and"
                                                    + " one line: '"
                                                    + header
                                                    + "'");
                                }
                            });
                    copied.put(type, Map.copyOf(named));
                });
        headers = java.util.Collections.unmodifiableMap(copied);
    }

    /**
     * Returns the headers sent beside a credential of a type.
     *
     * @param credentialType The credential's type, for example {@code oauth}.
     * @return The headers, empty when none are declared.
     */
    public Map<String, String> headersFor(final String credentialType) {
        return headers.getOrDefault(
                credentialType, headers.getOrDefault(AgentDefinition.DEFAULT_TOKEN_KEY, Map.of()));
    }
}
