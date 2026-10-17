package org.fuin.porelo.app;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.supervisor.ClientRegistration;
import org.fuin.porelo.supervisor.CodeGrant;
import org.fuin.porelo.supervisor.DeviceGrant;
import org.fuin.porelo.supervisor.Grants;
import org.fuin.porelo.supervisor.ServiceMetadata;
import org.fuin.porelo.supervisor.TokenPurchase;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultFile;
import org.jspecify.annotations.Nullable;

/**
 * Completes a vault entry that names only a service: its endpoints from the service's metadata,
 * and, where the entry has no client id, a client registered with the service once.
 *
 * <p>Done on the host when a person authorizes the entry, never in a task. What comes back is
 * written into the entry itself, so every later {@code authorize}, the broker and every task read
 * it exactly as an entry a person set up by hand: nothing is asked of the service twice. The
 * registration access token, which deletes the registration again, is kept hidden beside it as
 * {@code registration/<entry>}.
 */
final class ServiceRegistration {

    /** The setting that names the protected resource whose metadata is read. */
    static final String RESOURCE = "resource_url";

    /** The kind of the hidden entry that keeps a registration access token. */
    static final String KIND = "registration-access-token";

    /** The setting of the hidden entry that names the registration's own URL. */
    static final String CLIENT_URI = "registration_client_uri";

    private ServiceRegistration() {}

    /**
     * Returns whether an entry names only its service and still lacks what a grant needs.
     *
     * @param entry The entry, or {@code null}.
     * @return true for a grant's entry with a {@value #RESOURCE} and no client id or token URL.
     */
    static boolean needed(@Nullable VaultEntry entry) {
        return entry != null
                && Grants.isGrant(entry.type())
                && entry.settings().containsKey(RESOURCE)
                && (!entry.settings().containsKey("client_id")
                        || !entry.settings().containsKey("token_url"));
    }

    /**
     * Reads the service's metadata and registers a client where the entry needs one, keeping both
     * in the vault.
     *
     * <p>The registration is made inside the vault's update, under its lock, so two authorizations
     * started at once register one client, not two.
     *
     * @param context Where the vault is.
     * @param name The entry.
     * @param http How the service is reached.
     * @return What was done, for the person, or "" when the entry needed nothing.
     * @throws TokenPurchase.Refused If the metadata cannot be read, offers no endpoint for the
     *     entry's flow, or the entry has no client id and the service registers none.
     */
    static String complete(final PoreloContext context, final String name, final HttpClient http)
            throws TokenPurchase.Refused {
        final VaultEntry entry = context.credentials().get(name);
        if (!needed(entry)) {
            return "";
        }
        final VaultEntry named = Objects.requireNonNull(entry);
        final boolean device = DeviceGrant.KIND.equals(named.type());
        final ServiceMetadata.Endpoints endpoints =
                ServiceMetadata.discover(
                        http, Objects.requireNonNull(named.settings().get(RESOURCE)));
        final String grantUrl = device ? endpoints.deviceUrl() : endpoints.authorizationUrl();
        if (grantUrl == null) {
            throw new TokenPurchase.Refused(
                    "the authorization server at "
                            + host(endpoints.issuer())
                            + " offers no "
                            + (device ? "device code" : "authorization endpoint")
                            + "; store the entry as kind "
                            + (device ? CodeGrant.KIND : DeviceGrant.KIND)
                            + " instead");
        }
        final VaultFile.Opener opener =
                context.opener()
                        .orElseGet(() -> VaultFile.Opener.passphrase(context.requirePassphrase()));
        final String[] said = {"read the endpoints of " + host(endpoints.issuer())};
        try {
            context.vault()
                    .update(
                            opener,
                            entries -> {
                                final VaultEntry found = entries.get(name);
                                if (!needed(found)) {
                                    // Completed meanwhile, by another authorization.
                                    return entries;
                                }
                                final VaultEntry current = Objects.requireNonNull(found);
                                final Map<String, String> settings =
                                        new LinkedHashMap<>(current.settings());
                                String value = current.value();
                                if (!settings.containsKey("client_id")) {
                                    final ClientRegistration.Registered registered =
                                            register(endpoints, device, settings, http);
                                    settings.put("client_id", registered.clientId());
                                    final String secret = registered.clientSecret();
                                    value = secret == null ? GrantEntry.NO_SECRET : secret;
                                    if (registered.managed()) {
                                        entries.put(
                                                VaultNames.REGISTRATION_PREFIX + name,
                                                new VaultEntry(
                                                        Objects.requireNonNull(
                                                                registered.accessToken()),
                                                        KIND,
                                                        Map.of(
                                                                CLIENT_URI,
                                                                Objects.requireNonNull(
                                                                        registered.clientUri()))));
                                    }
                                    said[0] =
                                            "registered a client with "
                                                    + host(endpoints.issuer())
                                                    + ": "
                                                    + registered.clientId();
                                }
                                // What a person set on the entry by hand stays as it is.
                                settings.putIfAbsent(
                                        device ? "device_authorization_url" : "authorization_url",
                                        grantUrl);
                                settings.putIfAbsent("token_url", endpoints.tokenUrl());
                                final String revocation = endpoints.revocationUrl();
                                if (revocation != null) {
                                    settings.putIfAbsent("revocation_url", revocation);
                                }
                                entries.put(name, new VaultEntry(value, current.type(), settings));
                                return entries;
                            });
        } catch (Unregistered ex) {
            throw ex.refused;
        }
        return said[0];
    }

