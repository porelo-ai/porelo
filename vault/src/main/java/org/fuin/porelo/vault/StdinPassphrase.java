package org.fuin.porelo.vault;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Takes the passphrase as one line of this process's standard input.
 *
 * <p>For a program on another machine that holds the passphrase in its own memory and hands it over
 * an ssh channel: nothing on either side holds it in a file, and it is in no process's arguments.
 * {@link CommandPassphrase} cannot serve there, because its command gets a pipe of Porelo's own,
 * closed at once, as its input.
 *
 * <p>It reads up to the first newline and no further, so what follows on the stream is left for
 * whoever reads next. The bytes are zeroed once decoded.
 */
public class StdinPassphrase implements PassphraseSource {

    /** The longest line taken, in bytes; a generated passphrase is far below it. */
    public static final int MAX_BYTES = 1024;

    private final InputStream input;

    /**
     * Constructor.
     *
     * @param input The stream to read the line from.
     */
    public StdinPassphrase(InputStream input) {
        this.input = input;
    }

    /**
     * Reads the line.
     *
     * @return The passphrase, or empty when the stream ends before a byte or the line is empty.
     * @throws VaultException If the line is longer than {@value #MAX_BYTES} bytes, is not UTF-8, or
     *     the stream cannot be read.
     */
    @Override
    public Optional<char[]> passphrase() {
        final byte[] line = new byte[MAX_BYTES];
        int length = 0;
        try {
            while (true) {
                final int next = input.read();
                if (next == -1 || next == '\n') {
                    break;
                }
                if (length == MAX_BYTES) {
                    Arrays.fill(line, (byte) 0);
                    throw new VaultException(
                            "the passphrase on standard input is longer than "
                                    + MAX_BYTES
                                    + " bytes");
                }
                line[length++] = (byte) next;
            }
        } catch (final IOException ex) {
            Arrays.fill(line, (byte) 0);
            throw new VaultException("cannot read standard input: " + ex.getMessage(), ex);
        }
        if (length > 0 && line[length - 1] == '\r') {
            length--;
        }
        try {
            if (length == 0) {
                return Optional.empty();
            }
            final CharBuffer decoded =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(line, 0, length));
            final char[] passphrase = new char[decoded.remaining()];
            decoded.get(passphrase);
            Arrays.fill(decoded.array(), '\0');
            return Optional.of(passphrase);
        } catch (final CharacterCodingException ex) {
            throw new VaultException("the passphrase on standard input is not UTF-8", ex);
        } finally {
            Arrays.fill(line, (byte) 0);
        }
    }

    @Override
    public String name() {
        return "standard input";
    }
}
