"""Simple desktop UI for linkedin_jobs.py.

Add as many searches (position + location) as you like; each one is checked
for new LinkedIn postings every 10 minutes. New jobs appear at the top of the
list, highlighted, and are also saved to jobs.csv. Double-click a job to open
it in your browser. Searches are remembered between runs.

Click "Notifications..." to also get each round's new jobs by email and/or
WhatsApp (settings are saved in settings.json next to this file).

Click "Phone..." for a QR code that opens the phone version (served by
mobile_server.py over your Wi-Fi) on your iPhone.

Run:  pythonw jobs_ui.py    (or `python jobs_ui.py` to keep a console window)
"""

import csv
import ctypes
import json
import queue
import sys
import threading
import tkinter as tk
import webbrowser
from datetime import datetime, timedelta
from tkinter import messagebox, ttk

import qrcode
import requests

import mobile_server
from linkedin_jobs import (HEADERS, HERE, append_csv, build_params, fetch_new_jobs,
                           load_seen, save_seen, search_label)
from notify import DEFAULT_SETTINGS, send_enabled

INTERVAL_MINUTES = 10
# Each check reads every job posted or reposted in the last 15 minutes. The 5-minute
# overlap catches jobs LinkedIn lists late; jobs already sent are skipped, so no repeats.
POSTED_WITHIN = "15m"
HISTORY_ROWS = 500
SEARCHES_FILE = HERE / "searches.json"
JOBS_FILE = HERE / "jobs.csv"
SEEN_FILE = HERE / "seen_jobs.json"
SETTINGS_FILE = HERE / "settings.json"
APP_TITLE = "LinkedIn Jobs"
SAMPLE_JOB = {"job_id": "0", "title": "Test job - your notifications work!", "company": "LinkedIn Jobs app",
              "location": "Anywhere", "posted": "", "url": "https://www.linkedin.com/jobs/", "search": "Test"}