    private static ClientRegistration.Registered register(
            final ServiceMetadata.Endpoints endpoints,
            final boolean device,
            final Map<String, String> settings,
            final HttpClient http) {
        final String url = endpoints.registrationUrl();
        try {
            if (url == null) {
                throw new TokenPurchase.Refused(
                        "the authorization server at "
                                + host(endpoints.issuer())
                                + " registers no client itself; register one there and set"
                                + " 'client_id' on the entry");
            }
            return ClientRegistration.register(
                    http,
                    url,
                    ClientRegistration.metadata(
                            device,
                            device
                                    ? null
                                    : CodeGrant.Client.redirectUri(CodeGrant.Client.port(settings)),
                            settings.getOrDefault("scopes", "")));
        } catch (TokenPurchase.Refused ex) {
            throw new Unregistered(ex);
        }
    }

    /**
     * Returns the registration kept for an entry, or {@code null} where none is.
     *
     * @param context Where the vault is.
     * @param name The entry.
     * @return The hidden entry holding the registration access token, or {@code null}.
     */
    static @Nullable VaultEntry kept(final PoreloContext context, final String name) {
        return context.opener()
                .map(
                        opener ->
                                context.vault()
                                        .read(opener)
                                        .get(VaultNames.REGISTRATION_PREFIX + name))
                .orElse(null);
    }

    /**
     * Deletes a registration at the service (RFC 7592).
     *
     * @param registration The hidden entry holding it.
     * @param http How the service is reached.
     * @return {@code null} when the service deleted it, else why it could not be told.
     */
    static @Nullable String delete(final VaultEntry registration, final HttpClient http) {
        final String uri = registration.settings().get(CLIENT_URI);
        if (uri == null) {
            return "the registration's URL is not kept, so the service cannot be told";
        }
        try {
            ClientRegistration.delete(http, uri, registration.value());
            return null;
        } catch (TokenPurchase.Refused ex) {
            return ex.getMessage();
        }
    }

    private static String host(final String url) {
        final String host = java.net.URI.create(url).getHost();
        return host == null ? url : host;
    }

    /** Carries a refusal out of the vault's update, which takes no checked exception. */
    private static final class Unregistered extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient TokenPurchase.Refused refused;

        Unregistered(final TokenPurchase.Refused refused) {
            super(refused.getMessage(), refused);
            this.refused = refused;
        }
    }
}
