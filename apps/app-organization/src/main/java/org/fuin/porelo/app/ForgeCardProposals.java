package org.fuin.porelo.app;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.fuin.porelo.forge.forgejo.ForgejoClient;
import org.fuin.porelo.forge.forgejo.ForgejoException;
import org.fuin.porelo.forge.forgejo.model.CreatePullRequestOption;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.model.PullsCreateRequest;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.gittree.CardProposals;
import org.fuin.porelo.organization.gittree.CardProposals.Pull;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/** GitHub and Forgejo pull requests over the token lent for this organization's forge. */
final class ForgeCardProposals implements CardProposals.Forge {
    private final ForgeAddress address;
    private final String token;
    private final HttpClient client;
    private final URI api;
    private final boolean github;
    private final String repository;

    ForgeCardProposals(ForgeAddress address, String token, HttpClient client) {
        this(
                address,
                token,
                client,
                "github.com".equalsIgnoreCase(address.forge().getHost())
                        ? URI.create("https://api.github.com/")
                        : address.forge().resolve("/api/v1/"));
    }

    ForgeCardProposals(ForgeAddress address, String token, HttpClient client, URI api) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER
                || !token.matches("[\\x21-\\x7e]+")
                || api.getUserInfo() != null
                || api.getQuery() != null
                || api.getFragment() != null
                || !("https".equals(api.getScheme())
                        || "http".equals(api.getScheme())
                                && ("127.0.0.1".equals(api.getHost())
                                        || "[::1]".equals(api.getHost())))) {
            throw new Refused(
                    Refused.USAGE,
                    "the forge needs a usable token and an HTTPS client that follows no redirects",
                    null);
        }
        this.address = address;
        this.token = token;
        this.client = client;
        this.api = api;
        github = "github.com".equalsIgnoreCase(address.forge().getHost());
        repository = "repos/" + address.organization() + "/" + address.repository();
    }

    @Override
    public String login() {
        Object value = request("GET", "user", null, 200).body();
        if (value instanceof Map<?, ?> fields
                && fields.get("login") instanceof String login
                && PersonCard.isLogin(login)) return login;
        throw failed("the forge returned no usable login; check the lent token");
    }

    @Override
    public List<Pull> list() {
        return call(
                () -> {
                    final List<Pull> result = new ArrayList<>();
                    for (int page = 1; page <= 100; page++) {
                        boolean more;
                        if (github) {
                            var listed =
                                    new GitHubClient(api, client)
                                            .pulls(
                                                    token,
                                                    address.organization(),
                                                    address.repository(),
                                                    "open",
                                                    page,
                                                    50);
                            for (var pull : listed.items())
                                result.add(read(required(pull.number(), "number")));
                            more = listed.more();
                        } else {
                            var listed =
                                    new ForgejoClient(api, client)
                                            .pulls(
                                                    token,
                                                    address.organization(),
                                                    address.repository(),
                                                    "open",
                                                    page,
                                                    50);
                            for (var pull : listed.items())
                                result.add(read(required(pull.number(), "number")));
                            more = listed.more();
                        }
                        if (!more) return List.copyOf(result);
                    }
                    throw failed(
                            "too many pull requests; narrow the open list on the forge and read again");
                });
    }

    @Override
    public Pull read(long number) {
        if (number < 1) throw new Refused(Refused.USAGE, "expected a pull request number", null);
        return call(
                () ->
                        github
                                ? pull(
                                        new GitHubClient(api, client)
                                                .pull(
                                                        token,
                                                        address.organization(),
                                                        address.repository(),
                                                        number))
                                : pull(
                                        new ForgejoClient(api, client)
                                                .pull(
                                                        token,
                                                        address.organization(),
                                                        address.repository(),
                                                        number)));
    }

    /**
     * Names the fork's git address: the organization's own, its owner segment, the one right before
     * the repository, given to the login. A host that bears the organization's name stays.
     *
     * @param url The organization repository's git address.
     * @param organization Its owner.
     * @param login The fork's owner.
     * @return The fork's git address.
     */
    static String forkUrl(String url, String organization, String login) {
        for (String before : new String[] {"/", ":"}) {
            final String owner = before + organization + "/";
            final int at = url.lastIndexOf(owner);
            if (at >= 0 && url.indexOf('/', at + owner.length()) < 0) {
                return url.substring(0, at)
                        + before
                        + login
                        + "/"
                        + url.substring(at + owner.length());
            }
        }
        throw failed("cannot name the fork of " + organization + "'s repository");
    }

    @Override
    public String fork(String login) {
        if (!PersonCard.isLogin(login))
            throw new Refused(Refused.USAGE, "expected a forge login", null);
        request("POST", repository + "/forks", Map.of(), 200, 201, 202, 409, 422);
        for (int attempt = 0; attempt < 30; attempt++) {
            final Reply reply =
                    request("GET", "repos/" + login + "/" + address.repository(), null, 200, 404);
            if (reply.status() == 200) {
                return forkUrl(address.url(), address.organization(), login);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw failed("fork waiting was interrupted; run porelo org join again");
            }
        }
        throw failed("the fork is not readable yet; run porelo org join again");
    }

    @Override
    public Pull open(String login, boolean fork) {
        if (!PersonCard.isLogin(login))
            throw new Refused(Refused.USAGE, "expected a forge login", null);
        String head = (fork ? login + ":" : "") + "porelo-card-" + login;
        String title = "Proposed card for " + login;
        String body =
                "This person's signed first card grants nothing until an organization admin accepts its unchanged commit.";
        return call(
                () ->
                        github
                                ? pull(
                                        new GitHubClient(api, client)
                                                .createPull(
                                                        token,
                                                        address.organization(),
                                                        address.repository(),
                                                        new PullsCreateRequest(
                                                                title, head, null, "main", body,
                                                                false, false, null)))
                                : pull(
                                        new ForgejoClient(api, client)
                                                .createPull(
                                                        token,
                                                        address.organization(),
                                                        address.repository(),
                                                        new CreatePullRequestOption(
                                                                null, null, "main", body, null,
                                                                head, null, null, title))));
    }

    @Override
    public void close(long number, String comment) {
        if (number < 1) throw new Refused(Refused.USAGE, "expected a pull request number", null);
        request(
                "POST",
                repository + "/issues/" + number + "/comments",
                Map.of("body", comment),
                200,
                201);
        request("PATCH", repository + "/pulls/" + number, Map.of("state", "closed"), 200);
    }

    private Pull pull(org.fuin.porelo.forge.github.model.PullRequest pull) {
        return new Pull(
                pull.number(),
                url(String.valueOf(pull.htmlUrl())),
                pull.user().login(),
                pull.head().ref(),
                pull.base().ref(),
                pull.head().sha(),
                String.valueOf(pull.createdAt()),
                String.valueOf(pull.state()).equalsIgnoreCase("open"),
                Boolean.TRUE.equals(pull.merged()),
                pull.head().repo() == null
                        ? ""
                        : required(pull.head().repo().owner().login(), "source owner"));
    }

    private Pull pull(org.fuin.porelo.forge.forgejo.model.PullRequest pull) {
        final var head = required(pull.head(), "head");
        return new Pull(
                required(pull.number(), "number"),
                url(required(pull.htmlUrl(), "URL")),
                required(required(pull.user(), "user").login(), "login"),
                required(head.ref(), "branch"),
                required(required(pull.base(), "base").ref(), "base branch"),
                required(head.sha(), "commit"),
                required(pull.createdAt(), "opening time").toString(),
                "open".equals(pull.state()),
                Boolean.TRUE.equals(pull.merged()),
                head.repo() == null
                        ? ""
                        : required(
                                required(head.repo().owner(), "source owner").login(),
                                "source login"));
    }

    private static <T> T required(@Nullable T value, String field) {
        return java.util.Objects.requireNonNull(value, "the forge response must hold " + field);
    }

    private String url(String text) {
        URI uri = URI.create(text);
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || text.contains(token)) {
            throw failed("the forge returned no usable pull request URL; read it on the forge");
        }
        return text;
    }

    private <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (GitHubException | ForgejoException ex) {
            throw failed(
                    "the forge refused the pull request operation or could not answer; check the token and read the forge");
        } catch (Refused ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw failed("the forge returned an unusable pull request; read it on the forge");
        }
    }

    private record Reply(int status, @Nullable Object body) {}

    private Reply request(String method, String path, @Nullable Object body, int... expected) {
        try {
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(api.resolve(path))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "token " + token)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json");
            request.method(
                    method,
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(Json.write(body)));
            HttpResponse<String> response =
                    client.send(
                            request.build(),
                            HttpResponse.BodyHandlers.limiting(
                                    HttpResponse.BodyHandlers.ofString(), 1024 * 1024));
            if (java.util.Arrays.stream(expected)
                    .noneMatch(status -> status == response.statusCode())) {
                throw failed(
                        "the forge answered HTTP "
                                + response.statusCode()
                                + "; verify that your token can read the organization and fork it");
            }
            return new Reply(
                    response.statusCode(),
                    response.body().isBlank() ? null : Json.parseExact(response.body()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failed("the forge request was interrupted; read it again");
        } catch (IOException | IllegalArgumentException ex) {
            throw failed("the forge did not answer usable JSON; check access and read it again");
        }
    }

    private static Refused failed(String why) {
        return new Refused(Refused.CANNOT_SAY, why, null);
    }
}
