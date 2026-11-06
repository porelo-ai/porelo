package org.fuin.porelo.core.project.file;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.nodes.Node;

/** Binds a node tree onto the file models, one method per type, each knowing its own keys. */
final class FileBinder {

    private FileBinder() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Binds a project file.
     *
     * @param b The reading.
     * @param root The file's root.
     * @return The model; complete only when the reading recorded no fault.
     */
    static ProjectFile project(final Binding b, final Node root) {
        final Map<String, Node> top = top(b, root);
        final Map<String, Parameter> parameters = new LinkedHashMap<>();
        b.named(top.get("parameters"), "parameters")
                .forEach(
                        (name, node) -> {
                            final Parameter parameter =
                                    parameter(b, node, Binding.join("parameters", name));
                            if (parameter != null) {
                                parameters.put(name, parameter);
                            }
                        });
        final Map<String, String> credentials = new LinkedHashMap<>();
        b.named(top.get("credentials"), "credentials")
                .forEach(
                        (name, node) -> {
                            final String destination =
                                    b.text(node, Binding.join("credentials", name));
                            credentials.put(name, destination == null ? "" : destination);
                        });
        final Map<String, RepositoryEntry> repositories = new LinkedHashMap<>();
        b.named(top.get("repositories"), "repositories")
                .forEach(
                        (name, node) -> {
                            final RepositoryEntry entry =
                                    repository(b, node, Binding.join("repositories", name));
                            if (entry != null) {
                                repositories.put(name, entry);
                            }
                        });
        return new ProjectFile(
                projectInfo(b, top.get("project")),
                image(b, top.get("image"), "image"),
                build(b, top.get("build")),
                egress(b, top.get("egress")),
                limits(b, top.get("limits")),
                mail(b, top.get("mail")),
                builds(b, top.get("builds")),
                parameters,
                branch(b, top.get("branch"), "branch"),
                credentials,
                repositories,
                b.word(top.get("base_branch"), "base_branch"));
    }

    /**
     * Binds a repository's file.
     *
     * @param b The reading.
     * @param root The file's root.
     * @return The model; complete only when the reading recorded no fault.
     */
    static RepositoryFile repository(final Binding b, final Node root) {
        return new RepositoryFile(image(b, top(b, root).get("image"), "image"));
    }

    private static Map<String, Node> top(final Binding b, final Node root) {
        final Map<String, Node> top = b.section(root, "", Sections.TOP);
        return top == null ? Map.of() : top;
    }

    private static @Nullable ProjectInfo projectInfo(final Binding b, final @Nullable Node node) {
        final Map<String, Node> s = b.section(node, "project", "project");
        if (s == null) {
            return null;
        }
        return new ProjectInfo(
                b.word(s.get("name"), "project.name"),
                b.text(s.get("description"), "project.description"),
                b.text(s.get("security_class"), "project.security_class"),
                // 'central' names the address; 'upstream' is its old name, read alike until
                // renamed.
                named(
                        b.text(s.get("central"), "project.central"),
                        b.text(s.get("upstream"), "project.upstream")),
                b.text(s.get("review"), "project.review"),
                b.word(s.get("nickname"), "project.nickname"));
    }

    private static @Nullable String named(
            final @Nullable String central, final @Nullable String upstream) {
        return central != null ? central : upstream;
    }

    private static @Nullable RepositoryEntry repository(
            final Binding b, final Node node, final String path) {
        final Map<String, Node> s = b.entry(node, path, "repositories.<name>");
        if (s == null) {
            return null;
        }
        final List<String> issues = b.list(s.get("issues"), path + ".issues");
        final String central = b.text(s.get("central"), path + ".central");
        final String upstream = b.text(s.get("upstream"), path + ".upstream");
        // A row naming 'central' alone sends work there. A file not yet renamed keeps what it said:
        // its 'upstream' is where work goes, and never a central one, so a host places it as
        // before.
        return new RepositoryEntry(
                upstream != null ? upstream : central,
                central,
                b.text(s.get("review"), path + ".review"),
                b.text(s.get("description"), path + ".description"),
                issues == null ? List.of() : issues,
                b.word(s.get("agent"), path + ".agent"),
                b.word(s.get("nickname"), path + ".nickname"),
                b.text(s.get("maturity"), path + ".maturity"),
                branch(b, s.get("branch"), path + ".branch"));
    }

    private static @Nullable Image image(
            final Binding b, final @Nullable Node node, final String path) {
        final Map<String, Node> s = b.section(node, path, "image");
        if (s == null) {
            return null;
        }
        final Map<String, String> env = new LinkedHashMap<>();
        b.named(s.get("env"), path + ".env")
                .forEach(
                        (name, value) -> {
                            final String text = b.text(value, Binding.join(path + ".env", name));
                            env.put(name, text == null ? "" : text);
                        });
        return new Image(
                b.text(s.get("from"), path + ".from"),
                env,
                b.text(s.get("run"), path + ".run"),
                b.text(s.get("run_file"), path + ".run_file"));
    }

