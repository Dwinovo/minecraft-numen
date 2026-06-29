package com.dwinovo.numen.agent.tool.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Numen tool the LLM can call. This is the entire authoring
 * surface for a capability: a name, a description, and the method body.
 *
 * <p>The parameter <em>schema</em> the model sees is derived from the method
 * signature — each {@link Arg}-annotated parameter becomes a typed property (see
 * {@link ToolSchema}). The author never hand-writes JSON schema. Non-{@code @Arg}
 * parameters are context the engine injects by type (the companion handle, the
 * reply callback) and never appear in the schema.
 *
 * <p>This is the public, MIT-surface annotation that both Numen's own tools and
 * third-party addon mods use to register capabilities — the same path for
 * everyone (dogfooding).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NumenAction {

    /** Tool name as the LLM sees it ({@code snake_case}). Empty = use the method name. */
    String name() default "";

    /**
     * Description shown to the LLM — the biggest lever on correct tool selection.
     * Be thorough: what it does, WHEN to use it (and when not), any caveat. The
     * per-tool how-to lives here, riding on every request, so it can't rot.
     */
    String description();

    /**
     * Deadline in game ticks for a body task (20 ticks = 1 s). Ignored by tools
     * that complete immediately. Default 1 = effectively instant.
     */
    long timeoutTicks() default 1L;
}
