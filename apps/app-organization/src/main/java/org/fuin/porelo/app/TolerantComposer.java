package org.fuin.porelo.app;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.composer.Composer;
import org.yaml.snakeyaml.events.CommentEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.parser.Parser;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Works around SnakeYAML 2.7 typing whole comment lines after a block scalar ('|' or '>') as
 * end-of-line comments, crashing composition on separated groups. They belong to the next key.
 * Remove this workaround when OrganizationYamlTest.plainSnakeYamlStillNeedsTheWorkaround fails
 * because plain SnakeYAML composes build.yml with comments.
 */
final class TolerantComposer extends Composer {

    /** Comment lines read after a block scalar, waiting for the next key. */
    private final List<CommentLine> pending = new ArrayList<>();

    private TolerantComposer(Parser parser, Resolver resolver, LoaderOptions options) {
        super(parser, resolver, options);
    }

    /** Composes one document with comments, retaining SnakeYAML's loading limits. */
    static @Nullable Node compose(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setProcessComments(true);
        return new TolerantComposer(
                        new ParserImpl(new StreamReader(new StringReader(text)), options),
                        new Resolver(),
                        options)
                .getSingleNode();
    }

    @Override
    protected Node composeScalarNode(@Nullable String anchor, List<CommentLine> blockComments) {
        Node node = super.composeScalarNode(anchor, blockComments);
        DumperOptions.ScalarStyle style = ((ScalarNode) node).getScalarStyle();
        boolean block =
                style == DumperOptions.ScalarStyle.LITERAL
                        || style == DumperOptions.ScalarStyle.FOLDED;
        if (block && node.getInLineComments() != null && !node.getInLineComments().isEmpty()) {
            // No comment can stand on a block scalar's last line: these stood on lines of their
            // own.
            for (CommentLine line : node.getInLineComments()) {
                pending.add(asBlock(line));
            }
            node.setInLineComments(null);
        }
        if (block) {
            // Consume every group before the mapping loop mistakes a comment for a key.
            while (parser.checkEvent(Event.ID.Comment)) {
                pending.add(asBlock(new CommentLine((CommentEvent) parser.getEvent())));
            }
        }
        return node;
    }

    @Override
    protected Node composeKeyNode(MappingNode node) {
        List<CommentLine> before = new ArrayList<>(pending);
        pending.clear();
        List<CommentLine> stray = new ArrayList<>();
        while (parser.checkEvent(Event.ID.Comment)) {
            CommentEvent event = (CommentEvent) parser.getEvent();
            stray.add(asBlock(new CommentLine(event)));
        }
        Node key = super.composeKeyNode(node);
        if (!before.isEmpty() || !stray.isEmpty()) {
            List<CommentLine> all = new ArrayList<>(before);
            if (key.getBlockComments() != null) {
                all.addAll(key.getBlockComments());
            }
            all.addAll(stray);
            key.setBlockComments(all);
        }
        return key;
    }

    @Override
    public @Nullable Node getSingleNode() {
        Node root = super.getSingleNode();
        if (root != null && !pending.isEmpty()) {
            List<CommentLine> end = new ArrayList<>(pending);
            if (root.getEndComments() != null) {
                end.addAll(root.getEndComments());
            }
            root.setEndComments(end);
            pending.clear();
        }
        return root;
    }

    private static CommentLine asBlock(CommentLine line) {
        if (line.getCommentType() == CommentType.IN_LINE) {
            return new CommentLine(
                    line.getStartMark(), line.getEndMark(), line.getValue(), CommentType.BLOCK);
        }
        return line;
    }
}
