package org.sensepitch.edge;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Excludes a method from the JaCoCo coverage report, which skips methods annotated with an
/// annotation whose name contains `Generated`. Only for a small helper whose single uncovered
/// branch cannot be reached, e.g. a `catch` that the compiler demands for a checked exception the
/// JDK guarantees never to throw. The doc comment of the method says why the branch is
/// unreachable. Everything else that is not covered is a design question, see `AGENTS.md`.
///
/// @author Jens Wilke
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface ExcludeFromJacocoGeneratedReport {}
