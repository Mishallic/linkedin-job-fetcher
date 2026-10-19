# Late notifications (Job Watch 1.12)

**Summary.** Jobs reached the phone up to about 18 minutes after LinkedIn listed them. Most of
the extra time came from 1.12's alert batching, which held a job a check had already found for
up to 8 more minutes, on top of the up-to-10-minute wait for its search's turn. 1.13 tells the
phone in the check that finds the job. What remains is the wait for the search's turn and
LinkedIn's own delay, which the app now measures on the phone.

## Symptom

Reported on 2026-10-19 with 1.12 installed: "I still get the job 18 minutes late."

## How a job reaches the phone

| Stage | Set by | Delay in 1.12 |
| --- | --- | --- |
| LinkedIn shows the posting in its search | LinkedIn | unknown |
| The search's next turn | design: 5 searches, one LinkedIn request every 2 minutes | 0 to 10 min |
| The alert | 1.12: at most one alert every 10 minutes, all queued jobs together | 0 to about 10 min |
| The phone runs the alarm | Android's battery rules | unknown |

Each check looks back to the previous one plus 5 minutes. So a posting LinkedIn shows more than
5 minutes after listing it can fall outside every check's window and be missed, not just delayed.

## Hypotheses

1. The alert batching adds up to 10 minutes after a job is found. Predicted from the code.
2. The wait for a search's turn adds up to 10 minutes. By design.
3. LinkedIn shows postings late. Unknown; above 5 minutes it also causes misses.
4. The phone runs alarms late while idle. Unknown.

## Method

- **Time made testable.** All scheduling reads one `Clock`, so checks can run on a simulated clock.
- **End-to-end simulation** (`DeliveryTimeTest`). A fake LinkedIn lists a job for each of 5
  searches every 7 minutes for 3 hours (126 jobs), shown at once or 5 minutes late. Its answers
  respect the window and page each request asks for. The alarm fires whenever it is due, and
  the test measures, per job, shown to found, found to notified, and posted to notified.
- **Field instrumentation.** Each job now records when it was posted (counted back from
  LinkedIn's "N minutes ago" when it was read), found and notified. Each alarm check records when
  it was due. **Delivery times** in the app shows the median and 90th percentile of each stage,
  each job's times, and shares them as text. **Recent checks** shows checks the phone ran late.
- **Contract test against LinkedIn** (`CoreTest.liveFetchFromLinkedIn`, off unless
  `LIVE_LINKEDIN=1`). One real request on 2026-10-19: all 10 cards' "N minutes ago" read as
  posting times, minute by minute under an hour.

## Results

Posted to notified, median / 90% / max in minutes, 126 jobs:

| LinkedIn shows postings | 1.12 | 1.13 |
| --- | --- | --- |
| at once | 9 / 14 / 17 | 5 / 9 / 9 |
| 5 minutes late | 13 / 19 / 22 | 9 / 14 / 14 |

In 1.12, found to notified was median 4, max 8 minutes; in 1.13 it is 0. Shown to found was
median 5, max 9 minutes in both, as the 10-minute turns predict. No posting was missed in either
version, at once or 5 minutes late.

## Root cause

Hypothesis 1. Since 1.12, a job found within 10 minutes of the previous alert waited for the
next one. With the turn's wait on top, a posting reached the phone up to 17 minutes later even
when LinkedIn showed it at once, and 22 minutes when LinkedIn took 5 minutes. That matches the
report.

## Fix (1.13)

The phone is told by the check that finds the jobs. Email and WhatsApp still go at most every 10
minutes, all together, since a message every 2 minutes would be spam, and nothing is queued
while neither is on. `DeliveryTimeTest` holds the budget: found within one turn of LinkedIn
showing the job, announced by that same check, and nothing missed with up to 5 minutes of
LinkedIn delay.

## Still open: needs data from the phone

- **LinkedIn's delay.** In Delivery times, posted to found should have a median near 5 minutes
  and 90% within 10 if LinkedIn shows postings at once. Often above 10 means LinkedIn is late.
  Values near 15 mean some postings are being missed. In that case, widen the window's overlap
  and extend `DeliveryTimeTest` to the measured delay.
- **Late alarms.** Recent checks marks any check the phone ran a minute or more late. Many such
  checks point to the phone's battery settings.
- **The floor.** With 5 searches and one LinkedIn request every 2 minutes, each search is checked
  every 10 minutes. Going lower means fewer searches or more requests, and more slow-downs.
- **Reposted jobs** may carry LinkedIn's original "posted" time, which would show as a long
  posted-to-found time. The median is not affected by a few of them.

## Verification

Install 1.13 and use it for a day, then open Delivery times and share the report. Expected:
found to notified 0 for every job, and posted to found within about 10 minutes unless LinkedIn
is late.

## Follow-up: one LinkedIn request a minute (1.14)

With 1.13 working as expected, the spacing between LinkedIn requests was halved to 1 minute, so each
of the 5 searches comes round every 5 minutes. First, the slow-down pause, the email and WhatsApp
gap and the remote boards' pace were given their own 10 minutes (they had shared the search
interval), and the tests were rewritten in terms of the spacing and interval. The simulation gave
1.13's times unchanged before the switch.

| LinkedIn shows postings at once | 1.13 (2 min) | 1.14 (1 min) |
| --- | --- | --- |
| posted to notified, median / 90% / max | 5 / 9 / 9 min | 2 / 4 / 4 min |
| largest LinkedIn delay with nothing missed | 4 min | 9 min |

The cost is twice the requests to LinkedIn (60 an hour) and twice the wake-ups, close to how often
Android lets an app wake a sleeping phone. Watch Recent checks for "asked to slow down" and for
checks the phone ran late; if either becomes common, set `Scheduler.SPACING_MINUTES` back to 2.
