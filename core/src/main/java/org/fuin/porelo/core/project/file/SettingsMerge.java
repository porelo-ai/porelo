package org.fuin.porelo.core.project.file;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.core.project.ProjectException;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Merges a project's settings with a repository's own, on the YAML tree before it is bound, so the
 * project file's checks judge what results.
 *
 * <p>Each later layer adds to what came before: mappings merge key by key, a list takes the earlier
 * entries and then the later ones, and a single value the later layer sets wins. {@code null}
 * removes a key set before; in a list a quoted {@code "!entry"} removes that inherited entry and
 * {@code "!*"} every inherited one. An unquoted {@code !} is a YAML tag, not a removal, and is
 * refused with the quotes it needs. A later layer is judged by these rules wherever it writes, also
 * under a key no earlier layer set.
 */
final class SettingsMerge {

    private static final String REMOVE = "!";
    private static final String EVERY = "!*";

    private SettingsMerge() {}

    /**
     * One source of settings.
     *
     * @param source What it is called where a start names a value's source.
     * @param root Its tree, or {@code null} for an empty file.
     */
    record Layer(String source, @Nullable Node root) {}

    /**
     * Something a later layer took away.
     *
     * @param path The key, or a list entry as {@code key[entry]}.
     * @param by The source that removed it.
     */
    record Removed(String path, String by) {}

    /**
     * What the layers make together.
     *
     * @param root The merged tree.
     * @param sources Each value's path to the source that set it, in the order they were set.
     * @param removed What was taken away and is not there again, in order.
     */
    record Merged(Node root, Map<String, String> sources, List<Removed> removed) {}

    /**
     * Merges layers, the first the project's.
     *
     * @param layers The sources, earliest first.
     * @return What they make together.
     * @throws ProjectException Naming every fault: a key written twice, an alias, a root that is no
     *     mapping, a null list entry, and a removal written without quotes or matching nothing.
     */
    static Merged merge(final List<Layer> layers) {
        final State state = new State();
        MappingNode result = null;
        for (final Layer layer : layers) {
            final Node root = layer.root();
            if (root == null || isNull(root)) {
                continue;
            }
            if (!(root instanceof MappingNode mapping)) {
                state.fault(layer.source(), root, "the file is no mapping of keys");
                continue;
            }
            if (!state.wellFormed(layer.source(), mapping)) {
                continue;
            }
            if (result == null) {
                state.mark(mapping, "", layer.source());
                result = mapping;
            } else {
                result = state.mapping(result, mapping, "", layer.source());
            }
        }
        if (!state.faults.isEmpty()) {
            throw new ProjectException(String.join("; ", state.faults));
        }
        return new Merged(
                result == null ? emptyMapping() : result,
                Collections.unmodifiableMap(state.sources),
                List.copyOf(state.removed));
    }

    private static final class State {

        private final Map<String, String> sources = new LinkedHashMap<>();
        private final List<Removed> removed = new ArrayList<>();
        private final List<String> faults = new ArrayList<>();

        /** Refuses what compose lets through and the merge would hide: two keys, an alias. */
        private boolean wellFormed(final String by, final MappingNode root) {
            final int before = faults.size();
            walk(by, root, "", Collections.newSetFromMap(new IdentityHashMap<>()));
            return faults.size() == before;
        }

        private void walk(
                final String by, final Node node, final String path, final Set<Node> seen) {
            if (!seen.add(node)) {
                fault(by, node, path + " repeats a node by an alias; write each value out");
                return;
            }
            switch (node) {
                case MappingNode map -> {
                    final Set<String> keys = new HashSet<>();
                    for (final NodeTuple tuple : map.getValue()) {
                        final String at = Binding.join(path, key(tuple));
                        if (!keys.add(key(tuple))) {
                            fault(by, tuple.getKeyNode(), "'" + at + "' is written twice");
                        }
                        walk(by, tuple.getValueNode(), at, seen);
                    }
                }
                case SequenceNode list -> {
                    for (int at = 0; at < list.getValue().size(); at++) {
                        walk(by, list.getValue().get(at), path + "[" + at + "]", seen);
                    }
                }
                default -> {
                    // A scalar holds nothing further.
                }
            }
        }

