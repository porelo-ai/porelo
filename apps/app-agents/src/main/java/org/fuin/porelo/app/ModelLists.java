package org.fuin.porelo.app;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.fuin.porelo.agent.api.ProviderDefinition;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;

/**
 * The models a provider offers this machine, asked of the provider with the credential the vault
 * holds for it, in the header and with the prefix the broker puts it in.
 *
 * <p>Where and in which shape is the provider's own {@code models} section ({@link
 * org.fuin.porelo.agent.api.ModelListing}), never a provider Porelo knows by name. A provider that
 * declares none, one the vault holds nothing for, and one whose stored kind it cannot broker answer
 * {@code none}.
 *
 * <p>A listing is kept in memory for an hour per provider. One read per provider is in flight at a
 * time, and a provider is asked at most once a minute: within it, its last answer or failure is
 * given again, fresh or not; the hour and the minute follow the polling factor, a listing's time
 * the services factor. Nothing here writes a file, and no message carries the credential or what
 * the provider sent back.
 */
public final class ModelLists {

    /** How long a listing is answered from memory. */
    public static final Duration KEPT = Duration.ofHours(1);

    /** How long one listing may take, every page included. */
    public static final Duration PATIENCE = Duration.ofSeconds(20);

    /** How long after one ask a provider is not asked again, fresh or not. */
    public static final Duration SPACING = Duration.ofMinutes(1);

    /** More pages than any provider's list has; a provider that pages on is refused. */
    private static final int MOST_PAGES = 50;

    /** The credential kind a stored entry without one is, as a start takes it. */
    private static final String DEFAULT_TYPE = "api-key";

    /**
     * One model a provider offers.
     *
     * @param id What Start's model takes.
     * @param label What a person is shown, the id where the provider names none.
     * @param efforts The reasoning efforts the provider says the model takes, or {@code null} where
     *     it says none.
     */
    public record Model(String id, String label, @Nullable List<String> efforts) {

        /** Copies the efforts. */
        public Model {
            efforts = efforts == null ? null : List.copyOf(efforts);
        }

        /**
         * Returns the model as the contract's {@code Model}.
         *
         * @return Plain values.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("label", label);
            if (efforts != null) {
                map.put("efforts", efforts);
            }
            return map;
        }
    }

    /**
     * What a provider offers.
     *
     * @param models Its models, in its order; empty when {@code from} is {@code none}.
     * @param from {@code provider} or {@code none}.
     * @param readAt When it was read.
     */
    public record Listing(List<Model> models, String from, Instant readAt) {

        /** Copies the models. */
        public Listing {
            models = List.copyOf(models);
        }

        /**
         * Returns the listing as the contract's {@code Models} reply.
         *
         * @return Plain values.
         */
        public Map<String, Object> asMap() {
            return Map.of(
                    "models",
                    models.stream().map(Model::asMap).toList(),
                    "from",
                    from,
                    "readAt",
                    readAt.toString());
        }
    }

    /** The provider was asked and did not answer with a list; the message says why. */
    public static final class Failed extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param message Why, without the credential.
         */
        public Failed(final String message) {
            super(message);
        }
    }

    /** One answer of a provider. */
    public record Answer(int status, String body) {

        @Override
        public String toString() {
            // A body is the provider's; it may echo what was sent.
            return "Answer[status=" + status + "]";
        }
    }

    /** How a request reaches a provider. */
    @FunctionalInterface
    public interface Transport {

        /**
         * Sends a GET.
         *
         * @param uri Where.
         * @param headers The headers, the credential's among them.
         * @param timeout How long it may take.
         * @return The answer.
         * @throws IOException If it could not be had.
         * @throws InterruptedException If interrupted while waiting.
         */
        Answer get(URI uri, Map<String, String> headers, Duration timeout)
                throws IOException, InterruptedException;
    }

    private final Transport transport;

    private final Clock clock;

    private final LimitFactors limits;