class JobsApp:
    def __init__(self, root):
        self.root = root
        self.searches = self.load_searches()
        self.settings = self.load_settings()
        self.seen = load_seen(SEEN_FILE)  # only touched by the worker thread after this
        self.todo = queue.Queue()  # searches waiting to be fetched
        self.done = queue.Queue()  # (search, jobs, found_at, error) back from the worker
        self.ui_calls = queue.Queue()  # callables from background threads, run on the UI thread
        self.pending = set()
        self.batch = []  # new jobs from the current round, sent once every search has finished
        self.job_data = {}  # job_id -> what the jobs list shows, for the phone page
        self.after_id = None

        self.build_ui()
        self.load_history()
        for search in self.searches:
            self.add_search_row(search)

        try:
            self.phone_url, self.phone_error = mobile_server.start(self), None
        except OSError as exc:
            self.phone_url, self.phone_error = None, str(exc)

        threading.Thread(target=self.worker, daemon=True).start()
        self.check_all()
        self.poll_results()

    # ---------- layout ----------

    def build_ui(self):
        self.root.title(APP_TITLE)
        self.root.geometry("1100x680")
        self.root.minsize(800, 500)

        form = ttk.Frame(self.root, padding=(10, 10, 10, 0))
        form.pack(fill="x")
        ttk.Label(form, text="Position").grid(row=0, column=0, sticky="w")
        ttk.Label(form, text="Location").grid(row=0, column=1, sticky="w")
        self.keywords_entry = ttk.Entry(form, width=35)
        self.keywords_entry.grid(row=1, column=0, padx=(0, 8))
        self.location_entry = ttk.Entry(form, width=35)
        self.location_entry.grid(row=1, column=1, padx=(0, 8))
        ttk.Button(form, text="Add search", command=self.add_search).grid(row=1, column=2)
        # LinkedIn can misread a bare country name ("Jordan" matched Danville, PA in testing).
        ttk.Label(form, text='Tip: "City, Country" works best for location, e.g. "Amman, Jordan"',
                  foreground="#666").grid(row=1, column=3, padx=10)
        for entry in (self.keywords_entry, self.location_entry):
            entry.bind("<Return>", self.add_search)
        self.keywords_entry.focus()

        searches = ttk.LabelFrame(self.root, text="Searches", padding=8)
        searches.pack(fill="x", padx=10, pady=10)
        self.search_tree = self.make_tree(searches, height=4, columns=[
            ("position", "Position", 220), ("location", "Location", 180),
            ("status", "Status", 380), ("found", "New jobs", 80),
        ])
        self.search_tree.pack(fill="x")
        buttons = ttk.Frame(searches)
        buttons.pack(fill="x", pady=(6, 0))
        ttk.Button(buttons, text="Remove selected", command=self.remove_search).pack(side="left")
        ttk.Button(buttons, text="Check now", command=self.check_all).pack(side="left", padx=6)
        ttk.Button(buttons, text="Notifications...", command=self.open_settings).pack(side="left")
        ttk.Button(buttons, text="Phone...", command=self.open_phone).pack(side="left", padx=6)
        self.notify_var = tk.StringVar(value=self.describe_notifications())
        ttk.Label(buttons, textvariable=self.notify_var).pack(side="left", padx=10)
        self.next_check_var = tk.StringVar()
        ttk.Label(buttons, textvariable=self.next_check_var).pack(side="right")

        jobs = ttk.LabelFrame(self.root, text="Jobs (double-click to open)", padding=8)
        jobs.pack(fill="both", expand=True, padx=10, pady=(0, 10))
        self.job_tree = self.make_tree(jobs, height=15, columns=[
            ("found", "Found", 125), ("title", "Title", 300), ("company", "Company", 190),
            ("location", "Location", 200), ("posted", "Posted", 85), ("search", "Search", 170),
            ("url", "", 0),
        ])
        self.job_tree["displaycolumns"] = ("found", "title", "company", "location", "posted", "search")
        self.job_tree.tag_configure("new", background="#dff0d8")
        scrollbar = ttk.Scrollbar(jobs, orient="vertical", command=self.job_tree.yview)
        self.job_tree.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        self.job_tree.pack(fill="both", expand=True)
        self.job_tree.bind("<Double-1>", self.open_job)

    @staticmethod
    def make_tree(parent, height, columns):
        tree = ttk.Treeview(parent, columns=[c[0] for c in columns], show="headings", height=height)
        for key, heading, width in columns:
            tree.heading(key, text=heading, anchor="w")
            tree.column(key, width=width, anchor="w", stretch=key not in ("found", "posted"))
        return tree

    # ---------- searches ----------

    def add_search(self, _event=None):
        error = self.create_search(self.keywords_entry.get(), self.location_entry.get())
        if error:
            messagebox.showwarning(APP_TITLE, error)
            return
        self.keywords_entry.delete(0, "end")
        self.location_entry.delete(0, "end")

    def create_search(self, keywords, location):
        """Add a search and check it right away. Returns an error message, or None."""
        search = {"keywords": keywords.strip(), "location": location.strip()}
        if not search["keywords"]:
            return "Enter a position to search for."
        if self.search_tree.exists(search_label(**search)):
            return "That search is already in the list."
        self.searches.append(search)
        self.save_searches()
        self.add_search_row(search)
        self.enqueue(search)
        return None

    def add_search_row(self, search):
        self.search_tree.insert("", "end", iid=search_label(**search), values=(
            search["keywords"], search["location"] or "Anywhere", "Waiting", 0))

    def remove_search(self):
        self.delete_searches(self.search_tree.selection())

    def delete_searches(self, labels):
        labels = {label for label in labels if self.search_tree.exists(label)}
        if not labels:
            return
        self.search_tree.delete(*labels)
        self.searches = [s for s in self.searches if search_label(**s) not in labels]
        self.save_searches()

    def load_searches(self):
        try:
            return json.loads(SEARCHES_FILE.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return []

    def save_searches(self):
        SEARCHES_FILE.write_text(json.dumps(self.searches, indent=2), encoding="utf-8")

    # ---------- checking ----------

    def check_all(self):
        """Queue every search now, then schedule the next round."""
        if self.after_id:
            self.root.after_cancel(self.after_id)
        for search in self.searches:
            self.enqueue(search)
        next_check = datetime.now() + timedelta(minutes=INTERVAL_MINUTES)
        self.next_check_var.set(f"Checks every {INTERVAL_MINUTES} min. Next: {next_check:%H:%M}")
        self.after_id = self.root.after(INTERVAL_MINUTES * 60_000, self.check_all)

    def enqueue(self, search):
        label = search_label(**search)
        if label in self.pending:
            return
        self.pending.add(label)
        self.search_tree.set(label, "status", "Checking...")
        self.todo.put(search)

    def worker(self):
        """Background thread: fetches one search at a time so the window never freezes."""
        session = requests.Session()
        session.headers.update(HEADERS)
        while True:
            search = self.todo.get()
            found_at = f"{datetime.now():%Y-%m-%d %H:%M}"
            try:
                params = build_params(search["keywords"], search["location"], POSTED_WITHIN)
                jobs = fetch_new_jobs(session, params, self.seen)
                if jobs:
                    append_csv(JOBS_FILE, jobs, found_at, search_label(**search))
                    self.seen.update(j["job_id"] for j in jobs)
                    save_seen(SEEN_FILE, self.seen)
            except Exception as exc:  # keep the worker alive whatever goes wrong
                self.done.put((search, [], found_at, str(exc)))
            else:
                self.done.put((search, jobs, found_at, None))

    def poll_results(self):
        while True:
            try:
                search, jobs, found_at, error = self.done.get_nowait()
            except queue.Empty:
                break
            label = search_label(**search)
            self.pending.discard(label)
            self.batch.extend({**job, "search": label} for job in jobs)
            for job in reversed(jobs):  # results come newest first; keep that order on top
                self.add_job_row(job, found_at, label, new=True)
            if self.search_tree.exists(label):
                checked = found_at[-5:]
                status = f"Error at {checked}: {error}" if error else f"Checked at {checked}: {len(jobs)} new"
                self.search_tree.set(label, "status", status)
                total = int(self.search_tree.set(label, "found")) + len(jobs)
                self.search_tree.set(label, "found", total)
            if jobs:
                self.root.bell()
        if self.batch and not self.pending:  # round finished: send everything it found at once
            self.send_batch(self.batch)
            self.batch = []
        while True:
            try:
                self.ui_calls.get_nowait()()
            except queue.Empty:
                break
        self.root.after(200, self.poll_results)

    # ---------- phone ----------

    def run_on_ui(self, fn, timeout=10):
        """Run fn on the Tk thread and return its result (used by the phone server's threads)."""
        box = queue.Queue(maxsize=1)

        def call():
            try:
                box.put((fn(), None))
            except Exception as exc:
                box.put((None, exc))

        self.ui_calls.put(call)
        try:
            result, error = box.get(timeout=timeout)
        except queue.Empty:
            raise RuntimeError("The PC app didn't answer. Close any open window on the PC and try again.")
        if error:
            raise error
        return result

    def web_state(self):
        """Everything the phone page shows, as plain data."""
        searches = []
        for search in self.searches:
            label = search_label(**search)
            searches.append({"label": label, "keywords": search["keywords"],
                             "location": search["location"] or "Anywhere",
                             "status": str(self.search_tree.set(label, "status")),
                             "found": int(self.search_tree.set(label, "found"))})
        jobs = [{**self.job_data[iid], "new": self.job_tree.tag_has("new", iid)}
                for iid in self.job_tree.get_children()[:300]]
        return {"searches": searches, "jobs": jobs,
                "next_check": self.next_check_var.get(), "notify": self.notify_var.get()}

    def open_phone(self):
        win = tk.Toplevel(self.root)
        win.title("Use on your iPhone")
        win.transient(self.root)
        win.resizable(False, False)
        frame = ttk.Frame(win, padding=16)
        frame.pack()
        if not self.phone_url:
            ttk.Label(frame, text=f"The phone page couldn't start:\n{self.phone_error}\n\n"
                                  f"Another program may be using port {mobile_server.DEFAULT_PORT}.",
                      wraplength=380).pack()
            return

        qr = qrcode.QRCode(border=3)
        qr.add_data(self.phone_url)
        qr.make(fit=True)
        matrix = qr.get_matrix()
        cell = max(4, 270 // len(matrix))
        canvas = tk.Canvas(frame, width=cell * len(matrix), height=cell * len(matrix),
                           background="white", highlightthickness=0)
        for y, row in enumerate(matrix):
            for x, dark in enumerate(row):
                if dark:
                    canvas.create_rectangle(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell,
                                            fill="black", width=0)
        canvas.pack()

        link = ttk.Entry(frame, width=52)
        link.insert(0, self.phone_url)
        link.configure(state="readonly")
        link.pack(pady=(12, 10))
        ttk.Label(frame, justify="left", wraplength=400, text=(
            "1. Connect your iPhone to the same Wi-Fi as this PC.\n"
            "2. Point the iPhone Camera at the code and tap the link.\n"
            "3. In Safari, tap Share, then \"Add to Home Screen\".\n\n"
            "This PC must be on with this app open. If Windows asks whether to allow "
            "Python on the network, allow it on private networks."
        )).pack(anchor="w")

    # ---------- notifications ----------

    def send_batch(self, jobs, settings=None, on_done=None):
        settings = dict(settings or self.settings)
        if not (settings["email_enabled"] or settings["whatsapp_enabled"]):
            return

        def run():
            results = send_enabled(settings, jobs)
            self.ui_calls.put(lambda: (on_done or self.show_send_results)(results, len(jobs)))

        self.notify_var.set("Sending...")
        threading.Thread(target=run, daemon=True).start()

    def show_send_results(self, results, count):
        now = f"{datetime.now():%H:%M}"
        sent = [channel for channel, error in results.items() if error is None]
        parts = [f"Sent {count} job(s) by {' + '.join(sent)} at {now}"] if sent else []
        parts += [f"{channel} failed at {now}: {error}" for channel, error in results.items() if error]
        self.notify_var.set(". ".join(parts))

    def describe_notifications(self):
        channels = [name for name, key in (("Email", "email_enabled"), ("WhatsApp", "whatsapp_enabled"))
                    if self.settings[key]]
        return f"Notifications: {' + '.join(channels)}" if channels else "Notifications: off"

    def load_settings(self):
        try:
            return {**DEFAULT_SETTINGS, **json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))}
        except FileNotFoundError:
            return dict(DEFAULT_SETTINGS)

    def open_settings(self):
        win = tk.Toplevel(self.root)
        win.title("Notifications")
        win.transient(self.root)
        win.resizable(False, False)
        win.grab_set()
        frame = ttk.Frame(win, padding=14)
        frame.pack(fill="both")
        fields = {key: (tk.BooleanVar if isinstance(value, bool) else tk.StringVar)(value=self.settings[key])
                  for key, value in DEFAULT_SETTINGS.items()}
        row = 0

        def add(widget, **grid):
            nonlocal row
            widget.grid(**{"sticky": "w", "pady": 2, **grid, "row": row})
            row += 1

        def field(label, key, hidden=False):
            ttk.Label(frame, text=label).grid(row=row, column=0, sticky="w", padx=(18, 8))
            add(ttk.Entry(frame, textvariable=fields[key], width=40, show="*" if hidden else ""), column=1)

        def hint(text):
            add(ttk.Label(frame, text=text, foreground="#666", wraplength=430), column=0, columnspan=2, padx=(18, 0))

        add(ttk.Checkbutton(frame, text="Email me each round of new jobs", variable=fields["email_enabled"]),
            column=0, columnspan=2)
        field("Send to", "email_to")
        field("SMTP server", "smtp_host")
        field("Port", "smtp_port")
        field("Sender email", "smtp_user")
        field("Password", "smtp_password", hidden=True)
        hint("Gmail: turn on 2-Step Verification and use an App Password "
             "(myaccount.google.com/apppasswords), not your normal password.")
        add(ttk.Separator(frame), column=0, columnspan=2, pady=10, sticky="ew")

        add(ttk.Checkbutton(frame, text="WhatsApp me each round of new jobs (via CallMeBot)",
                            variable=fields["whatsapp_enabled"]), column=0, columnspan=2)
        field("Your number", "whatsapp_phone")
        field("API key", "whatsapp_apikey", hidden=True)
        hint("Number with country code, e.g. +971501234567. To get a free API key, send "
             "\"I allow callmebot to send me messages\" on WhatsApp to the number listed at "
             "callmebot.com/blog/free-api-whatsapp-messages")

        def collect():
            values = {key: var.get() for key, var in fields.items()}
            values = {key: v.strip() if isinstance(v, str) else v for key, v in values.items()}
            problems = []
            if values["email_enabled"] and not (all(values[k] for k in ("email_to", "smtp_host", "smtp_user",
                                                                         "smtp_password"))
                                                and values["smtp_port"].isdigit()):
                problems.append("Fill in all the email fields (port must be a number).")
            if values["whatsapp_enabled"] and not (values["whatsapp_phone"] and values["whatsapp_apikey"]):
                problems.append("Fill in your WhatsApp number and API key.")
            if problems:
                messagebox.showwarning(APP_TITLE, "\n".join(problems), parent=win)
                return None
            return values

        def send_test():
            values = collect()
            if values is None:
                return
            if not (values["email_enabled"] or values["whatsapp_enabled"]):
                messagebox.showinfo(APP_TITLE, "Tick email and/or WhatsApp first.", parent=win)
                return

            def report(results, _count):
                self.notify_var.set(self.describe_notifications())
                if win.winfo_exists():
                    test_button.configure(text="Send test", state="normal")
                lines = [f"{channel}: {'sent' if error is None else 'failed - ' + error}"
                         for channel, error in results.items()]
                show = messagebox.showerror if any(results.values()) else messagebox.showinfo
                show(APP_TITLE, "\n".join(lines), parent=win if win.winfo_exists() else self.root)

            test_button.configure(text="Sending...", state="disabled")
            self.send_batch([SAMPLE_JOB], values, on_done=report)

        def save():
            values = collect()
            if values is None:
                return
            self.settings = values
            SETTINGS_FILE.write_text(json.dumps(values, indent=2), encoding="utf-8")
            self.notify_var.set(self.describe_notifications())
            win.destroy()

        buttons = ttk.Frame(frame)
        add(buttons, column=0, columnspan=2, pady=(12, 0))
        test_button = ttk.Button(buttons, text="Send test", command=send_test)
        test_button.pack(side="left")
        ttk.Button(buttons, text="Save", command=save).pack(side="left", padx=6)
        ttk.Button(buttons, text="Cancel", command=win.destroy).pack(side="left")

    # ---------- jobs list ----------

    def add_job_row(self, job, found_at, search, new=False):
        if self.job_tree.exists(job["job_id"]):
            return
        self.job_tree.insert("", 0, iid=job["job_id"], tags=("new",) if new else (), values=(
            found_at, job["title"], job["company"], job["location"], job["posted"], search, job["url"]))
        self.job_data[job["job_id"]] = {"found": found_at, "title": job["title"], "company": job["company"],
                                        "location": job["location"], "posted": job["posted"],
                                        "search": search, "url": job["url"]}

    def load_history(self):
        """Show jobs found in earlier sessions (not highlighted)."""
        try:
            with JOBS_FILE.open(newline="", encoding="utf-8-sig") as f:
                rows = list(csv.DictReader(f))[-HISTORY_ROWS:]
        except FileNotFoundError:
            return
        for row in rows:
            self.add_job_row(row, row["found_at"][:16], row.get("search") or "")

    def open_job(self, event):
        row = self.job_tree.identify_row(event.y)
        if row:
            webbrowser.open(self.job_tree.set(row, "url"))


def already_running():
    """True if another copy of the app is open (two copies would send every job twice)."""
    if sys.platform != "win32":
        return False
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateMutexW(None, False, "LinkedInJobsApp")  # released when this process exits
    return ctypes.get_last_error() == 183  # ERROR_ALREADY_EXISTS


def main():
    root = tk.Tk()
    if already_running():
        root.withdraw()
        messagebox.showinfo(APP_TITLE, "LinkedIn Jobs is already open. Look for it on the taskbar.")
        root.destroy()
        return
    JobsApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