        private Node merge(
                final Node earlier, final Node later, final String path, final String by) {
            if (later instanceof MappingNode added) {
                if (!(earlier instanceof MappingNode)) {
                    forget(path);
                }
                return mapping(
                        earlier instanceof MappingNode map ? map : emptyMapping(), added, path, by);
            }
            if (later instanceof SequenceNode added) {
                if (!(earlier instanceof SequenceNode)) {
                    forget(path);
                }
                return sequence(
                        earlier instanceof SequenceNode list ? list : emptySequence(),
                        added,
                        path,
                        by);
            }
            forget(path);
            sources.put(path, by);
            restored(path);
            return later;
        }

        private MappingNode mapping(
                final MappingNode earlier,
                final MappingNode later,
                final String path,
                final String by) {
            final Map<String, NodeTuple> entries = new LinkedHashMap<>();
            for (final NodeTuple tuple : earlier.getValue()) {
                entries.put(key(tuple), tuple);
            }
            for (final NodeTuple tuple : later.getValue()) {
                final String key = key(tuple);
                final String at = Binding.join(path, key);
                final Node value = tuple.getValueNode();
                final NodeTuple before = entries.get(key);
                if (isNull(value)) {
                    if (before != null) {
                        entries.remove(key);
                        forget(at);
                        removed.add(new Removed(at, by));
                    }
                } else {
                    // The later key's node, so a fault the binder finds names the line it is on.
                    entries.put(
                            key,
                            new NodeTuple(
                                    tuple.getKeyNode(),
                                    before == null
                                            ? merge(emptyLike(value), value, at, by)
                                            : merge(before.getValueNode(), value, at, by)));
                }
            }
            return new MappingNode(
                    Tag.MAP, new ArrayList<>(entries.values()), earlier.getFlowStyle());
        }

        private SequenceNode sequence(
                final SequenceNode earlier,
                final SequenceNode later,
                final String path,
                final String by) {
            // Inherited entries keep the paths their sources were recorded under until the list
            // is whole again; removals go first, so an entry this layer adds is never one of them.
            final List<Node> inherited = new ArrayList<>(earlier.getValue());
            final List<String> inheritedPaths = new ArrayList<>();
            for (int at = 0; at < inherited.size(); at++) {
                inheritedPaths.add(entryPath(path, inherited.get(at), at));
            }
            final List<Node> additions = new ArrayList<>();
            for (final Node entry : later.getValue()) {
                if (isTagged(entry)) {
                    fault(
                            by,
                            entry,
                            path
                                    + " holds an entry YAML reads as the tag '"
                                    + entry.getTag().getValue()
                                    + "'; a removal is written in quotes, as \""
                                    + entry.getTag().getValue()
                                    + "\"");
                } else if (isNull(entry)) {
                    fault(
                            by,
                            entry,
                            path + " holds an empty entry; a removal is written \"!<entry>\"");
                } else if (entry instanceof ScalarNode scalar
                        && isQuoted(scalar)
                        && scalar.getValue().startsWith(REMOVE)) {
                    remove(inherited, inheritedPaths, scalar, path, by);
                } else {
                    additions.add(entry);
                }
            }
            final List<Node> all = new ArrayList<>();
            for (int at = 0; at < inherited.size(); at++) {
                final String now = entryPath(path, inherited.get(at), at);
                rename(inheritedPaths.get(at), now);
                all.add(inherited.get(at));
            }
            for (final Node entry : additions) {
                final String at = entryPath(path, entry, all.size());
                all.add(merge(emptyLike(entry), entry, at, by));
            }
            return new SequenceNode(Tag.SEQ, all, earlier.getFlowStyle());
        }

