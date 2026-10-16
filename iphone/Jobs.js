// Variables used by Scriptable.
// These must be at the very top of the file. Do not edit.
// icon-color: blue; icon-glyph: briefcase;

// LinkedIn Jobs for iPhone (runs in the free Scriptable app).
// Watches your LinkedIn job searches and sends an iPhone notification when new jobs
// are posted or reposted. Open it in Scriptable to manage searches and browse jobs.
// Background checks run from a home-screen widget and/or a Shortcuts automation;
// tap "Set up background checks" inside the script for the steps.

const STARTING_SEARCHES = [ // used the first time only; change searches inside the app
  { keywords: "software engineer", location: "Amman, Jordan" },
  { keywords: "software engineer", location: "United Arab Emirates" },
  { keywords: "data analyst", location: "United Arab Emirates" },
  { keywords: "software engineer", location: "Saudi Arabia" },
  { keywords: "data analyst", location: "Saudi Arabia" },
];

// iOS decides when scripts may run in the background, so each check looks back to the
// previous check (plus an overlap) instead of a fixed 10 minutes. Nothing falls in a gap.
const MIN_WINDOW_MINUTES = 15;
const OVERLAP_MINUTES = 5;
const MAX_WINDOW_HOURS = 24; // after a long gap (phone off), catch up on at most one day
const MIN_MINUTES_BETWEEN_CHECKS = 2; // widget and automation firing together check once
const MAX_PAGES = 40; // safety stop only
const KEEP_JOBS = 300;
const KEEP_SEEN = 5000;
const WHATSAPP_MAX_JOBS = 15;

const SEARCH_URL = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search";
const JOB_URL = "https://www.linkedin.com/jobs/view/";
const CALLMEBOT_URL = "https://api.callmebot.com/whatsapp.php";
const KEYCHAIN_KEY = "linkedin-jobs-callmebot-key";
const HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
  "Accept-Language": "en-US,en;q=0.9",
};
const ACCENT = new Color("#2563eb");
const MUTED = Color.gray();

const fm = FileManager.local();
const DATA_PATH = fm.joinPath(fm.documentsDirectory(), "linkedin-jobs.json");

// ---------- data ----------

function loadData() {
  if (fm.fileExists(DATA_PATH)) {
    try {
      return JSON.parse(fm.readString(DATA_PATH));
    } catch (e) {
      // unreadable file: start over below
    }
  }
  return {
    searches: STARTING_SEARCHES.map((s) => newSearch(s.keywords, s.location)),
    seen: [],
    jobs: [],
    lastRun: 0,
    whatsapp: { enabled: false, phone: "" },
    welcomed: false,
  };
}

function saveData(data) {
  data.jobs = data.jobs.slice(0, KEEP_JOBS);
  data.seen = data.seen.slice(-KEEP_SEEN);
  fm.writeString(DATA_PATH, JSON.stringify(data));
}

const labelOf = (search) => (search.location ? `${search.keywords} @ ${search.location}` : search.keywords);

// lastCheck = the moment jobs are covered up to. Starting it at "now" means that if the
// first check fails, the next one still looks back to when the search was added.
const newSearch = (keywords, location) =>
  ({ keywords, location, lastCheck: Date.now(), found: 0, status: "Not checked yet" });

// ---------- LinkedIn ----------

const ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };

function decodeEntities(text) {
  return text.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (match, code) => {
    if (code[0] !== "#") return ENTITIES[code.toLowerCase()] ?? match;
    const hex = code[1].toLowerCase() === "x";
    return String.fromCodePoint(parseInt(code.slice(hex ? 2 : 1), hex ? 16 : 10));
  });
}

function cleanText(html) {
  return decodeEntities(html.replace(/<[^>]+>/g, " ")).replace(/\s+/g, " ").trim();
}

function grab(text, pattern) {
  const match = text.match(pattern);
  return match ? cleanText(match[1]) : "";
}

