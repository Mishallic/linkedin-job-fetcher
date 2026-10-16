# LinkedIn Job Fetcher

Watches your LinkedIn job searches and tells you about new and reposted jobs soon after
they're posted. It uses LinkedIn's public, logged-out job search, so it needs no LinkedIn
account or API key.

There are three versions of it:

| Version | Where it runs | How it tells you |
| --- | --- | --- |
| **Job Watch** ([`android/`](android)) | Android phone: LinkedIn every 10 minutes, remote job boards every 6 hours | Phone notification, plus optional email and WhatsApp |
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

**New and read:** a filled dot marks a new job, an empty circle a read one. Tap the dot to
switch it, or open the job to mark it read. Long-press a job to select several, then
**Mark as read**, **Mark as new** or **Select all** from the bar at the bottom.

**Remote job boards:** besides LinkedIn, Job Watch reads the official feeds of We Work
Remotely, Himalayas, Jobicy, Remote OK and Remotive. It keeps jobs whose title contains one
of your positions and that are open worldwide or to the places in your searches (or to
EMEA, the Middle East or the GCC). These boards ask apps to link to the original listing,
name them as the source, and call sparingly (Remotive: at most 4 times a day), so they're
checked every 6 hours, and every job shows which board it came from.

**Build** (JDK 17 and the Android SDK with platform 35):

```
cd android
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest    # unit and Robolectric UI tests; no network needed
```

`local.properties` (git-ignored) must point at the SDK: `sdk.dir=C\:\\path\\to\\Android\\Sdk`.
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
  When that happens the apps stop the current round after one request and try again at the
  next check.
- Use "City, Country" for locations. "Jordan" on its own is read by LinkedIn as Danville, PA,
  so use "Amman, Jordan".
- LinkedIn's terms don't allow automated scraping. This is meant for light personal use.