    /** What was last asked of each provider, by name; written under that provider's lock. */
    private final Map<String, Asked> asked = new ConcurrentHashMap<>();

    /** One lock per provider, so one read is in flight per provider and the others take it. */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * The last ask of a provider: when, and its listing or why it failed.
     *
     * @param at When it was asked.
     * @param listing What it answered, or {@code null} when it failed.
     * @param failure Why it failed, or {@code null}.
     */
    private record Asked(Instant at, @Nullable Listing listing, @Nullable String failure) {}

    /**
     * Asks the providers at their own https addresses.
     *
     * @param limits The factors {@link #KEPT}, {@link #PATIENCE} and {@link #SPACING} follow.
     */
    public ModelLists(final LimitFactors limits) {
        this(http(UnaryOperator.identity(), limits), Clock.systemUTC(), limits);
    }

    /**
     * Constructor.
     *
     * @param transport How a provider is reached.
     * @param clock What tells the time.
     * @param limits The factors {@link #KEPT}, {@link #PATIENCE} and {@link #SPACING} follow.
     */
    public ModelLists(final Transport transport, final Clock clock, final LimitFactors limits) {
        this.transport = transport;
        this.clock = clock;
        this.limits = limits;
    }

    /**
     * Returns the transport that sends over HTTP.
     *
     * @param address The address a provider's URL is sent to: itself, except in a test.
     * @param limits The factors its connect timeout, {@link #PATIENCE}, follows.
     * @return The transport.
     */
    public static Transport http(final UnaryOperator<URI> address, final LimitFactors limits) {
        final HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(limits.services(PATIENCE))
                        // A redirect would carry the credential to wherever it points.
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        return (uri, headers, timeout) -> {
            final HttpRequest.Builder request =
                    HttpRequest.newBuilder(address.apply(uri)).timeout(timeout).GET();
            headers.forEach(request::header);
            final HttpResponse<String> response =
                    client.send(
                            request.build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Answer(response.statusCode(), response.body());
        };
    }

    /**
     * Returns the models a provider offers.
     *
     * @param provider The provider.
     * @param credentials What the vault holds, read only when the provider has to be asked; empty
     *     when it is shut or unreadable.
     * @param fresh Whether to ask although a listing is kept; not within {@link #SPACING} of the
     *     last ask, which is answered again instead.
     * @return The listing.
     * @throws Failed If the provider was asked and did not answer with a list, or the vault is
     *     shut.
     */
    public Listing list(
            final ProviderDefinition provider,
            final Supplier<Optional<Map<String, VaultEntry>>> credentials,
            final boolean fresh)
            throws Failed {
        final org.fuin.porelo.agent.api.ModelListing listed = provider.models();
        if (listed == null) {
            return none();
        }
        // One read per provider at a time: a call that waited takes the answer the read gave.
        synchronized (locks.computeIfAbsent(provider.name(), name -> new Object())) {
            final Instant now = clock.instant();
            final Duration spacing = limits.polling(SPACING);
            final Asked last = asked.get(provider.name());
            if (last != null && now.isBefore(last.at().plus(spacing))) {
                // A loop of asks, fresh or not, reaches the provider once a minute.
                if (last.listing() != null) {
                    return last.listing();
                }
                throw new Failed(last.failure() + "; asked again from " + last.at().plus(spacing));
            }
            if (!fresh
                    && last != null
                    && last.listing() != null
                    && now.isBefore(last.listing().readAt().plus(limits.polling(KEPT)))) {
                return last.listing();
            }
            return read(provider, listed, credentials, now);
        }
    }

    private Listing read(
            final ProviderDefinition provider,
            final org.fuin.porelo.agent.api.ModelListing listed,
            final Supplier<Optional<Map<String, VaultEntry>>> credentials,
            final Instant now)
            throws Failed {
        final Map<String, VaultEntry> stored =
                credentials
                        .get()
                        .orElseThrow(
                                () ->
                                        new Failed(
                                                "the vault is shut, so '"
                                                        + provider.name()
                                                        + "' cannot be asked: open it with 'porelo"
                                                        + " vault unlock'"));
        final VaultEntry entry = stored.get(provider.name());
        if (entry == null) {
            return none();
        }
        final String type = entry.type() == null ? DEFAULT_TYPE : entry.type();
        if (provider.unbrokerableReason(type) != null) {
            return none();
        }
        try {
            final Listing listing =
                    new Listing(ask(provider, listed, entry, type), "provider", clock.instant());
            asked.put(provider.name(), new Asked(now, listing, null));
            return listing;
        } catch (Failed ex) {
            asked.put(provider.name(), new Asked(now, null, ex.getMessage()));
            throw ex;
        }
    }

    private Listing none() {
        return new Listing(List.of(), "none", clock.instant());
    }

    private List<Model> ask(
            final ProviderDefinition provider,
            final org.fuin.porelo.agent.api.ModelListing listed,
            final VaultEntry entry,
            final String type)
            throws Failed {
        final Map<String, String> headers = new LinkedHashMap<>();
        final String query = provider.authQueryFor(type);
        if (query == null) {
            headers.put(provider.authHeaderFor(type), provider.authPrefixFor(type) + entry.value());
        }
        provider.credentialHeaders()
                .forEach(
                        (header, setting) -> {
                            final String value = entry.settings().get(setting);
                            if (value != null) {
                                headers.put(header, value);
                            }
                        });
        headers.putAll(listed.headersFor(type));
        final boolean paged =
                listed.format() == org.fuin.porelo.agent.api.ModelListing.Format.LAST_ID_PAGES;
        final String base = trimmed(provider.upstream()) + listed.path();
        final String key =
                query == null
                        ? ""
                        : query + "=" + URLEncoder.encode(entry.value(), StandardCharsets.UTF_8);
        final URI upstream = URI.create(provider.upstream());
        if (!"https".equals(upstream.getScheme())) {
            // A provider refuses such an upstream when it is read; this keeps the key off one made
            // any other way.
            throw new Failed("'" + provider.name() + "' is not reached over https");
        }
        final long deadline = System.nanoTime() + limits.services(PATIENCE).toNanos();
        final List<Model> models = new ArrayList<>();
        String next = paged ? base + "?limit=1000" : base;
        for (int page = 0; next != null; page++) {
            if (page == MOST_PAGES) {
                throw new Failed(
                        "'" + provider.name() + "' kept paging past " + MOST_PAGES + " pages");
            }
            final URI plain = URI.create(next);
            if (!sameOrigin(plain, upstream)) {
                // The credential goes to the provider's own origin and to no address a page names:
                // the same host on plain http or another port would carry it in the clear.
                throw new Failed("'" + provider.name() + "' named a next page on another origin");
            }
            final Map<?, ?> document =
                    page(provider, URI.create(withKey(next, key)), headers, deadline);
            next =
                    paged
                            ? lastIdPage(provider, document, models, base)
                            : dataPage(provider, document, models, plain);
        }
        return models;
    }

    private static boolean sameOrigin(final URI one, final URI other) {
        return "https".equals(one.getScheme())
                && "https".equals(other.getScheme())
                && one.getHost() != null
                && one.getHost().equalsIgnoreCase(other.getHost())
                && port(one) == port(other)
                && one.getRawUserInfo() == null;
    }

    private static int port(final URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }

    private static String withKey(final String address, final String key) {
        if (key.isEmpty()) {
            return address;
        }
        return address + (address.contains("?") ? "&" : "?") + key;
    }

    private static String trimmed(final String upstream) {
        return upstream.endsWith("/") ? upstream.substring(0, upstream.length() - 1) : upstream;
    }

    private Map<?, ?> page(
            final ProviderDefinition provider,
            final URI uri,
            final Map<String, String> headers,
            final long deadline)
            throws Failed {
        final long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw late(provider);
        }
        final Answer answer;
        try {
            answer = transport.get(uri, headers, Duration.ofNanos(left));
        } catch (HttpTimeoutException ex) {
            throw late(provider);
        } catch (IOException ex) {
            // The exception's own text may hold the address, and with a key in the URL the key.
            throw new Failed(
                    "'"
                            + provider.name()
                            + "' could not be reached at "
                            + provider.upstreamHost()
                            + " ("
                            + ex.getClass().getSimpleName()
                            + ")");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failed("asking '" + provider.name() + "' was interrupted");
        }
        if (answer.status() < 200 || answer.status() > 299) {
            throw new Failed(
                    "'"
                            + provider.name()
                            + "' answered HTTP "
                            + answer.status()
                            + (answer.status() == 401 || answer.status() == 403
                                    ? ": it refused the vault's credential"
                                    : ""));
        }
        final Object document;
        try {
            document = Json.parse(answer.body());
        } catch (JsonException ex) {
            throw unreadable(provider, "is not JSON");
        }
        if (!(document instanceof Map<?, ?> map)) {
            throw unreadable(provider, "is not an object");
        }
        return map;
    }

