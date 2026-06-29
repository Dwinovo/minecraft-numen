package com.dwinovo.numen.agent.tool.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method parameter as a model-facing tool argument. Its name comes from
 * the parameter name (bytecode must be compiled with {@code -parameters}; the
 * Numen build does this) unless {@link #name()} overrides it; its JSON-schema
 * type is derived from the parameter's Java type. The author supplies only the
 * description plus any constraints that can't be read from the type.
 *
 * <p>Parameters <em>without</em> this annotation are engine-injected context
 * (the companion handle, the reply callback) and are excluded from the schema.
 *
 * <h2>Optionality — two orthogonal axes</h2>
 * Whether an argument appears in the schema's {@code required} list and whether
 * its type permits {@code null} are independent, matching the two ways the tools
 * express "optional":
 * <ul>
 *   <li>{@link #required()} — is the property listed in {@code required}? Default
 *       {@code true}. The OpenAI strict / structured-output contract wants every
 *       property required and expresses "optional" as {@link #nullable()}; a few
 *       older tools instead drop the property from {@code required}, which
 *       {@code required = false} reproduces.</li>
 *   <li>{@link #nullable()} — is the type a {@code [type, "null"]} union? Default
 *       {@code false}. Set {@code true} for the strict-mode "optional" form
 *       (still in {@code required}, but may be null).</li>
 * </ul>
 * Either form of optionality should map to a boxed / nullable Java parameter so
 * a missing value can bind as {@code null}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Arg {

    /** Human description of the argument, shown to the model. */
    String value();

    /** Override the argument name. Empty = use the parameter's own name. */
    String name() default "";

    /** Is the property listed in the schema's {@code required} array? Default {@code true}. */
    boolean required() default true;

    /** Is the type a {@code [type, "null"]} union (strict-mode "optional")? Default {@code false}. */
    boolean nullable() default false;

    /** Inclusive numeric lower bound ({@code minimum}). {@code NaN} = unset. */
    double min() default Double.NaN;

    /** Inclusive numeric upper bound ({@code maximum}). {@code NaN} = unset. */
    double max() default Double.NaN;

    /** Minimum array length ({@code minItems}) for a {@code List}/array argument. Negative = unset. */
    int minItems() default -1;

    /** Allowed string values ({@code enum}). Empty = unconstrained. */
    String[] enumValues() default {};
}
