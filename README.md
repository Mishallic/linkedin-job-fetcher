# LinkedIn Job Fetcher

[![Android](https://github.com/Mishallic/linkedin-job-fetcher/actions/workflows/android.yml/badge.svg)](https://github.com/Mishallic/linkedin-job-fetcher/actions/workflows/android.yml)

Watches your LinkedIn job searches and tells you about new and reposted jobs soon after
they're posted. It uses LinkedIn's public, logged-out job search, so it needs no LinkedIn
account or API key.

There are three versions of it:

| Version | Where it runs | How it tells you |
| --- | --- | --- |
| **Job Watch** ([`android/`](android)) | Android phone: each LinkedIn search every 10 minutes, plus remote job boards and job sites | Phone notification, plus optional email and WhatsApp |
| **Desktop app** (`jobs_ui.py`) | Windows PC, while the window is open | Email and WhatsApp; a phone page over Wi-Fi |
| **iPhone script** ([`iphone/Jobs.js`](iphone/Jobs.js)) | iPhone, in the free Scriptable app, when iOS allows | iPhone notification, plus optional WhatsApp |

Each check reads every page of results posted since the previous check (plus a 5-minute
overlap), so a late or skipped check doesn't miss jobs. Jobs you've already been told about
are never repeated.

## Android app (Job Watch)

**Install:** download `JobWatch-<version>.apk` from this repository's
[Releases](../../releases) on the phone, open it, and allow installing from that source.
On first launch, allow notifications and tap **Allow** on the "Finish setting up" card so
Android doesn't pause the checks. The **Check every 10 minutes** switch turns background
checks off and on.

**How often LinkedIn is asked:** one search every 2 minutes at most, whatever starts the
check: the alarm, the refresh button or a new search. Each search's turn comes 10 minutes
after its last one, so with 5 searches, the most Job Watch takes, LinkedIn gets 5 requests
every 10 minutes rather than a burst of them; with fewer, the app sleeps until the next turn.
A search with more new jobs than one page holds reads one page per turn. If LinkedIn asks to
slow down, background checks leave it alone for 10 minutes; the refresh button can try again
after 2. The phone is told about new jobs by the check that finds them; email and WhatsApp
go at most every 10 minutes, all together, or at once for a check you ask for. Opening the app
doesn't check again; the refresh button checks the next search at once, or says when it goes.

**Recent checks**, under the switch, lists the last 100 checks: when each started, what
started it (the alarm, the refresh button, a new search, opening the app), how long it took
and what it found. A check the phone stopped before it finished shows as such, and so does
one the phone ran a minute or more late.

**Delivery times**, next to it, shows how long the last day's LinkedIn jobs took from being
posted to being found, and from being found to the notification: the median and the time 9 in
10 jobs were within, and each job's own times. **Share** sends it all as text. How these
measures were used to find why jobs came late is written up in
[docs/investigations/2026-10-19-late-notifications.md](docs/investigations/2026-10-19-late-notifications.md).

**New, read and deleted:** a filled dot marks a new job, an empty circle a read one. Tap the
dot to switch it, or open the job to mark it read. Long-press a job to select several, then
**Mark as read**, **Mark as new** or **Select all** from the bar at the bottom, or the trash
button to delete them (with **Undo** for a few seconds). Deleted jobs don't come back.

**Remote job boards:** besides LinkedIn, Job Watch reads the official feeds of We Work
Remotely, Himalayas, Remote OK, Jobicy and Remotive, and the posts in Hacker News's monthly
"Who is hiring?" thread (through its official search API). It keeps jobs whose title contains one
of your positions and that are open worldwide or to the places in your searches (or to
EMEA, the Middle East or the GCC). These boards ask apps to link to the original listing
and name them as the source, so every job shows which board it came from. The boards are
read on the same checks as LinkedIn, each only as often as its terms allow:

| Board | Read |
| --- | --- |
| We Work Remotely, Himalayas, Remote OK | every 10 minutes (no limit) |
| Jobicy | hourly (its limit) |
| Hacker News | hourly, only the posts since the last read |
| Remotive | every 6 hours (its limit: 4 times a day) |

On mobile data every board is read at most hourly: reading them all downloads about 400 KB,
which every 10 minutes would come to about 60 MB a day.

**Job sites:** ReliefWeb (run by the UN's humanitarian office, OCHA) and Jooble (a job search
engine) look for your positions in the places of your searches, through their official feed
and API, and their jobs show under the search they match. ReliefWeb needs no key; it lists UN
and NGO jobs, few of them in software or data, and asks feed readers to wait an hour between
reads. Jooble covers the UAE and Saudi Arabia but not Jordan, and needs a free API key: get
one at [jooble.org/api/about](https://jooble.org/api/about) (its form asks for a website) and
paste it into the **Job sites** card. Each is read at most hourly, Jooble one search per hour
each. A job already found on LinkedIn isn't announced again from a job site.

**Build** (JDK 17 and the Android SDK with platform 35):

```
cd android
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest    # unit, simulation and Robolectric UI tests; no network needed
```

`local.properties` (git-ignored) must point at the SDK: `sdk.dir=C\:\\path\\to\\Android\\Sdk`.
GitHub Actions runs the same tests and the release build on every push and pull request.
`LIVE_LINKEDIN=1 ./gradlew testReleaseUnitTest --tests "*liveFetchFromLinkedIn"` checks, with one
real request, that LinkedIn's results page still reads.
Release builds are signed with the building machine's debug key, so an update installs over
an earlier version only when both were built on the same machine.

## Desktop app

```
pip install -r requirements.txt
python jobs_ui.py                                    # window with searches, jobs and alerts
python linkedin_jobs.py -k "data analyst" -l "Dubai" # or a single search in the console
```

Email uses SMTP (for Gmail, an App Password); WhatsApp uses the free CallMeBot service.
The files it writes while running (`settings.json`, `searches.json`, `jobs.csv`, and so on)
hold passwords and personal data and are kept out of git.

## iPhone script

Copy [`iphone/Jobs.js`](iphone/Jobs.js) into a new script in the Scriptable app and run it
once. Background checks come from a Scriptable home-screen widget or a Shortcuts automation;
iOS decides how often those run.

## Good to know

- LinkedIn limits how often one internet connection can use its job search (HTTP 429).
  Job Watch asks for one search every 2 minutes at most, and after a refusal leaves LinkedIn
  alone for 10 minutes. The desktop app and the iPhone script stop the current round after one
  request and try again at the next check.
- Use "City, Country" for locations. "Jordan" on its own is read by LinkedIn as Danville, PA,
  so use "Amman, Jordan".
- LinkedIn's terms don't allow automated scraping. This is meant for light personal use.
