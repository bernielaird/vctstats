"""WPA credit rules (python/wpa/event_wpa.py) on a small hand-made round.

Run from the project root: .venv\\Scripts\\python -m unittest discover -s python\\tests
"""
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace as Ev

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # python/
from wpa.event_wpa import TRADE_WINDOW, build, credits, label  # noqa: E402

ATK, DEF = 1, 2  # team ids


def kill(t, actor, victim, actor_team, victim_team, weapon="Vandal"):
    return Ev(type="kill", time_seconds=t, actor_id=actor, victim_id=victim,
              actor_team_id=actor_team, victim_team_id=victim_team, weapon=weapon)


class LabelTest(unittest.TestCase):
    def test_first_kill_is_an_opening_kill(self):
        self.assertEqual(label(kill(10, 21, 11, DEF, ATK), []), ("opening_kill", None))

    def test_killing_the_killer_inside_the_window_is_a_trade(self):
        first = kill(10, 21, 11, DEF, ATK)
        action, traded = label(kill(10 + TRADE_WINDOW, 12, 21, ATK, DEF), [first])
        self.assertEqual(action, "trade_kill")
        self.assertIs(traded, first)

    def test_too_late_is_not_a_trade(self):
        first = kill(10, 21, 11, DEF, ATK)
        self.assertEqual(label(kill(10 + TRADE_WINDOW + 1, 12, 21, ATK, DEF), [first]), ("kill", None))

    def test_teamkill_and_spike_death(self):
        self.assertEqual(label(kill(20, 11, 12, ATK, ATK), []), ("teamkill", None))
        self.assertEqual(label(kill(95, None, 12, None, ATK, weapon="Spike"), []), ("spike_death", None))

    def test_plants_keep_their_type(self):
        self.assertEqual(label(Ev(type="plant"), []), ("plant", None))


class CreditsTest(unittest.TestCase):
    def test_killer_gains_what_the_victim_loses(self):
        # Attackers' win probability rises 0.2 when an attacker kills a defender.
        self.assertEqual(credits("kill", 0.2, ATK, ATK, DEF, False), (0.2, -0.2))
        # The same swing seen from a defender's kill.
        self.assertEqual(credits("kill", -0.2, ATK, DEF, ATK, False), (0.2, -0.2))

    def test_plants_are_credited_only_when_asked(self):
        self.assertEqual(credits("plant", 0.1, ATK, ATK, None, False), (None, None))
        self.assertEqual(credits("plant", 0.1, ATK, ATK, None, True), (0.1, None))

    def test_spike_death_charges_only_the_victim(self):
        self.assertEqual(credits("spike_death", -0.05, ATK, None, ATK, False), (None, -0.05))


class BuildTest(unittest.TestCase):
    """One round: defender 21 opens on attacker 11, attacker 12 trades 3 s later, then wipes the last defender."""

    def setUp(self):
        g, r = 1, 1
        state = dict(game_id=g, round_num=r, attacker_id=ATK, defender_id=DEF, spike_planted=0, atk_won=1,
                     atk_start_loadout=20000, def_start_loadout=20000)
        self.states = pd.DataFrame([
            dict(state, state_num=0, event_num=None, time_seconds=0, atk_alive=2, def_alive=2, atk_win_prob=0.5),
            dict(state, state_num=1, event_num=0, time_seconds=10, atk_alive=1, def_alive=2, atk_win_prob=0.3),
            dict(state, state_num=2, event_num=1, time_seconds=13, atk_alive=1, def_alive=1, atk_win_prob=0.5),
        ])
        # Win probability just before each action (the last one has drifted +0.05 with the clock).
        self.before = pd.DataFrame([(g, r, 0, 0.5), (g, r, 1, 0.3), (g, r, 2, 0.55)],
                                   columns=["game_id", "round_num", "event_num", "atk_win_prob"])
        ev = dict(game_id=g, round_num=r, type="kill", weapon="Vandal", site=None, after_detonation=0)
        self.events = pd.DataFrame([
            dict(ev, event_num=0, time_seconds=10, actor_id=21, victim_id=11, actor_team_id=DEF, victim_team_id=ATK),
            dict(ev, event_num=1, time_seconds=13, actor_id=12, victim_id=21, actor_team_id=ATK, victim_team_id=DEF),
            dict(ev, event_num=2, time_seconds=40, actor_id=12, victim_id=22, actor_team_id=ATK, victim_team_id=DEF),
        ])
        self.outcomes = pd.DataFrame([(g, r, "elim")], columns=["game_id", "round_num", "outcome"])

    def run_build(self, refund):
        return build(self.states, self.before, self.events, self.outcomes, trade_refund=refund)

    def test_every_round_adds_up_exactly(self):
        events, rounds = self.run_build(0.5)
        r = rounds.iloc[0]
        total = r.start_prob + r.clock_atk + r.spike_atk + events.atk_change.sum() + r.ending_atk
        self.assertAlmostEqual(total, r.atk_won)
        self.assertEqual(r.end_type, "wipe")
        self.assertAlmostEqual(r.clock_atk, 0.05)

    def test_labels_and_round_winning_kill(self):
        events, _ = self.run_build(0.5)
        self.assertEqual(list(events.action), ["opening_kill", "trade_kill", "kill"])
        self.assertEqual(list(events.round_winning), [0, 0, 1])

    def test_trade_moves_half_the_credit_to_the_avenged_death(self):
        events, _ = self.run_build(0.5)
        opening, trade = events.iloc[0], events.iloc[1]
        self.assertAlmostEqual(trade.actor_credit_raw, 0.2)
        self.assertAlmostEqual(trade.actor_credit, 0.1)       # the trader keeps half
        self.assertAlmostEqual(opening.victim_credit, -0.1)   # 11's death costs half as much
        self.assertEqual(opening.traded_by_event, 1)

    def test_trade_refund_keeps_team_totals(self):
        def attacker_total(events):
            actor = events.loc[events.actor_team_id == ATK, "actor_credit"].sum()
            victim = events.loc[events.victim_team_id == ATK, "victim_credit"].sum()
            return actor + victim
        self.assertAlmostEqual(attacker_total(self.run_build(0.5)[0]), attacker_total(self.run_build(0.0)[0]))


if __name__ == "__main__":
    unittest.main()
