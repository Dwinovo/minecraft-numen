package com.dwinovo.numen.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type, member, or whole package as <strong>not</strong> part of the
 * public numen-api surface that tool packs build against. Internal code may
 * change or disappear in any release without notice — depend on it at your own
 * risk.
 *
 * <p>Inspired by Applied Energistics 2's convention of a clearly-delineated API
 * package: here the public API is the set of packages whose {@code package-info}
 * declares them so — the {@code @NumenAction} authoring surface
 * ({@code com.dwinovo.numen.agent.tool.api}), the tool contract / registration
 * ({@code com.dwinovo.numen.agent.tool}), the world-action task contract
 * ({@code com.dwinovo.numen.task}), and {@code NumenPlayer} in
 * {@code com.dwinovo.numen.entity}. Anything outside those packages — or marked
 * with this annotation inside them — is internal.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR,
        ElementType.FIELD, ElementType.PACKAGE})
public @interface Internal {
}