function parseJobs(html) {
  return html.split('data-entity-urn="urn:li:jobPosting:').slice(1).flatMap((card) => {
    const id = (card.match(/^\d+/) || [])[0];
    if (!id) return [];
    return [{
      id,
      title: grab(card, /class="base-search-card__title"[^>]*>([\s\S]*?)<\/h3>/),
      company: grab(card, /class="base-search-card__subtitle"[^>]*>([\s\S]*?)<\/h4>/),
      location: grab(card, /class="job-search-card__location"[^>]*>([\s\S]*?)<\/span>/),
      posted: (card.match(/<time[^>]*datetime="([^"]*)"/) || [])[1] || "",
      url: `${JOB_URL}${id}/`,
    }];
  });
}

const sleep = (ms) => new Promise((resolve) => Timer.schedule(ms, false, resolve));

async function fetchPage(params, start) {
  const query = Object.entries({ ...params, start })
    .map(([key, value]) => `${key}=${encodeURIComponent(value)}`).join("&");
  const request = new Request(`${SEARCH_URL}?${query}`);
  request.headers = HEADERS;
  request.timeoutInterval = 20;
  const html = await request.loadString();
  const status = request.response.statusCode;
  if (status === 200) return html;
  if (status === 400 && start > 0) return ""; // LinkedIn's answer past the last page
  if (status === 429 || status === 999) throw new Error("LinkedIn is limiting requests; retrying next check");
  throw new Error(`LinkedIn returned HTTP ${status}`);
}

/** Every job in the search's window (all pages) that hasn't been reported before. */
async function fetchNewJobs(search, seen, now) {
  const since = search.lastCheck || now;
  const seconds = Math.round(Math.min(
    Math.max((now - since) / 1000 + OVERLAP_MINUTES * 60, MIN_WINDOW_MINUTES * 60),
    MAX_WINDOW_HOURS * 3600));
  const params = { keywords: search.keywords, location: search.location, f_TPR: `r${seconds}`, sortBy: "DD" };
  const read = new Set();
  const fresh = [];
  let start = 0;
  for (let page = 0; page < MAX_PAGES; page++) {
    if (page && !config.runsInWidget) await sleep(1200); // be polite between pages
    const jobs = parseJobs(await fetchPage(params, start));
    const unread = jobs.filter((job) => !read.has(job.id));
    if (!unread.length) break; // past the last page (or LinkedIn repeated one)
    unread.forEach((job) => read.add(job.id));
    fresh.push(...unread.filter((job) => !seen.has(job.id)));
    start += jobs.length;
  }
  return fresh;
}

/** Check the given searches (default: all), store results, notify.
 *  Returns the new jobs, or null if a check ran moments ago and this one was skipped. */
async function check(data, { searches = data.searches, force = false } = {}) {
  const now = Date.now();
  if (!force && now - (data.lastRun || 0) < MIN_MINUTES_BETWEEN_CHECKS * 60e3) return null;
  if (searches === data.searches) data.lastRun = now;
  const seen = new Set(data.seen);
  const batch = [];
  for (const search of searches) {
    try {
      const jobs = await fetchNewJobs(search, seen, now);
      search.lastCheck = now;
      search.found = (search.found || 0) + jobs.length;
      search.status = `Checked ${timeText(now)}: ${jobs.length} new`;
      for (const job of jobs) {
        seen.add(job.id);
        batch.push({ ...job, search: labelOf(search), foundAt: now, isNew: true });
      }
    } catch (e) {
      search.status = `Error ${timeText(now)}: ${e.message}`;
    }
  }
  data.seen = [...seen];
  data.jobs = [...batch, ...data.jobs];
  saveData(data);
  if (batch.length) await sendAlerts(data, batch);
  return batch;
}

// ---------- alerts ----------

const headline = (jobs) => `${jobs.length} new LinkedIn job${jobs.length === 1 ? "" : "s"}`;

async function sendAlerts(data, jobs) {
  const note = new Notification();
  note.title = headline(jobs);
  note.body = jobs.slice(0, 6).map((job) => `• ${job.title} – ${job.company}`).join("\n") +
    (jobs.length > 6 ? `\n+ ${jobs.length - 6} more` : "");
  note.threadIdentifier = "linkedin-jobs";
  note.sound = "default";
  note.openURL = jobs.length === 1 ? jobs[0].url : URLScheme.forRunningScript();
  await note.schedule();

  if (data.whatsapp && data.whatsapp.enabled) {
    try {
      await sendWhatsApp(data, jobs);
      data.whatsappStatus = `Sent ${timeText(Date.now())}`;
    } catch (e) {
      data.whatsappStatus = `Failed ${timeText(Date.now())}: ${e.message}`;
    }
    saveData(data);
  }
}

