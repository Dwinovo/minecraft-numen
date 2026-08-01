/**
 * <strong>Public API:</strong> {@link SkillRegistry} — the entry a mod calls to
 * declare the {@code /skills} directory bundled in its jar (one call in a client
 * entry point and players' companions learn the mod) — and {@link SkillInfo},
 * the read surface the {@code load_skill} tool consumes.
 *
 * <p>{@link SkillMarkdown} (frontmatter parsing) is internal.
 */
package com.dwinovo.numen.agent.skill;
