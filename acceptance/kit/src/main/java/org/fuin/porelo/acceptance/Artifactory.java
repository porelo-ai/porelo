package org.fuin.porelo.acceptance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Finds the newest snapshot of a package in Artifactory and the sha256 Artifactory names for it,
 * through its storage API, so the kit pins no build of a tool it only tests with.
 */
final class Artifactory {

    /** A snapshot's version, as the packages' file names carry it. */
    private static final Pattern VERSION =
            Pattern.compile("([0-9]+(?:\\.[0-9]+)*)~snapshot\\.([0-9]+)");

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** Reads one address and answers its body. */
    @FunctionalInterface
    interface Lookup {

        /**
         * Reads one address.
         *
         * @param url The https address.
         * @return The body of a successful answer.
         * @throws IOException If it cannot be read, or the answer is no success; it carries what
         *     the service said.
         */
        String get(String url) throws IOException;
    }

    private Artifactory() {}

    /**
     * Finds the newest snapshot in one folder.
     *
     * @param lookup How an address is read.
     * @param base Artifactory's https address, without a trailing slash.
     * @param folder The repository and folder the packages lie in.
     * @param name The package's file name, with {@code {version}} where its version stands.
     * @return The newest snapshot's address and the sha256 Artifactory names for it.
     * @throws IOException If Artifactory cannot be read.
     * @throws IllegalStateException If the folder holds no snapshot, or Artifactory names no sha256
     *     for it.
     */
    static MessageFilter.Package newest(Lookup lookup, String base, String folder, String name)
            throws IOException {
        final int at = name.indexOf("{version}");
        if (at < 0) {
            throw new IllegalStateException(name + " has no {version}");
        }
        final String prefix = "/" + name.substring(0, at);
        final String suffix = name.substring(at + "{version}".length());
        final String storage = base + "/api/storage/" + folder;
        final String newest =
                children(Json.parse(lookup.get(storage))).stream()
                        .filter(uri -> uri.startsWith(prefix) && uri.endsWith(suffix))
                        .map(uri -> uri.substring(prefix.length(), uri.length() - suffix.length()))
                        .filter(version -> VERSION.matcher(version).matches())
                        .max(Artifactory::compare)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                storage + " lists no snapshot named " + name));
        final String file = name.replace("{version}", newest);
        final String sha256 = sha256(Json.parse(lookup.get(storage + "/" + file)));
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalStateException(
                    "Artifactory names no sha256 for " + storage + "/" + file);
        }
        return new MessageFilter.Package(base + "/" + folder + "/" + file, sha256);
    }

    /**
     * Orders two snapshot versions by their release numbers, then by their snapshot number.
     *
     * @param left A version.
     * @param right Another one.
     * @return Below zero when the left is older, above when it is newer.
     */
    static int compare(String left, String right) {
        final Matcher one = VERSION.matcher(left);
        final Matcher other = VERSION.matcher(right);
        if (!one.matches() || !other.matches()) {
            throw new IllegalArgumentException(left + " or " + right + " is no snapshot version");
        }
        final List<Long> mine = numbers(one.group(1));
        final List<Long> theirs = numbers(other.group(1));
        for (int i = 0; i < Math.max(mine.size(), theirs.size()); i++) {
            final int byPart =
                    Long.compare(
                            i < mine.size() ? mine.get(i) : 0L,
                            i < theirs.size() ? theirs.get(i) : 0L);
            if (byPart != 0) {
                return byPart;
            }
        }
        return Long.compare(Long.parseLong(one.group(2)), Long.parseLong(other.group(2)));
    }

    /**
     * Reads an address over https, following redirects within https.
     *
     * @param url The address.
     * @return The body of a 200 answer.
     * @throws IOException If it cannot be read, or the answer is another status; it carries the
     *     status and what the service said.
     */
    static String https(String url) throws IOException {
        final HttpClient client =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(TIMEOUT)
                        .build();
        final HttpRequest request =
                HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).build();
        final HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading " + url, ex);
        } finally {
            client.close();
        }
        if (response.statusCode() != 200) {
            final String body = response.body().strip();
            throw new IOException(
                    url
                            + " answered "
                            + response.statusCode()
                            + ": "
                            + (body.length() > 500 ? body.substring(0, 500) + "..." : body));
        }
        return response.body();
    }

    private static List<String> children(@Nullable Object listing) {
        if (!(listing instanceof Map<?, ?> map) || !(map.get("children") instanceof List<?> list)) {
            throw new IllegalStateException("Artifactory's listing has no children: " + listing);
        }
        return list.stream()
                .flatMap(
                        child ->
                                child instanceof Map<?, ?> entry
                                                && entry.get("uri") instanceof String uri
                                                && !Boolean.TRUE.equals(entry.get("folder"))
                                        ? Optional.of(uri).stream()
                                        : Optional.<String>empty().stream())
                .toList();
    }

    private static @Nullable String sha256(@Nullable Object info) {
        return info instanceof Map<?, ?> map
                        && map.get("checksums") instanceof Map<?, ?> checksums
                        && checksums.get("sha256") instanceof String sha256
                ? sha256
                : null;
    }

    private static List<Long> numbers(String release) {
        return Pattern.compile("\\.").splitAsStream(release).map(Long::parseLong).toList();
    }
}