function whatsappText(jobs) {
  const lines = [`*${headline(jobs)}*`];
  const groups = new Map();
  jobs.forEach((job) => groups.set(job.search, [...(groups.get(job.search) || []), job]));
  let shown = 0;
  for (const [search, group] of groups) {
    if (shown === WHATSAPP_MAX_JOBS) break;
    lines.push(`\n*${search}*`);
    for (const job of group.slice(0, WHATSAPP_MAX_JOBS - shown)) {
      lines.push(`- ${job.title} @ ${job.company}\n${job.url}`);
      shown++;
    }
  }
  if (jobs.length > shown) lines.push(`\n...and ${jobs.length - shown} more in the app.`);
  return lines.join("\n");
}

async function sendWhatsApp(data, jobs) {
  const text = whatsappText(jobs);
  const apikey = Keychain.contains(KEYCHAIN_KEY) ? Keychain.get(KEYCHAIN_KEY) : "";
  const request = new Request(`${CALLMEBOT_URL}?phone=${encodeURIComponent(data.whatsapp.phone)}` +
    `&text=${encodeURIComponent(text)}&apikey=${encodeURIComponent(apikey)}`);
  request.timeoutInterval = 30;
  const reply = cleanText(await request.loadString());
  // CallMeBot echoes our message back before its verdict; judge only the verdict,
  // otherwise a job titled e.g. "Error Analyst" would look like a failure.
  const lastLine = text.split("\n").pop();
  const at = reply.lastIndexOf(lastLine);
  const verdict = (at >= 0 ? reply.slice(at + lastLine.length) : reply).trim() || reply;
  if (request.response.statusCode !== 200 || /invalid|error|wrong/i.test(verdict)) {
    throw new Error(`CallMeBot: ${verdict.slice(0, 200) || `HTTP ${request.response.statusCode}`}`);
  }
}

// ---------- shared helpers ----------

function timeText(ms) {
  if (!ms) return "never";
  const date = new Date(ms);
  const time = date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  if (date.toDateString() === new Date().toDateString()) return time;
  return `${date.toLocaleDateString([], { month: "short", day: "numeric" })} ${time}`;
}

async function showMessage(title, message = "") {
  const alert = new Alert();
  alert.title = title;
  alert.message = message;
  alert.addAction("OK");
  await alert.presentAlert();
}

const BACKGROUND_HELP =
  "iPhone apps can't run on a fixed timer, so checks happen whenever iOS allows. " +
  "Each check looks back to the previous one, so no job is missed, only reported a bit later.\n\n" +
  "1. WIDGET: touch and hold the Home Screen, tap +, choose Scriptable, add a widget, then tap it " +
  "and set Script to \"" + Script.name() + "\". iOS refreshes it roughly every 15-60 minutes.\n\n" +
  "2. AUTOMATION (more often): Shortcuts app > Automation > + > App > pick apps you open a lot " +
  "(WhatsApp, Instagram, Safari...) > Is Opened > Run Immediately > Next > New Blank Automation > " +
  "add the Scriptable action \"Run Script\" > choose \"" + Script.name() + "\". " +
  "You can also add Time of Day automations (e.g. every hour).";

// ---------- app (opened in Scriptable, from the widget, or from a notification) ----------

