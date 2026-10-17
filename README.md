# VCT Ratings: in-round win probability and WPA for pro Valorant

A data pipeline and model that estimates, after every kill and spike plant, each team's chance of
winning the round in professional Valorant (VCT 2023–2026), then credits each change to the
players who caused it: **win probability added (WPA)**.

- **Scraper (Java):** collects 1,890 VCT matches from [vlr.gg](https://www.vlr.gg), including each
  map's 2D replay data, politely (rate limited, cached, resumable).
- **Database (Java + SQLite):** turns ~4 GB of saved HTML into a clean, validated database: matches,
  maps, rounds, per-player stats, buys, ~600k kill/plant/defuse events, and 3.7M player positions.
- **Model (Python):** a calibrated LightGBM model of round win probability from players alive, time,
  spike state and economy, evaluated on a season it never saw.
- **WPA:** credits every kill and death, with a trade adjustment, and reports per-player WPA with
  uncertainty ranges and shrinkage for small samples.

## Example: one round, kill by kill

*VCT 2026 Americas Stage 2 grand final, map 5 (Haven), round 23. 100 Thieves attacking.*

| Time | Event | Attackers' win prob | Change |
|---|---|---|---|
| 0:00 | Round start, 5v5 | 56.9% | |
| 0:53 | lukxo (LOUD) kills Timotino: opening kill | 35.1% | −21.4 |
| 1:02 | tkzin (LOUD) kills Cryocells | 16.3% | −19.4 |
| 1:10 | bang (100T) plants A | 21.1% | +5.0 |
| 1:11 | lukxo kills Asuna | 6.3% | −14.8 |
| 1:20 | vora (100T) kills tkzin | 18.7% | +12.1 |
| 1:29 | lukxo kills vora | 2.6% | −15.6 |
| 1:29 | bang kills Darker | 8.4% | +5.8 |
| 1:31 | lukxo kills bang: trade, round over | 0.0% | −8.8 |

lukxo's four kills were worth about **+56%** of a round win to LOUD. Darker's death costs him only
−1.4 instead of −5.8, because lukxo traded it within 5 seconds (see [trades](#modelling-choices)).

## Results

**Win probability model.** Trained on 2023–2025, tested on all of 2026 (196k game states it never saw):

| Model | Log loss | Brier | Accuracy | AUC |
|---|---|---|---|---|
| Lookup table (players alive + spike) | 0.477 | 0.160 | 74.9% | 0.844 |
| Logistic regression | 0.457 | 0.151 | 77.0% | 0.862 |
| **LightGBM** (monotone constraints) | **0.448** | **0.149** | **77.3%** | **0.867** |

- **Calibrated:** in every 10% probability band, predicted and actual win rates are within ~2 points.
- **At round start** (before any kills), accuracy is 61.1%, compared with 60.6% for a published
  round-start logistic regression on 1,301 rounds ([Wang, 2025](https://doi.org/10.36227/techrxiv.174742078.82022650/v1)).
- **Biggest drivers:** difference in players alive (~60% of the model's gain), then loadout
  difference (~23%), then spike state and time left on it.

**What actions are worth** (average change in the acting team's win probability, ~600k actions):

| Situation | Value |
|---|---|
| Opening kill, full buy vs full buy | +19.6% |
| Opening kill, half buy vs full buy | +23.1% |
| Opening kill, full buy vs eco | +9.8% |
| Kill in a 2v2 before the plant (attacker) | +31.8% |
| Kill in a 5v2 (already winning) | +0.9% |
| Defender kill in a post-plant 2v2 | +36.9% |
| Last-second plant (90 s+) | +19.3% (credited to no one) |

**Players.** Net WPA per round played, as % of a round win, 2026 season (1,000+ rounds):
"shrunk" pulls small samples toward the average, so it's the best single estimate.

| Player | Team | Rounds | vlr.gg rating | Shrunk net WPA | 95% range |
|---|---|---|---|---|---|
| marteen | M8 | 1,274 | 1.22 | 1.88 | 1.4 to 3.8 |
| Neon | LEV | 1,504 | 1.19 | 1.82 | 1.3 to 3.4 |
| lukxo | LOUD | 1,357 | 1.16 | 1.74 | 1.2 to 3.5 |
| primmie | FS | 1,273 | 1.21 | 1.65 | 1.1 to 3.5 |
| Jemkin | RRQ | 1,159 | 1.12 | 1.64 | 1.1 to 3.6 |

Net WPA correlates 0.90 with vlr.gg's rating. The interesting cases are where they disagree.

## How it works

```
vlr.gg ──scrape──> data/raw/*.html ──build-db──> data/vct.db ──train_wpa──> win prob per state
 (Java)                                (Java)                     (Python)
                                                              ──event_wpa──> credit per kill, per player
                                                              ──report────> season / event tables
```

1. **Scrape** (`discover`, `fetch`, `fetch-logs`, `update`): walks vlr.gg's results list, keeps
   official VCT events (league stages, Kickoffs, Masters, Champions) since 2023, and saves each match
   page and each map's replay page. Requests are rate limited (1.5–3 s apart), retried with backoff,
   cached on disk, and resumable. `update` fetches only new matches.
2. **build-db**: parses every saved page into `data/vct.db`, always from scratch, so a parser fix is
   just a rebuild. It writes to a temporary file and swaps it in only when the whole build succeeds,
   and prints a validation report: series and map scores agree, round strips match map scores,
   replay kills match the stat table, every replay player maps to a vlr.gg ID, and so on.
3. **Game states**: a state at round start and after every kill and plant, until the round is
   decided: players alive, seconds in, spike planted and time since, loadout value of living players
   (from a [price table](src/main/java/dev/bernie/vct/db/Prices.java)), bank, weapon counts, score.
4. **train_wpa.py**: compares the three models above, then scores every state out-of-fold
   (5 folds grouped by match), so no state is scored by a model that saw its match.
5. **event_wpa.py**: splits each change in win probability into the clock's part (time passing,
   credited to no one) and the action's part (credited to players). Every round adds up exactly:
   start + clock + actions + ending = result.

## Modelling choices

- **Kills and deaths only.** The killer gets +change and the victim −change. Teamkills are charged
  to the teamkiller. Spike and fall deaths are charged to the victim.
- **Trades are split 0.5.** If a teammate kills your killer within 5 s, half of the trade kill's
  credit goes back to you, so a traded entry costs ~−8% instead of ~−17%. Team totals don't change.
- **Plants and defuses aren't credited to players.** They change win probability (and the model
  uses the spike state), but planting is a team action. Crediting the spike carrier rewards a role.
- **Clock drift and spike detonation/time-out endings** are credited to no one.

## Limitations

- **No HP, utility or ultimates:** vlr.gg doesn't record them. A 2v2 where one side is at 10 HP
  looks like any other 2v2.
- **No China league replays:** China-based players are judged on international events only.
- **The model predicts rounds, not matches:** saving weapons counts as a loss even when it's right.
- **No opponent adjustment yet:** a kill against the best team counts the same as any other.
- **Prices** are current-patch values for every season; two are estimates.
- **Single events are small samples:** ~500 rounds can tell good from bad, not #1 from #6.

## Running it

Requires JDK 21 and Python 3.12. The scraped data isn't in the repo; a full scrape takes ~4 hours.

```powershell
# one-time setup
copy .env.example .env          # then put your contact email in it (sent to vlr.gg)
python -m venv .venv
.venv\Scripts\python -m pip install -r model\requirements.txt

# scrape (resumable: rerun to continue)
.\gradlew.bat run --args="discover"     # since 2023 by default; --since YYYY-MM-DD to change
.\gradlew.bat run --args="fetch"
.\gradlew.bat run --args="fetch-logs"

# build the database, the model and WPA
.\pipeline.ps1                  # -Update to scrape new matches first, -SkipBuild for Python only

# reports
.venv\Scripts\python model\report.py --season 2026
.venv\Scripts\python model\report.py --event "VCT 2026: Americas Stage 2"

# tests
.\gradlew.bat test
```

## Project layout

```
src/main/java/dev/bernie/vct/
  cli/      commands (Main) and .env settings
  fetch/    Fetcher: the only code that talks to vlr.gg
  parse/    results, match and replay page parsers; VCT event filter
  store/    scrape queue (data/scrape.db)
  db/       build-db: schema, game states, prices, validation
src/test/   JUnit tests, with saved vlr.gg pages as fixtures
model/      train_wpa.py, event_wpa.py, report.py
pipeline.ps1
```

## Data and credit

Match data comes from [vlr.gg](https://www.vlr.gg). The scraper identifies itself with a contact
address, waits between requests, and never re-downloads a saved page. The scraped data is not
redistributed here. Licensed under MIT (see [LICENSE](LICENSE)).