        private void remove(
                final List<Node> inherited,
                final List<String> inheritedPaths,
                final ScalarNode removal,
                final String path,
                final String by) {
            final boolean every = EVERY.equals(removal.getValue());
            final String which = removal.getValue().substring(REMOVE.length());
            final List<Integer> matching = new ArrayList<>();
            for (int at = 0; at < inherited.size(); at++) {
                if (every
                        || inherited.get(at) instanceof ScalarNode scalar
                                && scalar.getValue().equals(which)) {
                    matching.add(at);
                    forget(inheritedPaths.get(at));
                    removed.add(new Removed(inheritedPaths.get(at), by));
                }
            }
            for (int at = matching.size() - 1; at >= 0; at--) {
                inherited.remove((int) matching.get(at));
                inheritedPaths.remove((int) matching.get(at));
            }
            final boolean matched = !matching.isEmpty();
            if (!matched && !every) {
                fault(
                        by,
                        removal,
                        "\""
                                + removal.getValue()
                                + "\" removes '"
                                + which
                                + "', which "
                                + path
                                + " does not hold before it");
            }
        }

        /** Notes the source of every value under a node of the first layer. */
        private void mark(final Node node, final String path, final String by) {
            switch (node) {
                case MappingNode map -> {
                    for (final NodeTuple tuple : map.getValue()) {
                        mark(tuple.getValueNode(), Binding.join(path, key(tuple)), by);
                    }
                }
                case SequenceNode list -> {
                    for (int at = 0; at < list.getValue().size(); at++) {
                        final Node entry = list.getValue().get(at);
                        mark(entry, entryPath(path, entry, at), by);
                    }
                }
                default -> sources.put(path, by);
            }
        }

        /** Drops what is known of a path and everything under it. */
        private void forget(final String path) {
            sources.keySet().removeIf(each -> under(each, path));
        }

        /** Moves the sources recorded under one path to another, as a list closes up. */
        private void rename(final String from, final String to) {
            if (from.equals(to)) {
                return;
            }
            final Map<String, String> moved = new LinkedHashMap<>();
            sources.forEach(
                    (each, by) ->
                            moved.put(
                                    under(each, from) ? to + each.substring(from.length()) : each,
                                    by));
            sources.clear();
            sources.putAll(moved);
        }

        /** What is set again is no longer listed as removed. */
        private void restored(final String path) {
            removed.removeIf(each -> under(each.path(), path) || under(path, each.path()));
        }

        private void fault(final String by, final Node node, final String what) {
            faults.add(by + ", line " + line(node) + ": " + what);
        }
    }

    private static boolean under(final String path, final String root) {
        return root.isEmpty()
                || path.equals(root)
                || path.startsWith(root + ".")
                || path.startsWith(root + "[");
    }

    private static Node emptyLike(final Node node) {
        return switch (node) {
            case MappingNode map -> emptyMapping();
            case SequenceNode list -> emptySequence();
            default -> node;
        };
    }

    private static MappingNode emptyMapping() {
        return new MappingNode(Tag.MAP, new ArrayList<>(), DumperOptions.FlowStyle.BLOCK);
    }

    private static SequenceNode emptySequence() {
        return new SequenceNode(Tag.SEQ, new ArrayList<>(), DumperOptions.FlowStyle.BLOCK);
    }

    private static String entryPath(final String path, final Node entry, final int at) {
        return path + "[" + (entry instanceof ScalarNode scalar ? scalar.getValue() : at) + "]";
    }

    private static String key(final NodeTuple tuple) {
        return tuple.getKeyNode() instanceof ScalarNode scalar ? scalar.getValue() : "?";
    }

    private static boolean isNull(final Node node) {
        return Tag.NULL.equals(node.getTag());
    }

    /** A local tag such as {@code !mirror.example.org}: what an unquoted removal turns into. */
    private static boolean isTagged(final Node node) {
        return node.getTag().getValue().startsWith(REMOVE);
    }

    private static boolean isQuoted(final ScalarNode scalar) {
        return scalar.getScalarStyle() == DumperOptions.ScalarStyle.DOUBLE_QUOTED
                || scalar.getScalarStyle() == DumperOptions.ScalarStyle.SINGLE_QUOTED;
    }

    private static int line(final Node node) {
        return node.getStartMark() == null ? 0 : node.getStartMark().getLine() + 1;
    }
}
