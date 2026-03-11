package de.adesso.agenticaifunctioncalling.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.adesso.agenticaifunctioncalling.model.ModelState

// ─────────────────────────────────────────────────────────────────────────────
// Download overlay
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun DownloadOverlay(state: ModelState.Downloading) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Gemma 3 1B herunterladen…", color = OnSurfaceText, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))

        if (state.progress.totalBytes > 0) {
            val pct = state.progress.percent / 100f
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth(),
                color = GreenPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${state.progress.percent}%  " +
                        "(${state.progress.bytesReceived / 1_048_576} MB " +
                        "/ ${state.progress.totalBytes / 1_048_576} MB)",
                color = Muted,
                fontSize = 12.sp
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = GreenPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${state.progress.bytesReceived / 1_048_576} MB empfangen…",
                color = Muted,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Das Modell (~600 MB) wird einmalig auf dem Gerät gespeichert.",
            color = Muted,
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}