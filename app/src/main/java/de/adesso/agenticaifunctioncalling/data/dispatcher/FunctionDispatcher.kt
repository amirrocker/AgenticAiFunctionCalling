package de.adesso.agenticaifunctioncalling.data.dispatcher

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.telephony.SmsManager
import androidx.core.app.ActivityCompat
import de.adesso.agenticaifunctioncalling.model.FunctionCall
import de.adesso.agenticaifunctioncalling.model.FunctionResult
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "FunctionDispatcher"

/**
 * Executes Android actions described by a [FunctionCall].
 * Each function checks permissions before acting and returns a [FunctionResult].
 */
class FunctionDispatcher(private val context: Context) {

    fun dispatch(call: FunctionCall): FunctionResult = when (call.name) {
        "addCalendarEntry" -> addCalendarEntry(call.args)
        "sendSMS"          -> sendSMS(call.args)
        "setAlarm"         -> setAlarm(call.args)
        "openApp"          -> openApp(call.args)
        else -> FunctionResult.Failure("Unbekannte Funktion: ${call.name}")
    }

    // ── Calendar ──────────────────────────────────────────────────────────────

    private fun addCalendarEntry(args: Map<String, String>): FunctionResult {
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR))
            return FunctionResult.Failure("Kalender-Berechtigung fehlt.")

        return runCatching {
            val title    = args.required("title")
            val date     = args.required("date")         // "2026-03-09"
            val time     = args.required("time")         // "14:30"
            val duration = args["durationMinutes"]?.toLongOrNull() ?: 60L

            val sdf      = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val startMs  = sdf.parse("$date $time")!!.time
            val endMs    = startMs + duration * 60_000L

            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.CALENDAR_ID, 1L)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            FunctionResult.Success("Termin \"$title\" am $date um $time Uhr eingetragen.")
        }.getOrElse { FunctionResult.Failure("Kalender-Fehler: ${it.message}") }
    }

    // ── SMS ───────────────────────────────────────────────────────────────────

    private fun sendSMS(args: Map<String, String>): FunctionResult {
        if (!hasPermission(Manifest.permission.SEND_SMS))
            return FunctionResult.Failure("SMS-Berechtigung fehlt.")

        return runCatching {
            val number  = args.required("number")
            val message = args.required("message")
            @Suppress("DEPRECATION")
            SmsManager.getDefault().sendTextMessage(number, null, message, null, null)
            FunctionResult.Success("SMS an $number gesendet.")
        }.getOrElse { FunctionResult.Failure("SMS-Fehler: ${it.message}") }
    }

    // ── Alarm ─────────────────────────────────────────────────────────────────

    private fun setAlarm(args: Map<String, String>): FunctionResult {
        return runCatching {
            val (hour, minute) = args.required("time").split(":").map { it.toInt() }
            val label = args["label"] ?: "Alarm"

            val triggerAt = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis())
                    add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis

            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val intent = Intent(context, AlarmReceiver::class.java)
                .putExtra("label", label)
            val pending = PendingIntent.getBroadcast(
                context,
                triggerAt.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            FunctionResult.Success("Alarm \"$label\" auf ${args["time"]} Uhr gestellt.")
        }.getOrElse { FunctionResult.Failure("Alarm-Fehler: ${it.message}") }
    }

    // ── Open App ──────────────────────────────────────────────────────────────

    private fun openApp(args: Map<String, String>): FunctionResult {
        return runCatching {
            val pkg = args.required("packageName")
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return FunctionResult.Failure("App nicht installiert: $pkg")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            FunctionResult.Success("App $pkg geöffnet.")
        }.getOrElse { FunctionResult.Failure("App-Fehler: ${it.message}") }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hasPermission(permission: String) =
        ActivityCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED

    private fun Map<String, String>.required(key: String) =
        this[key] ?: error("Fehlendes Argument: $key")
}