    private static @Nullable BuildSources build(final Binding b, final @Nullable Node node) {
        final Map<String, Node> s = b.section(node, "build", "build");
        return s == null
                ? null
                : new BuildSources(b.list(s.get("package_sources"), "build.package_sources"));
    }

    private static @Nullable Egress egress(final Binding b, final @Nullable Node node) {
        final Map<String, Node> s = b.section(node, "egress", "egress");
        if (s == null) {
            return null;
        }
        return new Egress(
                orEmpty(b.names(s.get("sets"), "egress.sets")),
                orEmpty(b.names(s.get("domains"), "egress.domains")),
                orEmpty(b.names(s.get("host_services"), "egress.host_services")),
                orEmpty(b.names(s.get("refused"), "egress.refused")));
    }

    private static @Nullable Limits limits(final Binding b, final @Nullable Node node) {
        final Map<String, Node> s = b.section(node, "limits", "limits");
        if (s == null) {
            return null;
        }
        return new Limits(
                b.text(s.get("memory"), "limits.memory"),
                b.number(s.get("pids"), "limits.pids"),
                b.text(s.get("cpus"), "limits.cpus"),
                b.text(s.get("hand_in"), "limits.hand_in"),
                b.number(s.get("subagents"), "limits.subagents"));
    }

    private static @Nullable Mail mail(final Binding b, final @Nullable Node node) {
        final Map<String, Node> s = b.section(node, "mail", "mail");
        if (s == null) {
            return null;
        }
        final Map<String, Mail.Peer> peers = new LinkedHashMap<>();
        b.named(s.get("peers"), "mail.peers")
                .forEach(
                        (name, value) -> {
                            final String path = Binding.join("mail.peers", name);
                            final Map<String, Node> peer =
                                    b.entry(value, path, "mail.peers.<name>");
                            if (peer != null) {
                                peers.put(
                                        name,
                                        new Mail.Peer(
                                                b.text(peer.get("address"), path + ".address"),
                                                b.text(peer.get("trust"), path + ".trust"),
                                                b.number(peer.get("per_day"), path + ".per_day"),
                                                b.text(peer.get("mode"), path + ".mode")));
                            }
                        });
        final Map<String, Node> rules = b.section(s.get("rules"), "mail.rules", "mail.rules");
        final Map<String, Mail.Transport> transports = new LinkedHashMap<>();
        b.named(s.get("transports"), "mail.transports")
                .forEach(
                        (scheme, value) -> {
                            final String path = Binding.join("mail.transports", scheme);
                            final Map<String, String> settings = new LinkedHashMap<>();
                            b.named(value, path)
                                    .forEach(
                                            (key, setting) -> {
                                                final String text =
                                                        b.text(setting, Binding.join(path, key));
                                                settings.put(key, text == null ? "" : text);
                                            });
                            transports.put(scheme, new Mail.Transport(settings));
                        });
        return new Mail(
                peers,
                rules == null
                        ? null
                        : new Mail.Rules(
                                b.text(rules.get("project"), "mail.rules.project"),
                                b.text(rules.get("room"), "mail.rules.room"),
                                b.text(rules.get("others"), "mail.rules.others")),
                b.text(s.get("outgoing_filter"), "mail.outgoing_filter"),
                transports);
    }

    private static @Nullable Builds builds(final Binding b, final @Nullable Node node) {
        final Map<String, Node> s = b.section(node, "builds", "builds");
        if (s == null) {
            return null;
        }
        return new Builds(
                b.text(s.get("forge"), "builds.forge"),
                b.text(s.get("credential"), "builds.credential"),
                b.text(s.get("api"), "builds.api"),
                b.text(s.get("logs"), "builds.logs"));
    }

    private static @Nullable Parameter parameter(
            final Binding b, final Node node, final String path) {
        final Map<String, Node> s = b.entry(node, path, "parameters.<name>");
        if (s == null) {
            return null;
        }
        return new Parameter(
                b.text(s.get("description"), path + ".description"),
                b.list(s.get("values"), path + ".values"),
                b.text(s.get("pattern"), path + ".pattern"),
                b.text(s.get("default"), path + ".default"),
                b.bool(s.get("required"), path + ".required"),
                b.text(s.get("env"), path + ".env"));
    }

    private static @Nullable BranchScheme branch(
            final Binding b, final @Nullable Node node, final String path) {
        final Map<String, Node> s = b.section(node, path, "branch");
        if (s == null) {
            return null;
        }
        return new BranchScheme(
                b.text(s.get("template"), path + ".template"),
                b.text(s.get("pattern"), path + ".pattern"));
    }

    private static List<String> orEmpty(final @Nullable List<String> list) {
        return list == null ? List.of() : list;
    }
}
