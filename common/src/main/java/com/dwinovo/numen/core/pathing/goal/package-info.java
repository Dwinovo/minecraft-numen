/**
 * The intent boundary of the pathing package: tasks say WHAT they want
 * ({@link com.dwinovo.numen.core.pathing.goal.GoalCompiler#interact interact
 * with this block}, {@link com.dwinovo.numen.core.pathing.goal.GoalCompiler#standOn
 * stand on this cell}, {@link com.dwinovo.numen.core.pathing.goal.GoalCompiler#mineField
 * mine this field}) and the compiler translates that — in ONE place — into the
 * three things a navigation and its task must agree on: the search goal
 * (a {@link com.dwinovo.numen.core.pathing.calc.NavGoal}), the sacred cells the
 * route may neither break nor bury (threaded into
 * {@link com.dwinovo.numen.core.pathing.moves.CalculationContext#sacred}), and the
 * body-level arrival ingredients
 * ({@link com.dwinovo.numen.core.pathing.goal.ArrivalSpec}).
 *
 * <p>Before this layer existed each task hand-picked its NavGoal, and the
 * fallback for "go to a block" was a Euclidean sphere that admitted elevated
 * cells — the geometry behind approaches that finished by pillaring beside
 * their target, and nothing marked the target itself as untouchable, so routes
 * could break or bury the very block they were travelling to.
 *
 * <p>Package contract: pathing's public front door is
 * {@code PlayerNav} + {@code GoalCompiler} (+ the {@code NavGoal} vocabulary
 * for custom goals like {@code runAway}). Task code should not assemble
 * goal/sacred/arrival triples by hand.
 */
package com.dwinovo.numen.core.pathing.goal;
