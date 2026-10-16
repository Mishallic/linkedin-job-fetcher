"""Poll LinkedIn's public job search on an interval and report new postings.

Uses the logged-out "jobs-guest" endpoint that powers linkedin.com/jobs, so no
account or API key is needed. New jobs are printed and appended to a CSV, and
job IDs already seen are remembered in a JSON file so each job is reported
only once, even across restarts.

Usage:
    python linkedin_jobs.py --keywords "python developer" --location "Dubai"
    python linkedin_jobs.py -k "data engineer" -l "United States" --work-type remote
    python linkedin_jobs.py -k "devops" --once        # one fetch, then exit
"""

import argparse
import csv
import json
import random
import sys
import time
from datetime import datetime
from pathlib import Path

import requests
from bs4 import BeautifulSoup

SEARCH_URL = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search"
JOB_URL = "https://www.linkedin.com/jobs/view/{}/"
HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    ),
    "Accept-Language": "en-US,en;q=0.9",
}
# LinkedIn's f_TPR filter: jobs posted (or reposted) within the last N seconds.
POSTED_WITHIN = {"10m": "r600", "15m": "r900", "1h": "r3600", "24h": "r86400",
                 "week": "r604800", "month": "r2592000"}
MAX_PAGES = 100  # safety stop only; a 15-minute window is normally a single page
# LinkedIn's f_WT filter.
WORK_TYPES = {"onsite": "1", "remote": "2", "hybrid": "3"}
CSV_FIELDS = ["found_at", "search", "job_id", "title", "company", "location", "posted", "url"]
HERE = Path(__file__).resolve().parent


def log(message):
    print(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {message}", flush=True)


def text_of(card, selector):
    element = card.select_one(selector)
    return element.get_text(" ", strip=True) if element else ""


def parse_jobs(html):
    soup = BeautifulSoup(html, "html.parser")
    jobs = []
    for card in soup.select('[data-entity-urn^="urn:li:jobPosting:"]'):
        job_id = card["data-entity-urn"].rsplit(":", 1)[-1]
        posted = card.select_one("time")
        jobs.append({
            "job_id": job_id,
            "title": text_of(card, ".base-search-card__title"),
            "company": text_of(card, ".base-search-card__subtitle"),
            "location": text_of(card, ".job-search-card__location"),
            "posted": posted.get("datetime", "") if posted else "",
            "url": JOB_URL.format(job_id),
        })
    return jobs


def build_params(keywords, location="", posted_within="15m", work_types=None):
    params = {
        "keywords": keywords,
        "location": location,
        "f_TPR": POSTED_WITHIN[posted_within],
        "sortBy": "DD",  # newest first
    }
    if work_types:
        params["f_WT"] = ",".join(WORK_TYPES[w] for w in work_types)
    return params


def search_label(keywords, location=""):
    return f"{keywords} @ {location}" if location else keywords


def fetch_page(session, params, retries=4):
    for attempt in range(retries):
        response = session.get(SEARCH_URL, params=params, timeout=20)
        if response.status_code == 200:
            return response.text
        # LinkedIn answers 400 once you page past the last result.
        if response.status_code == 400 and params.get("start", 0) > 0:
            return ""
        # 429 = rate limited, 999 = LinkedIn's bot block; back off and retry.
        if response.status_code in (429, 999) or response.status_code >= 500:
            wait = 30 * 2 ** attempt
            log(f"LinkedIn returned HTTP {response.status_code}; retrying in {wait}s")
            time.sleep(wait)
            continue
        response.raise_for_status()
    raise RuntimeError(f"gave up after {retries} attempts (last HTTP {response.status_code})")


def fetch_new_jobs(session, params, seen):
    """Read every page of results and return the jobs not reported before.

    LinkedIn's "newest first" order is loose, so stopping early could skip jobs.
    """
    new_jobs, found = [], set()
    start = 0
    for page in range(MAX_PAGES):
        if page:
            time.sleep(random.uniform(1.5, 4))  # be polite between pages
        jobs = parse_jobs(fetch_page(session, {**params, "start": start}))
        unread = [j for j in jobs if j["job_id"] not in found]
        if not unread:
            break  # past the last page (or LinkedIn repeated one)
        found.update(j["job_id"] for j in unread)
        new_jobs.extend(j for j in unread if j["job_id"] not in seen)
        start += len(jobs)
    return new_jobs


def load_seen(path):
    try:
        return set(json.loads(path.read_text(encoding="utf-8")))
    except FileNotFoundError:
        return set()


def save_seen(path, seen):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(sorted(seen)), encoding="utf-8")
    tmp.replace(path)


def append_csv(path, jobs, found_at, search=""):
    is_new_file = not path.exists()
    with path.open("a", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        if is_new_file:
            writer.writeheader()
        for job in jobs:
            writer.writerow({"found_at": found_at, "search": search, **job})


def report(jobs):
    if not jobs:
        log("No new jobs.")
        return
    log(f"{len(jobs)} new job(s):")
    for job in jobs:
        where = f" ({job['location']})" if job["location"] else ""
        posted = f"  posted {job['posted']}" if job["posted"] else ""
        print(f"  - {job['title']} @ {job['company']}{where}{posted}")
        print(f"    {job['url']}")
    sys.stdout.flush()


def parse_args():
    parser = argparse.ArgumentParser(description="Fetch new LinkedIn jobs on an interval.")
    parser.add_argument("-k", "--keywords", required=True, help='e.g. "python developer"')
    parser.add_argument("-l", "--location", default="", help='e.g. "Dubai" or "United States"')
    parser.add_argument("--work-type", nargs="+", choices=WORK_TYPES,
                        help="limit to onsite / remote / hybrid (one or more)")
    parser.add_argument("--posted-within", choices=POSTED_WITHIN, default="15m",
                        help="only consider jobs posted or reposted within this window; keep it a bit "
                             "longer than --interval so no job falls between checks (default: 15m)")
    parser.add_argument("--interval", type=int, default=10,
                        help="minutes between fetches (default: 10)")
    parser.add_argument("--output", type=Path, default=HERE / "jobs.csv",
                        help="CSV file new jobs are appended to")
    parser.add_argument("--state", type=Path, default=HERE / "seen_jobs.json",
                        help="file that remembers already-reported job IDs")
    parser.add_argument("--once", action="store_true",
                        help="fetch once and exit (for Task Scheduler / cron)")
    return parser.parse_args()


def main():
    # Job titles can contain any script; don't crash if output is redirected.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = parse_args()

    params = build_params(args.keywords, args.location, args.posted_within, args.work_type)
    label = search_label(args.keywords, args.location)

    session = requests.Session()
    session.headers.update(HEADERS)
    seen = load_seen(args.state)

    if not args.once:
        log(f'Watching LinkedIn for "{args.keywords}" in "{args.location or "anywhere"}" '
            f"every {args.interval} min. Press Ctrl+C to stop.")
    try:
        while True:
            try:
                new_jobs = fetch_new_jobs(session, params, seen)
            except (requests.RequestException, RuntimeError) as exc:
                log(f"Fetch failed: {exc}")
            else:
                report(new_jobs)
                if new_jobs:
                    append_csv(args.output, new_jobs, f"{datetime.now():%Y-%m-%d %H:%M:%S}", label)
                    seen.update(j["job_id"] for j in new_jobs)
                    save_seen(args.state, seen)
            if args.once:
                break
            time.sleep(args.interval * 60)
    except KeyboardInterrupt:
        log("Stopped.")


if __name__ == "__main__":
    main()
