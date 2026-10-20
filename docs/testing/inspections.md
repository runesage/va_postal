# Test plan: inspections (C4)

What inspections promise (`docs/design/central-office.md` §5):
- **Central watches its offices** every hour and sends an inspector to any with something wrong, at most once a week.
- **The inspector travels with Central's postman** and leaves a report in the office chest. The owner is told.
- **Reports only:** nothing is charged or taken.

**Most of this is covered automatically.** Unit tests cover the rules: what counts as wrong, the thresholds, when one
is due. Smoke phase 24 sends an inspector to Testville (it lost a letter earlier in the test), checks the report lands in
its chest when Central's postman visits, and checks it shows on `/postal inspect` and the notice board.

What the automated tests can't cover:
- the owner's chat message;
- reading the report in game;
- a real postman getting stuck on a real route.

Allow about 15 minutes. You need an office you own (**Town** below) and admin rights.

## 0. Setup

Optional settings in `config.yml` under `Settings:`. Each is shown with its default:

```yaml
Settings:
  Inspection:
    Enabled: 'true'
    Interval_days: 7       # at most one inspection per office per this many days
    Min_on_time: 0.5       # flag an office with less than this share of mail on time
    Stuck_resets: 10       # flag a postman stuck and reset this many times in the interval (0: ignore)
```

## 1. Nothing wrong

1. `/postal inspect Town`. **Expect:** "Never inspected", then "Now: nothing wrong". If something is listed, it's real:
   fix it or carry on with step 2.
2. `/postal inspect Town now`. **Expect:** "Nothing wrong at the Town Post Office: no inspector needed".

## 2. A full chest

1. Fill Town's office chest with any blocks, every slot.
2. `/postal inspect Town`. **Expect:** "Now: 1 thing(s) an inspector would find: The office chest is full".
3. `/postal inspect Town now`, then `/postal start` and wait for Central's postman to reach Town.
   **Expect:**
   - the server log says "Central's inspector filed a report on town" (the chest is full, so he couldn't leave one);
   - you get "Central's inspector reported on the Town Post Office:" and the finding with its fix.
4. Empty the chest.

## 3. A report left in the chest

1. Make something else wrong. The quickest is a lost letter: take a tracked letter out of Town's chest by hand, run
   `/postal reconcile` until `/postal track` shows it `MISSING`, then `/postal stats refresh`.
2. `/postal inspect Town now` and wait for Central's postman.
   **Expect:** an *Inspection report* (a sheet of paper) in Town's chest, listing the finding and its fix, with
   "Reports only: nothing is charged". You're told in chat, with "and left a report in its chest".
3. `/postal inspect`. **Expect:** Town with today's date and "1 finding(s)".
4. Put a sign on Central's notice board with `/postal board add inspections` and look at it.
   **Expect:** INSPECTIONS, Town and its count of findings. Right-clicking it lists the reports in chat.

## 4. Once a week

1. Leave Town with something wrong, with `/postal start` running for an hour or two.
   **Expect:** no second report, because the last one was within `Interval_days`. `/postal inspect Town` still shows
   the first.

## 5. What to report

For each step: what you did, what you expected, and what happened. For anything wrong, add the server log lines
around it.
