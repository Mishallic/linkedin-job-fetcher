package com.jobwatch.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

data class AlertConfig(
    val emailEnabled: Boolean = false,
    val emailTo: String = "",
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: String = "587",
    val smtpUser: String = "",
    val smtpPassword: String = "",
    val whatsappEnabled: Boolean = false,
    val whatsappPhone: String = "",
    val whatsappKey: String = "",
) {
    fun trimmed() = copy(emailTo = emailTo.trim(), smtpHost = smtpHost.trim(), smtpPort = smtpPort.trim(),
        smtpUser = smtpUser.trim(), smtpPassword = smtpPassword.trim(),
        whatsappPhone = whatsappPhone.replace(" ", ""), whatsappKey = whatsappKey.trim())

    /** What's missing before this can be saved or tested, or null if it's complete. */
    fun problem(): String? = when {
        emailEnabled && (listOf(emailTo, smtpHost, smtpUser, smtpPassword).any { it.isBlank() } || smtpPort.toIntOrNull() == null) ->
            "Fill in all the email fields (the port must be a number)."
        whatsappEnabled && (whatsappPhone.isBlank() || whatsappKey.isBlank()) -> "Fill in your WhatsApp number and API key."
        else -> null
    }
}

/** Email and WhatsApp settings, private to this app on the phone. */
object AlertPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    fun load(context: Context): AlertConfig = prefs(context).let { p ->
        val d = AlertConfig()
        AlertConfig(
            emailEnabled = p.getBoolean("emailEnabled", d.emailEnabled),
            emailTo = p.getString("emailTo", d.emailTo)!!,
            smtpHost = p.getString("smtpHost", d.smtpHost)!!,
            smtpPort = p.getString("smtpPort", d.smtpPort)!!,
            smtpUser = p.getString("smtpUser", d.smtpUser)!!,
            smtpPassword = p.getString("smtpPassword", d.smtpPassword)!!,
            whatsappEnabled = p.getBoolean("whatsappEnabled", d.whatsappEnabled),
            whatsappPhone = p.getString("whatsappPhone", d.whatsappPhone)!!,
            whatsappKey = p.getString("whatsappKey", d.whatsappKey)!!,
        )
    }

    fun save(context: Context, c: AlertConfig) {
        prefs(context).edit()
            .putBoolean("emailEnabled", c.emailEnabled).putString("emailTo", c.emailTo)
            .putString("smtpHost", c.smtpHost).putString("smtpPort", c.smtpPort)
            .putString("smtpUser", c.smtpUser).putString("smtpPassword", c.smtpPassword)
            .putBoolean("whatsappEnabled", c.whatsappEnabled).putString("whatsappPhone", c.whatsappPhone)
            .putString("whatsappKey", c.whatsappKey)
            .apply()
    }

    fun lastSend(context: Context): String = prefs(context).getString("lastSend", "") ?: ""

    fun setLastSend(context: Context, text: String) = prefs(context).edit().putString("lastSend", text).apply()
}

object Alerts {
    private const val CHANNEL = "new_jobs"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "New jobs", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "New jobs from your LinkedIn searches"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Tell the user about new jobs: notification, then email/WhatsApp if turned on. Returns whether
     * the notification went out (not when notifications aren't allowed).
     */
    fun deliver(context: Context, jobs: List<Job>): Boolean {
        val notified = notify(context, jobs)
        sendExternal(context, jobs)
        return notified
    }

    /** Email and WhatsApp the jobs, where turned on, and note how it went. */
    fun sendExternal(context: Context, jobs: List<Job>) {
        val config = AlertPrefs.load(context)
        if (!config.emailEnabled && !config.whatsappEnabled) return
        val results = send(config, jobs)
        val time = timeText(Clock.now())
        val sent = results.filterValues { it == null }.keys
        val parts = mutableListOf<String>()
        if (sent.isNotEmpty()) parts += "sent ${jobs.size} job(s) by ${sent.joinToString(" + ")} at $time"
        results.forEach { (channel, error) -> if (error != null) parts += "$channel failed at $time: $error" }
        AlertPrefs.setLastSend(context, parts.joinToString(". ").replaceFirstChar { it.uppercase() })
    }

    /** The phone's notification for [jobs]. Returns false when notifications aren't allowed. */
    @SuppressLint("MissingPermission") // checked just below
    fun notify(context: Context, jobs: List<Job>): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        // One job: tapping opens it. Several: tapping opens the app's list.
        val open = if (jobs.size == 1) Intent(Intent.ACTION_VIEW, Uri.parse(jobs[0].url))
        else Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val lines = NotificationCompat.InboxStyle()
        jobs.take(6).forEach { lines.addLine("${it.title} – ${it.company}") }
        if (jobs.size > 6) lines.setSummaryText("+ ${jobs.size - 6} more")
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Messages.headline(jobs))
            .setContentText("${jobs[0].title} – ${jobs[0].company}")
            .setStyle(lines)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify((Clock.now() / 1000).toInt(), notification)
        return true
    }

    /** Send to every enabled channel. Returns channel -> error message, or null if it was sent. */
    fun send(config: AlertConfig, jobs: List<Job>): Map<String, String?> {
        val results = linkedMapOf<String, String?>()
        if (config.emailEnabled) results["Email"] = attempt { sendEmail(config, jobs) }
        if (config.whatsappEnabled) results["WhatsApp"] = attempt { sendWhatsApp(config, jobs) }
        return results
    }

    private fun attempt(block: () -> Unit): String? = try {
        block()
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private fun sendEmail(c: AlertConfig, jobs: List<Job>) {
        val port = c.smtpPort.toInt()
        val props = Properties().apply {
            put("mail.smtp.host", c.smtpHost)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "20000")
            put("mail.smtp.writetimeout", "20000")
            put("mail.smtp.ssl.checkserveridentity", "true")
            if (port == 465) {
                put("mail.smtp.ssl.enable", "true")
            } else {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(c.smtpUser, c.smtpPassword)
        })
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(c.smtpUser))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(c.emailTo))
            setSubject(Messages.emailSubject(jobs), "UTF-8")
            setContent(MimeMultipart("alternative").apply {
                addBodyPart(MimeBodyPart().apply { setText(Messages.emailText(jobs), "UTF-8") })
                addBodyPart(MimeBodyPart().apply { setContent(Messages.emailHtml(jobs), "text/html; charset=UTF-8") })
            })
        }
        Transport.send(message)
    }

    private fun sendWhatsApp(c: AlertConfig, jobs: List<Job>) {
        val text = Messages.whatsappText(jobs)
        val url = "https://api.callmebot.com/whatsapp.php?phone=${encode(c.whatsappPhone)}" +
            "&text=${encode(text)}&apikey=${encode(c.whatsappKey)}"
        val (status, body) = Http.get(url)
        val verdict = Messages.callMeBotVerdict(cleanText(body), text)
        if (status != 200 || Messages.isCallMeBotFailure(verdict)) {
            throw IllegalStateException("CallMeBot: ${verdict.take(200).ifBlank { "HTTP $status" }}")
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
