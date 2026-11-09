package org.fuin.porelo.core.project;

/**
 * The model a project, or a repository's own file, names for one provider: what a task on that
 * provider runs on unless its start names another.
 *
 * @param model The model's id, one word.
 * @param label What a person is shown for it, or {@code ""} when the file gives none.
 * @param from Which file named it: {@link #PROJECT} or {@link #REPOSITORY}.
 */
public record ModelDefault(String model, String label, String from) {

    /** The model came with the start itself. */
    public static final String START = "start";

    /** The model is the repository's own file's. */
    public static final String REPOSITORY = "repository";

    /** The model is the project file's. */
    public static final String PROJECT = "project";

    /** Nothing named one: the agent runs on its own default. */
    public static final String AGENT = "agent";

    /** What a provider's name looks like, as a provider's own file must name it. */
    public static final String PROVIDER_NAME = "[a-z0-9][a-z0-9-]{0,30}";

    /**
     * Constructor.
     *
     * @param model The model's id.
     * @param label What a person is shown, or {@code ""}.
     * @param from Which file named it.
     */
    public ModelDefault {
        if (!isWord(model)) {
            throw new ProjectException("A model is one word, not '" + shown(model) + "'");
        }
        if (!label.isEmpty() && !isLine(label)) {
            throw new ProjectException("A model's label is one line of text");
        }
    }

    /**
     * Tells whether a model id can go on an agent's command line as the one value it is: not empty,
     * not read as an option, and holding no blank, line break or control character.
     *
     * @param model The id.
     * @return Whether it is one word.
     */
    public static boolean isWord(final String model) {
        return !model.isEmpty()
                && !model.startsWith("-")
                && model.codePoints()
                        .noneMatch(
                                c ->
                                        Character.isWhitespace(c)
                                                || Character.isSpaceChar(c)
                                                || Character.isISOControl(c)
                                                || Character.getType(c) == Character.FORMAT);
    }

    /**
     * Tells whether a label is one line of printable text, which every interface can show as it is.
     *
     * @param label The label.
     * @return Whether it is.
     */
    public static boolean isLine(final String label) {
        return !label.isBlank()
                && label.codePoints()
                        .noneMatch(
                                c ->
                                        Character.isISOControl(c)
                                                || Character.getType(c) == Character.FORMAT
                                                || Character.getType(c) == Character.LINE_SEPARATOR
                                                || Character.getType(c)
                                                        == Character.PARAGRAPH_SEPARATOR);
    }

    /**
     * Returns a value as a message shows it: no control character.
     *
     * @param value The value.
     * @return What a message shows of it.
     */
    public static String shown(final String value) {
        final String plain = value.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]", "?");
        return plain.length() <= 80 ? plain : plain.substring(0, 80) + "...";
    }
}
