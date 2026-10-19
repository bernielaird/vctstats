package dev.bernie.vct.db;

import java.util.Map;
import java.util.Set;

/**
 * Credit prices for working out loadout value from what a player holds.
 *
 * These are current-patch prices applied to every season. Prices have shifted a little
 * since 2023, and Bandit / Regen Shield are estimates. Edit here and rebuild; the model's
 * weapon-class counts (rifles, snipers, heavy armor) don't depend on these at all.
 */
final class Prices {

    static final Map<String, Integer> WEAPON = Map.ofEntries(
            Map.entry("Classic", 0),
            Map.entry("Shorty", 300),
            Map.entry("Frenzy", 450),
            Map.entry("Ghost", 500),
            Map.entry("Bandit", 600),      // estimate
            Map.entry("Sheriff", 800),
            Map.entry("Stinger", 1100),
            Map.entry("Spectre", 1600),
            Map.entry("Bucky", 850),
            Map.entry("Judge", 1850),
            Map.entry("Bulldog", 2050),
            Map.entry("Guardian", 2250),
            Map.entry("Phantom", 2900),
            Map.entry("Vandal", 2900),
            Map.entry("Marshal", 950),
            Map.entry("Outlaw", 2400),
            Map.entry("Operator", 4700),
            Map.entry("Ares", 1600),
            Map.entry("Odin", 3200));

    static final Map<String, Integer> ARMOR = Map.of(
            "none", 0,
            "light", 400,
            "regen", 650,                   // estimate
            "heavy", 1000);

    static final Set<String> RIFLES = Set.of("Vandal", "Phantom", "Bulldog", "Guardian");
    static final Set<String> SNIPERS = Set.of("Operator", "Outlaw");

    /** A player's bank can't exceed this; vlr.gg's "spent" sometimes implies more. */
    static final int MAX_CREDITS = 9000;

    private Prices() {}

    static int loadout(String weapon, String armor) {
        return (weapon == null ? 0 : WEAPON.getOrDefault(weapon, 0))
                + (armor == null ? 0 : ARMOR.getOrDefault(armor, 0));
    }
}
