package org.fuin.porelo.core.project.file;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Turns the file models into the maps SnakeYAML writes, in the order the reader knows the keys,
 * leaving out whatever the model leaves out.
 */
final class FileYaml {

    private FileYaml() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Writes a tree of maps, lists and values as block YAML.
     *
     * @param tree The tree; only maps, lists, strings, integers and booleans.
     * @return The text.
     */
    static String dump(final Map<String, Object> tree) {
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        options.setSplitLines(false);
        options.setWidth(Integer.MAX_VALUE);
        return new Yaml(new Representer(options), options).dump(tree);
    }

    /**
     * Refuses a model the check finds a fault in, since only a usable file is written.
     *
     * @param faults What the check found.
     * @throws ProjectFileException When it found anything.
     */
    static void refuse(final List<Fault> faults) {
        if (!faults.isEmpty()) {
            throw new ProjectFileException(
                    faults.stream().map(fault -> "not written: " + fault.message()).toList());
        }
    }

    static Map<String, Object> project(final ProjectFile file) {
        final Map<String, Object> top = new LinkedHashMap<>();
        final ProjectInfo info = file.project();
        if (info != null) {
            final Map<String, Object> project = new LinkedHashMap<>();
            put(project, "name", info.name());
            put(project, "description", info.description());
            put(project, "security_class", info.securityClass());
            put(project, "upstream", info.upstream());
            put(project, "review", info.review());
            put(project, "nickname", info.nickname());
            top.put("project", project);
        }
        final Map<String, Object> repositories = new LinkedHashMap<>();
        file.repositories().forEach((name, entry) -> repositories.put(name, repository(entry)));
        put(top, "repositories", repositories);
        if (file.image() != null) {
            top.put("image", image(file.image()));
        }
        if (file.build() != null) {
            final Map<String, Object> build = new LinkedHashMap<>();
            put(build, "package_sources", file.build().packageSources());
            top.put("build", build);
        }
        if (file.egress() != null) {
            final Map<String, Object> egress = new LinkedHashMap<>();
            put(egress, "sets", file.egress().sets());
            put(egress, "domains", file.egress().domains());
            put(egress, "host_services", file.egress().hostServices());
            put(egress, "refused", file.egress().refused());
            top.put("egress", egress);
        }
        if (file.limits() != null) {
            final Map<String, Object> limits = new LinkedHashMap<>();
            put(limits, "memory", file.limits().memory());
            put(limits, "pids", file.limits().pids());
            put(limits, "cpus", file.limits().cpus());
            put(limits, "hand_in", file.limits().handIn());
            put(limits, "subagents", file.limits().subagents());
            top.put("limits", limits);
        }
        if (file.mail() != null) {
            top.put("mail", mail(file.mail()));
        }
        put(top, "credentials", file.credentials());
        if (file.builds() != null) {
            final Map<String, Object> builds = new LinkedHashMap<>();
            put(builds, "forge", file.builds().forge());
            put(builds, "credential", file.builds().credential());
            put(builds, "api", file.builds().api());
            put(builds, "logs", file.builds().logs());
            top.put("builds", builds);
        }
        final Map<String, Object> parameters = new LinkedHashMap<>();
        file.parameters().forEach((name, parameter) -> parameters.put(name, parameter(parameter)));
        put(top, "parameters", parameters);
        if (file.branch() != null) {
            top.put("branch", branch(file.branch()));
        }
        return top;
    }

    static Map<String, Object> repository(final RepositoryFile file) {
        final Map<String, Object> top = new LinkedHashMap<>();
        if (file.image() != null) {
            top.put("image", image(file.image()));
        }
        return top;
    }

    private static Map<String, Object> repository(final RepositoryEntry entry) {
        final Map<String, Object> repository = new LinkedHashMap<>();
        put(repository, "upstream", entry.upstream());
        put(repository, "central", entry.central());
        put(repository, "review", entry.review());
        put(repository, "description", entry.description());
        put(repository, "issues", entry.issues());
        put(repository, "agent", entry.agent());
        put(repository, "nickname", entry.nickname());
        put(repository, "maturity", entry.maturity());
        if (entry.branch() != null) {
            repository.put("branch", branch(entry.branch()));
        }
        return repository;
    }

    private static Map<String, Object> image(final Image image) {
        final Map<String, Object> written = new LinkedHashMap<>();
        put(written, "from", image.from());
        put(written, "env", image.env());
        put(written, "run", image.run());
        put(written, "run_file", image.runFile());
        return written;
    }

    private static Map<String, Object> mail(final Mail mail) {
        final Map<String, Object> written = new LinkedHashMap<>();
        final Map<String, Object> peers = new LinkedHashMap<>();
        mail.peers()
                .forEach(
                        (name, peer) -> {
                            final Map<String, Object> each = new LinkedHashMap<>();
                            put(each, "address", peer.address());
                            put(each, "trust", peer.trust());
                            put(each, "per_day", peer.perDay());
                            put(each, "mode", peer.mode());
                            peers.put(name, each);
                        });
        put(written, "peers", peers);
        if (mail.rules() != null) {
            final Map<String, Object> rules = new LinkedHashMap<>();
            put(rules, "project", mail.rules().project());
            put(rules, "room", mail.rules().room());
            put(rules, "others", mail.rules().others());
            written.put("rules", rules);
        }
        put(written, "outgoing_filter", mail.outgoingFilter());
        final Map<String, Object> transports = new LinkedHashMap<>();
        mail.transports()
                .forEach(
                        (scheme, transport) ->
                                transports.put(scheme, new LinkedHashMap<>(transport.settings())));
        put(written, "transports", transports);
        return written;
    }

    private static Map<String, Object> parameter(final Parameter parameter) {
        final Map<String, Object> written = new LinkedHashMap<>();
        put(written, "description", parameter.description());
        if (parameter.values() != null) {
            written.put("values", parameter.values());
        }
        put(written, "pattern", parameter.pattern());
        put(written, "default", parameter.defaultValue());
        put(written, "required", parameter.required());
        put(written, "env", parameter.env());
        return written;
    }

    private static Map<String, Object> branch(final BranchScheme branch) {
        final Map<String, Object> written = new LinkedHashMap<>();
        put(written, "template", branch.template());
        put(written, "pattern", branch.pattern());
        return written;
    }

    /** Puts a value the model holds; an absent one, or an empty list or map, is not written. */
    private static void put(
            final Map<String, Object> map, final String key, final @Nullable Object value) {
        if (value == null
                || value instanceof List<?> list && list.isEmpty()
                || value instanceof Map<?, ?> each && each.isEmpty()) {
            return;
        }
        map.put(key, value instanceof Map<?, ?> each ? new LinkedHashMap<>(each) : value);
    }
}
