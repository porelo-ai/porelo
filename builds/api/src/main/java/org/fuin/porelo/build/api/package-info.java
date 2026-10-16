/**
 * The contract between Porelo and a build reader: the interface a reader implements, the server it runs, and the client
 * Porelo asks it with. A reader in its own repository depends on this and on {@code porelo-wire}, and on nothing else of
 * Porelo's.
 */
@NullMarked
package org.fuin.porelo.build.api;

import org.jspecify.annotations.NullMarked;
