"""Send a batch of new jobs to yourself by email (SMTP) and/or WhatsApp (CallMeBot).

Each job is a dict from linkedin_jobs.parse_jobs plus a "search" key naming the
search that found it.
"""

import re
import smtplib
import ssl
from collections import defaultdict
from email.message import EmailMessage
from html import escape, unescape

import requests

CALLMEBOT_URL = "https://api.callmebot.com/whatsapp.php"
WHATSAPP_MAX_JOBS = 15  # the message travels in a URL, so keep it a sensible size

DEFAULT_SETTINGS = {
    "email_enabled": False,
    "email_to": "",
    "smtp_host": "smtp.gmail.com",
    "smtp_port": "587",
    "smtp_user": "",
    "smtp_password": "",
    "whatsapp_enabled": False,
    "whatsapp_phone": "",
    "whatsapp_apikey": "",
}


def group_by_search(jobs):
    groups = defaultdict(list)
    for job in jobs:
        groups[job["search"]].append(job)
    return groups


def headline(jobs):
    return f"{len(jobs)} new LinkedIn job{'' if len(jobs) == 1 else 's'}"


def build_email(settings, jobs):
    msg = EmailMessage()
    msg["Subject"] = f"{headline(jobs)}: {', '.join(group_by_search(jobs))}"
    msg["From"] = settings["smtp_user"]
    msg["To"] = settings["email_to"]

    text = []
    html = ["<div style='font-family:Segoe UI,Arial,sans-serif;font-size:14px'>"]
    for search, group in group_by_search(jobs).items():
        text.append(search)
        html.append(f"<h3 style='margin:18px 0 6px'>{escape(search)}</h3><ul style='padding-left:18px'>")
        for job in group:
            text.append(f"- {job['title']} - {job['company']} ({job['location']})\n  {job['url']}")
            html.append(
                f"<li style='margin-bottom:6px'><a href='{escape(job['url'])}'>{escape(job['title'])}</a>"
                f"<br>{escape(job['company'])} <span style='color:#666'>&middot; {escape(job['location'])}</span></li>")
        text.append("")
        html.append("</ul>")
    html.append("</div>")
    msg.set_content("\n".join(text))
    msg.add_alternative("".join(html), subtype="html")
    return msg


def send_email(settings, jobs):
    port = int(settings["smtp_port"])
    context = ssl.create_default_context()
    if port == 465:
        server = smtplib.SMTP_SSL(settings["smtp_host"], port, context=context, timeout=30)
    else:
        server = smtplib.SMTP(settings["smtp_host"], port, timeout=30)
    with server:
        if port != 465:
            server.starttls(context=context)
        server.login(settings["smtp_user"], settings["smtp_password"])
        server.send_message(build_email(settings, jobs))


def build_whatsapp_text(jobs):
    lines = [f"*{headline(jobs)}*"]
    shown = 0
    for search, group in group_by_search(jobs).items():
        if shown == WHATSAPP_MAX_JOBS:
            break
        lines.append(f"\n*{search}*")
        for job in group[:WHATSAPP_MAX_JOBS - shown]:
            lines.append(f"- {job['title']} @ {job['company']}\n{job['url']}")
            shown += 1
    if len(jobs) > shown:
        lines.append(f"\n...and {len(jobs) - shown} more in the app.")
    return "\n".join(lines)


def send_whatsapp(settings, jobs):
    text = build_whatsapp_text(jobs)
    response = requests.get(CALLMEBOT_URL, timeout=30, params={
        "phone": settings["whatsapp_phone"].replace(" ", ""),
        "text": text,
        "apikey": settings["whatsapp_apikey"],
    })
    # CallMeBot replies with a small HTML page that echoes our message back
    # ("Message to: ... Text to send: <text> <verdict>"), so only judge the verdict;
    # otherwise a job titled e.g. "Error Analyst" would look like a failure.
    reply = " ".join(unescape(re.sub(r"<[^>]+>", " ", response.text)).split())
    verdict = reply.rpartition(text.splitlines()[-1])[2].strip() or reply
    if response.status_code != 200 or re.search(r"invalid|error|wrong", verdict, re.I):
        raise RuntimeError(f"CallMeBot: {verdict[:200] or f'HTTP {response.status_code}'}")


def send_enabled(settings, jobs):
    """Send to every enabled channel. Returns {channel: error message, or None if sent}."""
    results = {}
    for channel, enabled, send in (("Email", "email_enabled", send_email),
                                   ("WhatsApp", "whatsapp_enabled", send_whatsapp)):
        if settings.get(enabled):
            try:
                send(settings, jobs)
                results[channel] = None
            except Exception as exc:
                results[channel] = str(exc) or type(exc).__name__
    return results
