package org.fuin.porelo.gate;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A mirror's commit graph as git walks it, for the gate's judges: {@code rev-list} and {@code log}
 * honor every included and excluded tip, on the command line or standard input, read the refs as
 * they are at that moment for {@code --branches}, {@code --tags} and {@code --glob}, and page the
 * newest-first order by {@code --skip} and {@code --max-count}.
 */
final class FakeMirror implements GitHttpServer.GitProcess {
    final Map<String, List<String>> parents = new HashMap<>();
    final Map<String, Long> dates = new HashMap<>();
    final Map<String, String> refs = new LinkedHashMap<>();
    final Set<String> unsigned = new HashSet<>();
    final Set<String> untrailed = new HashSet<>();
    final List<String> read = new ArrayList<>();
    Runnable afterFirstWindow = () -> {};
    private int windows;

    static String id(String name) {
        return "%040x".formatted(new BigInteger(1, name.getBytes(StandardCharsets.UTF_8)));
    }

    static FakeMirror line(String prefix, int count) {
        final FakeMirror mirror = new FakeMirror();
        for (int index = 1; index <= count; index++)
            mirror.commit(
                    prefix + index, index, index == 1 ? List.of() : List.of(prefix + (index - 1)));
        return mirror;
    }

    void commit(String name, long date, List<String> parentNames) {
        parents.put(id(name), parentNames.stream().map(FakeMirror::id).toList());
        dates.put(id(name), date);
    }

    @Override
    public byte[] run(List<String> arguments, byte[] input) {
        final String text;
        if (arguments.contains("for-each-ref")) {
            if (arguments.contains("--contains")) text = "";
            else {
                final List<String> prefixes =
                        arguments
                                .subList(arguments.indexOf("for-each-ref") + 1, arguments.size())
                                .stream()
                                .filter(each -> !each.startsWith("--"))
                                .toList();
                text =
                        refs.entrySet().stream()
                                .filter(
                                        ref ->
                                                prefixes.isEmpty()
                                                        || prefixes.stream()
                                                                .anyMatch(ref.getKey()::startsWith))
                                .map(ref -> id(ref.getValue()) + "\n")
                                .collect(Collectors.joining());
            }
        } else if (arguments.contains("cat-file")) {
            final String commit = arguments.getLast();
            if (arguments.contains("-t")) text = parents.containsKey(commit) ? "commit\n" : "";
            else {
                read.add(commit);
                text =
                        "tree "
                                + "c".repeat(40)
                                + "\n"
                                + (unsigned.contains(commit)
                                        ? ""
                                        : "gpgsig -----BEGIN SSH SIGNATURE-----\n AAAA\n"
                                                + " -----END SSH SIGNATURE-----\n")
                                + "\nwork\n";
            }
        } else if (arguments.contains("rev-list") || arguments.contains("log")) {
            final boolean log = arguments.contains("log");
            final List<String> walked =
                    walk(arguments, new String(input, StandardCharsets.US_ASCII));
            if (windows++ == 0) afterFirstWindow.run();
            text =
                    log
                            ? walked.stream()
                                    .map(
                                            each ->
                                                    each
                                                            + "\n"
                                                            + (untrailed.contains(each)
                                                                    ? ""
                                                                    : "Porelo-Issue: o/r#1\n"))
                                    .collect(Collectors.joining("\0"))
                            : walked.stream()
                                    .map(each -> each + "\n")
                                    .collect(Collectors.joining());
        } else throw new AssertionError("git is asked " + arguments);
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private List<String> walk(List<String> arguments, String input) {
        final List<String> included = new ArrayList<>();
        final List<String> excluded = new ArrayList<>();
        int maximum = Integer.MAX_VALUE;
        int skip = 0;
        boolean not = false;
        final int start = Math.max(arguments.indexOf("rev-list"), arguments.indexOf("log")) + 1;
        final List<String> tokens = new ArrayList<>(arguments.subList(start, arguments.size()));
        for (int index = 0; index < tokens.size(); index++) {
            final String each = tokens.get(index);
            if (each.startsWith("--max-count=")) maximum = Integer.parseInt(each.substring(12));
            else if (each.startsWith("--skip=")) skip = Integer.parseInt(each.substring(7));
            else if (each.equals("--not")) not = !not;
            else if (each.equals("--branches"))
                (not ? excluded : included).addAll(under("refs/heads/"));
            else if (each.equals("--tags")) (not ? excluded : included).addAll(under("refs/tags/"));
            else if (each.startsWith("--glob="))
                (not ? excluded : included).addAll(under(each.substring(7).replaceAll("\\*$", "")));
            else if (each.equals("--stdin"))
                tokens.addAll(input.lines().filter(line -> !line.isBlank()).toList());
            else if (each.startsWith("^")) excluded.add(each.substring(1));
            else if (each.matches("[0-9a-f]{40}")) (not ? excluded : included).add(each);
            else if (!each.equals("--")
                    && !each.equals("-z")
                    && !each.equals("--no-show-signature")
                    && !each.startsWith("--format="))
                throw new AssertionError("a walk is given " + each);
        }
        final Set<String> hidden = reachable(excluded);
        return reachable(included).stream()
                .filter(each -> !hidden.contains(each))
                .sorted(
                        Comparator.comparing((String each) -> dates.get(each))
                                .reversed()
                                .thenComparing(Comparator.reverseOrder()))
                .skip(skip)
                .limit(maximum)
                .toList();
    }

    private List<String> under(String prefix) {
        return refs.entrySet().stream()
                .filter(ref -> ref.getKey().startsWith(prefix))
                .map(ref -> id(ref.getValue()))
                .toList();
    }

    private Set<String> reachable(Collection<String> tips) {
        final Set<String> seen = new HashSet<>();
        final ArrayDeque<String> open = new ArrayDeque<>(tips);
        while (!open.isEmpty()) {
            final String each = open.pop();
            if (seen.add(each)) open.addAll(parents.getOrDefault(each, List.of()));
        }
        return seen;
    }
}
