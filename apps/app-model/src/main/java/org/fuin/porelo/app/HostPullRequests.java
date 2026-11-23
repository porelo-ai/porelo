package org.fuin.porelo.app;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;

/** Opens reviews on the host's Forgejo with the authoring task's identity. */
final class HostPullRequests implements GitGate.PullRequests {

    private final URI repository;
    private final Function<String, Optional<String>> tokens;

    private final LimitFactors limits;

    HostPullRequests(
            String upstream, Function<String, Optional<String>> tokens, LimitFactors limits) {
        final URI address = URI.create(upstream);
        final String path = address.getPath();
        if (!path.matches("/[A-Za-z0-9_][A-Za-z0-9._-]*/[A-Za-z0-9_][A-Za-z0-9._-]*\\.git")) {
            throw new GateException("the host's upstream must name <owner>/<repo>.git");
        }
        repository =
                URI.create(
                        "http://127.0.0.1:"
                                + address.getPort()
                                + "/api/v1/repos"
                                + path.substring(0, path.length() - 4));
        this.tokens = tokens;
        this.limits = limits;
    }

    @Override
    public String open(String task, String branch, String subject) {
        final String token = tokens.apply(task).orElse("");
        if (token.isBlank() || !token.matches("[\\x21-\\x7e]+")) {
            throw failed(
                    branch,
                    "no usable forge token is kept for this task; unlock the vault and retry");
        }
        // No redirect or proxy may carry a task's identity beyond the host's own forge.
        try (HttpClient client =
                HttpClient.newBuilder()
                        .proxy(HttpClient.Builder.NO_PROXY)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(limits.services(Duration.ofSeconds(10)))
                        .build()) {
            final Object details = request(client, token, "", null);
            if (!(details instanceof Map<?, ?> fields)
                    || !(fields.get("default_branch") instanceof String base)
                    || base.isBlank()) {
                throw new GateException("the forge returned no default branch");
            }
            // Every page is read, to the forge's first empty one, as a forge may answer fewer than
            // asked on every page; a page adding no pull not already read means the forge ignores
            // the page asked, which would never end.
            final Set<Long> read = new HashSet<>();
            for (int page = 1; ; page++) {
                final Object listed =
                        request(client, token, "/pulls?state=open&limit=50&page=" + page, null);
                if (!(listed instanceof List<?> pulls)) {
                    throw new GateException("the forge returned no pull request list");
                }
                boolean added = false;
                for (Object pull : pulls) {
                    if (pull instanceof Map<?, ?> numbered
                            && numbered.get("number") instanceof Number number) {
                        added |= read.add(number.longValue());
                    }
                    if (pull instanceof Map<?, ?> entry
                            && entry.get("head") instanceof Map<?, ?> head
                            && branch.equals(head.get("ref"))
                            && entry.get("base") instanceof Map<?, ?> target
                            && base.equals(target.get("ref"))
                            && sameRepository(head)) {
                        return url(entry, token);
                    }
                }
                if (pulls.isEmpty()) break;
                if (!added)
                    throw new GateException("the forge repeated a page of open pull requests");
            }
            final Object opened =
                    request(
                            client,
                            token,
                            "/pulls",
                            Map.of(
                                    "head",
                                    branch,
                                    "base",
                                    base,
                                    "title",
                                    subject,
                                    "body",
                                    "Reviewed work from Porelo task " + task + "."));
            if (!(opened instanceof Map<?, ?> pull)) {
                throw new GateException("the forge returned no pull request");
            }
            return url(pull, token);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failed(branch, "the forge request was interrupted");
        } catch (IOException ex) {
            throw failed(branch, "the host's forge could not be reached");
        } catch (RuntimeException ex) {
            // Neither HTTP header validation nor a malformed response may echo the credential.
            throw failed(
                    branch,
                    ex instanceof GateException
                            ? safe(ex.getMessage(), token)
                            : "the forge returned an unusable response");
        }
    }

    private boolean sameRepository(Map<?, ?> head) {
        return !(head.get("repo") instanceof Map<?, ?> repo)
                || repository
                        .getPath()
                        .substring("/api/v1/repos/".length())
                        .equals(repo.get("full_name"));
    }

    private static String url(Map<?, ?> pull, String token) {
        if (pull.get("html_url") instanceof String url && !url.contains(token)) {
            final URI address = URI.create(url);
            if (("http".equals(address.getScheme()) || "https".equals(address.getScheme()))
                    && address.getHost() != null
                    && address.getUserInfo() == null) {
                return url;
            }
        }
        throw new GateException("the forge returned no usable pull request URL");
    }

    private Object request(
            HttpClient client, String token, String suffix, @Nullable Map<String, String> body)
            throws IOException, InterruptedException {
        final HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(repository + suffix))
                        .timeout(limits.services(Duration.ofSeconds(20)))
                        .header("Authorization", "token " + token)
                        .header("Accept", "application/json");
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        }
        final HttpResponse<String> response =
                client.send(
                        request.build(),
                        HttpResponse.BodyHandlers.limiting(
                                HttpResponse.BodyHandlers.ofString(),
                                HostPullRequestReader.MAXIMUM_ANSWER));
        final Object parsed;
        try {
            parsed = Json.parse(response.body());
        } catch (RuntimeException ex) {
            throw new GateException(
                    "forge HTTP " + response.statusCode() + ": invalid JSON response");
        }
        if (response.statusCode() / 100 != 2) {
            final String message =
                    parsed instanceof Map<?, ?> fields
                                    && fields.get("message") instanceof String text
                            ? safe(text, token)
                            : "no message";
            throw new GateException("forge HTTP " + response.statusCode() + ": " + message);
        }
        if (parsed == null) throw new GateException("the forge returned an empty response");
        return parsed;
    }

    private static String safe(@Nullable String text, String token) {
        return text == null
                ? "no message"
                : text.replace(token, "<token>").replaceAll("[\\p{Cntrl}]", " ");
    }

    private static GateException failed(String branch, String why) {
        return new GateException(
                "pushed to "
                        + branch
                        + ", but the pull request failed: "
                        + why
                        + "; the approval remains pending; retry gate approve");
    }
}
