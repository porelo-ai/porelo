package org.fuin.porelo.host;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/** A credential-free central address, normalized exactly once before a token can be sent. */
record ProjectAddress(String origin, String owner, String repository, String https, String ssh) {
    private static final String NAME = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}";
    private static final String HOST = "[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?";

    static ProjectAddress parse(String value) {
        final var scp =
                Pattern.compile("git@(" + HOST + "):(" + NAME + ")/(" + NAME + ")").matcher(value);
        final String server;
        final String owner;
        String repository;
        final String origin;
        final int port;
        if (scp.matches()) {
            server = scp.group(1);
            owner = scp.group(2);
            repository = scp.group(3);
            origin = "https://" + server;
            port = -1;
        } else {
            try {
                final URI parsed = new URI(value);
                final var path =
                        Pattern.compile("/(" + NAME + ")/(" + NAME + ")/?")
                                .matcher(parsed.getRawPath() == null ? "" : parsed.getRawPath());
                if (!("https".equals(parsed.getScheme()) || "ssh".equals(parsed.getScheme()))
                        || parsed.getHost() == null
                        || !parsed.getHost().matches(HOST)
                        || !path.matches()
                        || parsed.getRawQuery() != null
                        || parsed.getRawFragment() != null
                        || !("ssh".equals(parsed.getScheme())
                                ? "git".equals(parsed.getUserInfo())
                                : parsed.getUserInfo() == null)
                        || parsed.getPort() == 0
                        || parsed.getPort() > 65535) {
                    throw refusedAddress();
                }
                server = parsed.getHost().toLowerCase(java.util.Locale.ROOT);
                owner = path.group(1);
                repository = path.group(2);
                port = "ssh".equals(parsed.getScheme()) ? parsed.getPort() : -1;
                origin =
                        "https://"
                                + ("https".equals(parsed.getScheme())
                                        ? parsed.getRawAuthority()
                                        : server);
            } catch (URISyntaxException ex) {
                throw refusedAddress();
            }
        }
        if (repository.endsWith(".git")) {
            repository = repository.substring(0, repository.length() - 4);
        }
        if (repository.isEmpty()
                || repository.equals(".")
                || repository.equals("..")
                || repository.equalsIgnoreCase("repositories.json")
                || owner.equals(".")
                || owner.equals("..")) {
            throw new HostRefusal(65, "the project names no forge repository");
        }
        return new ProjectAddress(
                origin,
                owner,
                repository,
                origin + "/" + owner + "/" + repository + ".git",
                "ssh://git@"
                        + server
                        + (port > 0 ? ":" + port : "")
                        + "/"
                        + owner
                        + "/"
                        + repository
                        + ".git");
    }

    private static HostRefusal refusedAddress() {
        return new HostRefusal(65, "the project names no credential-free forge repository address");
    }
}
