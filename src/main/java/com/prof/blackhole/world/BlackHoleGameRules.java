package com.prof.blackhole.world;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.minecraft.world.GameRules;

/**
 * World-changing behaviour, switchable per world with {@code /gamerule}:
 * <ul>
 *     <li>{@code blackHoleDestroysBlocks} - blocks and fluids inside the pull are torn loose and swallowed;</li>
 *     <li>{@code blackHoleMassEvolution} - holes grow from what they swallow and evaporate by Hawking radiation
 *     (white holes: lose what they eject and gather mass while quiet);</li>
 *     <li>{@code blackHoleCorruption} - long-standing black holes turn the ground around them into Event Horizon
 *     Waste (also needs {@code blackHoleDestroysBlocks}).</li>
 * </ul>
 */
public final class BlackHoleGameRules {
	public static final GameRules.Key<GameRules.BooleanRule> DESTROYS_BLOCKS = GameRuleRegistry.register(
		"blackHoleDestroysBlocks", GameRules.Category.MISC, GameRuleFactory.createBooleanRule(true));
	public static final GameRules.Key<GameRules.BooleanRule> MASS_EVOLUTION = GameRuleRegistry.register(
		"blackHoleMassEvolution", GameRules.Category.MISC, GameRuleFactory.createBooleanRule(true));
	public static final GameRules.Key<GameRules.BooleanRule> CORRUPTION = GameRuleRegistry.register(
		"blackHoleCorruption", GameRules.Category.MISC, GameRuleFactory.createBooleanRule(true));

	private BlackHoleGameRules() {
	}

	/** Loads the class, registering the rules. */
	public static void register() {
	}
}