async function showApp(data) {
  const table = new UITable();
  table.showSeparators = true;
  let busy = false;
  let banner = "";

  const newRow = () => {
    const row = new UITableRow();
    row.dismissOnSelect = false; // tapping a row must never close the list
    return row;
  };

  const addHeader = (text) => {
    const row = newRow();
    row.isHeader = true;
    row.height = 40;
    const cell = row.addText(text);
    cell.titleFont = Font.semiboldSystemFont(13);
    cell.titleColor = MUTED;
    table.addRow(row);
  };

  const render = () => {
    table.removeAllRows();

    const top = newRow();
    top.height = 70;
    const title = top.addText("Jobs", banner || `Last check ${timeText(data.lastRun)}` +
      (data.whatsapp.enabled ? ` · WhatsApp on${data.whatsappStatus ? ` (${data.whatsappStatus})` : ""}` : ""));
    title.titleFont = Font.boldSystemFont(28);
    title.subtitleColor = MUTED;
    title.widthWeight = 70;
    const checkButton = top.addButton(busy ? "Checking…" : "Check now");
    checkButton.widthWeight = 30;
    checkButton.rightAligned();
    checkButton.dismissOnTap = false;
    checkButton.onTap = () => runCheck({ force: true });
    table.addRow(top);

    addHeader("SEARCHES");
    for (const search of data.searches) {
      const row = newRow();
      row.height = 60;
      const text = row.addText(search.keywords, `${search.location || "Anywhere"} · ${search.status}`);
      text.subtitleColor = MUTED;
      text.widthWeight = 85;
      const count = row.addText(String(search.found || 0));
      count.titleColor = ACCENT;
      count.widthWeight = 15;
      count.rightAligned();
      row.onSelect = () => removeSearch(search);
      table.addRow(row);
    }
    const add = newRow();
    add.addText("+ Add search").titleColor = ACCENT;
    add.onSelect = () => addSearch();
    table.addRow(add);

    addHeader(`JOBS (${data.jobs.length})`);
    if (!data.jobs.length) {
      const row = newRow();
      row.addText("No jobs yet", "New jobs show up here as they're found").subtitleColor = MUTED;
      table.addRow(row);
    }
    for (const job of data.jobs.slice(0, 150)) {
      const row = newRow();
      row.height = 64;
      const text = row.addText(`${job.isNew ? "● " : ""}${job.title}`,
        `${job.company} · ${job.location} · ${timeText(job.foundAt)}`);
      text.titleFont = Font.semiboldSystemFont(16);
      if (job.isNew) text.titleColor = ACCENT;
      text.subtitleColor = MUTED;
      row.onSelect = () => Safari.open(job.url);
      table.addRow(row);
    }

    addHeader("SETTINGS");
    for (const [label, action] of [
      [`WhatsApp alerts: ${data.whatsapp.enabled ? "on" : "off"}`, () => editWhatsApp()],
      ["Set up background checks", () => showMessage("Background checks", BACKGROUND_HELP)],
    ]) {
      const row = newRow();
      row.addText(label).titleColor = ACCENT;
      row.onSelect = action;
      table.addRow(row);
    }
    table.reload();
  };

  const runCheck = async (options) => {
    if (busy) return;
    busy = true;
    banner = "Checking LinkedIn…";
    render();
    const jobs = await check(data, options);
    busy = false;
    banner = jobs ? `Checked ${timeText(Date.now())}: ${jobs.length} new` : ""; // null: checked moments ago
    render();
  };

  const addSearch = async () => {
    const alert = new Alert();
    alert.title = "Add search";
    alert.message = '"City, Country" works best for location, e.g. "Amman, Jordan".';
    alert.addTextField("Position, e.g. Software engineer");
    alert.addTextField("Location, e.g. Amman, Jordan");
    alert.addAction("Add");
    alert.addCancelAction("Cancel");
    if (await alert.presentAlert() === -1) return;
    const search = newSearch(alert.textFieldValue(0).trim(), alert.textFieldValue(1).trim());
    if (!search.keywords) return showMessage("Enter a position to search for.");
    if (data.searches.some((s) => labelOf(s) === labelOf(search))) {
      return showMessage("That search is already in the list.");
    }
    data.searches.push(search);
    saveData(data);
    await runCheck({ searches: [search], force: true });
  };

  const removeSearch = async (search) => {
    const alert = new Alert();
    alert.title = labelOf(search);
    alert.addDestructiveAction("Stop watching");
    alert.addCancelAction("Cancel");
    if (await alert.presentSheet() !== 0) return;
    data.searches = data.searches.filter((s) => s !== search);
    saveData(data);
    render();
  };

  const editWhatsApp = async () => {
    const alert = new Alert();
    alert.title = "WhatsApp alerts";
    alert.message = "Optional; iPhone notifications work without this. Free via CallMeBot: on WhatsApp, " +
      "send \"I allow callmebot to send me messages\" to the number listed at " +
      "callmebot.com/blog/free-api-whatsapp-messages and it replies with your API key.";
    alert.addTextField("Your number, e.g. +971501234567", data.whatsapp.phone || "");
    alert.addSecureTextField("API key", Keychain.contains(KEYCHAIN_KEY) ? Keychain.get(KEYCHAIN_KEY) : "");
    alert.addAction("Save and send test");
    alert.addDestructiveAction("Turn off");
    alert.addCancelAction("Cancel");
    const choice = await alert.presentAlert();
    if (choice === 1) {
      data.whatsapp.enabled = false;
      saveData(data);
    } else if (choice === 0) {
      const phone = alert.textFieldValue(0).replace(/\s/g, "");
      const apikey = alert.textFieldValue(1).trim();
      if (!phone || !apikey) return showMessage("Enter both your number and the API key.");
      data.whatsapp = { enabled: true, phone };
      Keychain.set(KEYCHAIN_KEY, apikey);
      saveData(data);
      try {
        await sendWhatsApp(data, [{ title: "Test job - your alerts work!", company: "Jobs on iPhone",
          url: "https://www.linkedin.com/jobs/", search: "Test" }]);
        await showMessage("Test sent", "Check WhatsApp for the message.");
      } catch (e) {
        await showMessage("WhatsApp test failed", e.message);
      }
    }
    render();
  };

  render();
  const closed = table.present(true);
  if (!data.welcomed) {
    data.welcomed = true;
    saveData(data);
    const hello = new Notification(); // asks for notification permission now, not during a background check
    hello.title = "LinkedIn Jobs is set up";
    hello.body = "New jobs from your searches will show up here.";
    await hello.schedule();
    await showMessage("Welcome", BACKGROUND_HELP);
  }
  runCheck({});
  await closed;
  data.jobs.forEach((job) => { job.isNew = false; }); // you've seen them now
  saveData(data);
}

