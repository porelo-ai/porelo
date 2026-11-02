/**
 * The contract between Porelo and a build-tool plugin: the interface a plugin implements, the
 * server it runs, and the client Porelo asks it with. A plugin in its own repository depends on
 * this and on {@code porelo-wire}, both Apache-2.0, and on nothing else of Porelo's.
 */
@NullMarked
package org.fuin.porelo.ecosystem.api;

import org.jspecify.annotations.NullMarked;
