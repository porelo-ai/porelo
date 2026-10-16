/**
 * The service provider interface every agent module implements.
 * <p>
 * Nothing here names an agent, and nothing outside {@code agents/} depends on an agent. The
 * arrow points one way only: an agent knows about Porelo, Porelo does not know about agents.
 */
@NullMarked
package org.fuin.porelo.agent.api;

import org.jspecify.annotations.NullMarked;
