/**
 * A review's comments and replies as typed records, and the narrow interfaces between the parts
 * that check, keep and write them.
 *
 * <p>Only these records travel inside: every format is translated at its channel's edge on the way
 * in, and one writer ({@link ReviewWriter}) writes SARIF on the way out. What a producer gave and
 * what the host stamped are kept apart ({@link Kept}, {@link Origin}), and the kinds of anchor and
 * item are sealed, so a later kind is a new case, never a rebuild.
 */
@NullMarked
package org.fuin.porelo.gate.review;

import org.jspecify.annotations.NullMarked;
