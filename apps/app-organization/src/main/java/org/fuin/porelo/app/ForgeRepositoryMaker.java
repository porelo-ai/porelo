package org.fuin.porelo.app;

import java.net.URI;
import java.net.http.HttpClient;
import org.fuin.porelo.forge.forgejo.ForgejoClient;
import org.fuin.porelo.forge.forgejo.ForgejoException;
import org.fuin.porelo.forge.forgejo.model.CreateRepoOption;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.model.ReposCreateInOrgRequest;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;

/**
 * Makes a project's repository on the forge, the one place Porelo depends on the forge clients. It
 * is made private and empty: the first signed commit is Porelo's, and an {@code auto_init} commit
 * nobody signed would stand before it.
 */
final class ForgeRepositoryMaker {

    private static final URI GITHUB_API = URI.create("https://api.github.com/");

    private static final String GITHUB = "github.com";

    private final URI github;

    private final HttpClient http;

    ForgeRepositoryMaker(final HttpClient http) {
        this(GITHUB_API, http);
    }

    ForgeRepositoryMaker(final URI github, final HttpClient http) {
        this.github = github;
        this.http = http;
    }

    /**
     * Makes the repository.
     *
     * @param forge The forge, {@code https://host}.
     * @param token The person's token, sent to that forge only.
     * @param organisation The owner on the forge.
     * @param name The repository's name.
     * @throws Refused When the forge refused, by what it refused; what it said is not repeated,
     *     since a forge's words may echo what it was sent.
     */
    void make(final URI forge, final String token, final String organisation, final String name) {
        try {
            if (GITHUB.equals(forge.getHost())) {
                new GitHubClient(github, http).createRepository(token, organisation, github(name));
            } else {
                new ForgejoClient(forge.resolve("/api/v1/"), http)
                        .createRepository(token, organisation, forgejo(name));
            }
        } catch (GitHubException ex) {
            // Taken and not decoded: made, as the first commit that follows will see for itself.
            if (ex.delivery() != org.fuin.porelo.forge.github.Delivery.ANSWERED
                    || ex.status() != 0) {
                throw refused(ex.status());
            }
        } catch (ForgejoException ex) {
            if (ex.delivery() != org.fuin.porelo.forge.forgejo.Delivery.ANSWERED
                    || ex.status() != 0) {
                throw refused(ex.status());
            }
        }
    }

    private static Refused refused(final int status) {
        return switch (status) {
            case 401, 403 ->
                    new Refused(
                            Refused.NOT_ALLOWED,
                            "the forge does not let your token make a repository there",
                            null);
            case 409, 422 ->
                    new Refused(
                            Refused.EXISTS,
                            "the forge holds a repository of that name that you cannot read",
                            null);
            default ->
                    new Refused(
                            Refused.CANNOT_SAY,
                            status == 0
                                    ? "the forge did not answer, and may have made the repository:"
                                            + " ask again with the same --id"
                                    : "the forge did not make the repository: it answered "
                                            + status,
                            null);
        };
    }

    private static ReposCreateInOrgRequest github(final String name) {
        return new ReposCreateInOrgRequest(
                name, null, null, true, null, null, null, null, null, null, null, false, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static CreateRepoOption forgejo(final String name) {
        return new CreateRepoOption(
                false, "main", null, null, null, null, name, null, true, null, null, null);
    }
}