// ---------- widget ----------

function buildWidget(data) {
  const widget = new ListWidget();
  widget.backgroundColor = Color.dynamic(Color.white(), new Color("#1c1c1e"));
  widget.url = URLScheme.forRunningScript();
  widget.setPadding(12, 14, 12, 14);
  const fresh = data.jobs.filter((job) => job.isNew).length;
  const head = widget.addText(fresh ? `${fresh} new job${fresh === 1 ? "" : "s"}` : "LinkedIn Jobs");
  head.font = Font.semiboldSystemFont(13);
  head.textColor = ACCENT;
  widget.addSpacer(6);
  const rows = { small: 2, medium: 3, large: 7, extraLarge: 7 }[config.widgetFamily] || 3;
  const textColor = Color.dynamic(Color.black(), Color.white());
  for (const job of data.jobs.slice(0, rows)) {
    const stack = widget.addStack();
    stack.layoutVertically();
    stack.url = job.url;
    const title = stack.addText(job.title);
    title.font = Font.semiboldSystemFont(13);
    title.textColor = textColor;
    title.lineLimit = 1;
    const sub = stack.addText(`${job.company} · ${timeText(job.foundAt)}`);
    sub.font = Font.systemFont(11);
    sub.textColor = MUTED;
    sub.lineLimit = 1;
    widget.addSpacer(5);
  }
  if (!data.jobs.length) {
    const empty = widget.addText("No jobs yet");
    empty.font = Font.systemFont(12);
    empty.textColor = MUTED;
  }
  widget.addSpacer();
  const foot = widget.addText(`Checked ${timeText(data.lastRun)}`);
  foot.font = Font.systemFont(10);
  foot.textColor = MUTED;
  widget.refreshAfterDate = new Date(Date.now() + 10 * 60e3); // a request; iOS decides
  return widget;
}

// ---------- main ----------

const data = loadData();
if (config.runsInWidget) {
  await check(data);
  Script.setWidget(buildWidget(data));
} else if (config.runsInApp) {
  await showApp(data);
} else { // Shortcuts automation or Siri: check quietly
  const jobs = await check(data);
  Script.setShortcutOutput(jobs && jobs.length ? headline(jobs) : "No new jobs");
}
Script.complete();
