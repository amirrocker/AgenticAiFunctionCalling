package de.adesso.agenticaifunctioncalling.data.dispatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log


// ─────────────────────────────────────────────────────────────────────────────
// AlarmReceiver
// ─────────────────────────────────────────────────────────────────────────────

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "Alarm"
        Log.d("AlarmReceiver", "Alarm ausgelöst: $label")
        // TODO: Show a notification here
    }
}