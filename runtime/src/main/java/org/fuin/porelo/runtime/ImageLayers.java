package org.fuin.porelo.runtime;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Container-build fragments contributed by something outside this module.
 *
 * <p>Plain lists of lines rather than an agent-shaped type, deliberately: {@code porelo-runtime}
 * builds images and must not learn what an agent is. Whoever knows about agents - {@code
 * porelo-app} - converts and passes them in.
 *
 * @param asRoot Fragments run before the image drops to the unprivileged user.
 * @param asAgent Fragments run as the agent user.
 * @param settings What the image records about its build, or {@code null} to record nothing.
 * @param pinned The bare name of the program a start runs, resolved and recorded after every layer,
 *     or {@code null} for an image that starts none.
 */
public record ImageLayers(
        List<String> asRoot,
        List<String> asAgent,
        @Nullable ImageSettings settings,
        @Nullable String pinned) {

    /**
     * Constructor with all data.
     *
     * @param asRoot Fragments run as root.
     * @param asAgent Fragments run as the agent user.
     * @param settings What the image records, or {@code null}.
     * @param pinned The program a start runs, or {@code null}.
     */
    public ImageLayers {
        asRoot = List.copyOf(asRoot);
        asAgent = List.copyOf(asAgent);
    }

    /**
     * Constructor for layers that pin no program.
     *
     * @param asRoot Fragments run as root.
     * @param asAgent Fragments run as the agent user.
     * @param settings What the image records, or {@code null}.
     */
    public ImageLayers(
            List<String> asRoot, List<String> asAgent, @Nullable ImageSettings settings) {
        this(asRoot, asAgent, settings, null);
    }

    /**
     * Constructor for layers that record nothing.
     *
     * @param asRoot Fragments run as root.
     * @param asAgent Fragments run as the agent user.
     */
    public ImageLayers(List<String> asRoot, List<String> asAgent) {
        this(asRoot, asAgent, null);
    }

    /**
     * Returns these layers recording what the image was built with.
     *
     * @param recorded What to record.
     * @return The same layers, with the record.
     */
    public ImageLayers recording(ImageSettings recorded) {
        return new ImageLayers(asRoot, asAgent, recorded, pinned);
    }

    /**
     * Returns these layers pinning the program a start runs: resolved on root's {@code PATH} after
     * every layer and recorded in the image, so a start never looks the bare name up.
     *
     * @param program The program's bare name.
     * @return The same layers, pinning it.
     */
    public ImageLayers pinning(String program) {
        return new ImageLayers(asRoot, asAgent, settings, program);
    }

    /**
     * Returns layers that add nothing.
     *
     * @return Empty layers.
     */
    public static ImageLayers none() {
        return new ImageLayers(List.of(), List.of());
    }

    /**
     * Returns these layers with more appended.
     *
     * @param moreAsRoot Additional root fragments.
     * @param moreAsAgent Additional agent-user fragments.
     * @return Combined layers.
     */
    public ImageLayers and(List<String> moreAsRoot, List<String> moreAsAgent) {
        final List<String> root = new java.util.ArrayList<>(asRoot);
        appendSeparated(root, moreAsRoot);
        final List<String> agent = new java.util.ArrayList<>(asAgent);
        appendSeparated(agent, moreAsAgent);
        return new ImageLayers(root, agent, settings, pinned);
    }

    private static void appendSeparated(List<String> target, List<String> addition) {
        if (addition.isEmpty()) {
            return;
        }
        if (!target.isEmpty()) {
            target.add("");
        }
        target.addAll(addition);
    }

    /**
     * Tells whether these layers add anything.
     *
     * @return {@code true} if both lists are empty.
     */
    public boolean isEmpty() {
        return asRoot.isEmpty() && asAgent.isEmpty();
    }
}