    private Failed late(final ProviderDefinition provider) {
        return new Failed(
                "'"
                        + provider.name()
                        + "' did not answer with its list within "
                        + limits.services(PATIENCE).toSeconds()
                        + " s");
    }

    private static Failed unreadable(final ProviderDefinition provider, final String why) {
        return new Failed("'" + provider.name() + "' answered a list that " + why);
    }

    private static List<?> data(final ProviderDefinition provider, final Map<?, ?> page)
            throws Failed {
        if (!(page.get("data") instanceof List<?> data)) {
            throw unreadable(provider, "has no 'data'");
        }
        return data;
    }

    private static String id(final ProviderDefinition provider, final Object item) throws Failed {
        if (item instanceof Map<?, ?> model
                && model.get("id") instanceof String id
                && !id.isBlank()) {
            return id;
        }
        throw unreadable(provider, "has a model without an id");
    }

    private static String label(final Object item, final String key, final String id) {
        return ((Map<?, ?>) item).get(key) instanceof String label && !label.isBlank() ? label : id;
    }

    private static @Nullable String lastIdPage(
            final ProviderDefinition provider,
            final Map<?, ?> page,
            final List<Model> models,
            final String base)
            throws Failed {
        for (final Object item : data(provider, page)) {
            final String id = id(provider, item);
            models.add(new Model(id, label(item, "display_name", id), null));
        }
        if (!Boolean.TRUE.equals(page.get("has_more"))) {
            return null;
        }
        if (!(page.get("last_id") instanceof String last) || last.isBlank()) {
            throw unreadable(provider, "says there is more and names no last id");
        }
        return base + "?limit=1000&after_id=" + URLEncoder.encode(last, StandardCharsets.UTF_8);
    }

    private static @Nullable String dataPage(
            final ProviderDefinition provider,
            final Map<?, ?> page,
            final List<Model> models,
            final URI asked)
            throws Failed {
        for (final Object item : data(provider, page)) {
            final String id = id(provider, item);
            models.add(new Model(id, label(item, "name", id), efforts((Map<?, ?>) item)));
        }
        if (page.get("links") instanceof Map<?, ?> links
                && links.get("next") instanceof String next
                && !next.isBlank()) {
            return asked.resolve(next).toString();
        }
        return null;
    }

    private static @Nullable List<String> efforts(final Map<?, ?> model) {
        if (model.get("reasoning") instanceof Map<?, ?> reasoning
                && reasoning.get("supported_efforts") instanceof List<?> listed) {
            final List<String> efforts =
                    listed.stream()
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .toList();
            return efforts.isEmpty() ? null : efforts;
        }
        return null;
    }
}
