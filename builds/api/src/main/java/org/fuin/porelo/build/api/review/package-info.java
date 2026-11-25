/**
 * Reading a review document from outside: one hardened SARIF reader that holds a document to the
 * bounds and the schema of the review profile it names, before anything binds it, so a forge
 * adapter and Porelo read the same way.
 */
@NullMarked
package org.fuin.porelo.build.api.review;

import org.jspecify.annotations.NullMarked;
