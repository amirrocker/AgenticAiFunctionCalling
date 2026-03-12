package de.adesso.agenticaifunctioncalling.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Shared theme tokens (mirror AgentScreen palette) ─────────────────────────
private val GreenPrimary = Color(0xFF3DDC84)
private val Background = Color(0xFF0D1B2A)
private val Surface1 = Color(0xFF1C2733)
private val Surface2 = Color(0xFF112233)
private val OnSurface = Color(0xFFDCE8F0)
private val Muted = Color(0xFF8BA5BB)
private val ErrorRed = Color(0xFFFF6B6B)

// ── Shared back-nav top bar ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, color = OnSurface, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Zurück",
                    tint = GreenPrimary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface2)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Deposits screen
// ─────────────────────────────────────────────────────────────────────────────

private data class DepositEntry(val name: String, val amount: String, val date: String)

@Composable
fun DepositsScreen(onBack: () -> Unit) {
    val entries = listOf(
        DepositEntry("Monatsgehalt", "+€ 4.200,00", "01. Mär"),
        DepositEntry("Freelance Rechnung", "+€   850,00", "26. Feb"),
        DepositEntry("Zinsgutschrift", "+€    12,40", "28. Feb"),
    )

    Scaffold(
        containerColor = Background,
        topBar = { BackTopBar("Einlagen / Deposits", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Balance card
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Surface1,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Gesamtguthaben", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "€ 5.062,40",
                            color = GreenPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Letzte Eingänge", color = Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            items(entries) { entry ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Surface1,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, color = OnSurface, fontSize = 14.sp)
                            Text(entry.date, color = Muted, fontSize = 11.sp)
                        }
                        Text(entry.amount, color = GreenPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Relocation screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RelocationScreen(city: String?, onBack: () -> Unit) {
    val steps = listOf(
        "Umzugsantrag beim Arbeitgeber einreichen",
        "Genehmigung & Kostenübernahme bestätigen",
        "Vorübergehende Unterkunft buchen",
        "Beim Einwohnermeldeamt anmelden",
        "Bankadresse aktualisieren",
        "Steuernummer übertragen lassen"
    )

    Scaffold(
        containerColor = Background,
        topBar = {
            BackTopBar(
                title = "Umzugs-Assistent${city?.let { " – $it" } ?: ""}",
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (city != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Surface1,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Zielstadt", color = Muted, fontSize = 11.sp)
                            Text(
                                city,
                                color = GreenPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Deine Checkliste", color = Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }

            items(steps.withIndex().toList()) { (index, step) ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Surface1,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Badge(
                            containerColor = GreenPrimary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                "${index + 1}",
                                color = GreenPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(step, color = OnSurface, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Contracts screen
// ─────────────────────────────────────────────────────────────────────────────

private data class ContractEntry(val title: String, val status: String, val expires: String?)

@Composable
fun ContractsScreen(onBack: () -> Unit) {
    val contracts = listOf(
        ContractEntry("Arbeitsvertrag 2022", "Aktiv", null),
        ContractEntry("NDA – Lieferant XYZ", "Aktiv", null),
        ContractEntry("Büromietvertrag", "Läuft bald ab", "Apr 2026"),
        ContractEntry("Service-Level-Agreement", "In Prüfung", null),
    )

    Scaffold(
        containerColor = Background,
        topBar = { BackTopBar("Verträge / Contracts", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(contracts) { entry ->
                val statusColor = when (entry.status) {
                    "Aktiv" -> GreenPrimary
                    "Läuft bald ab" -> ErrorRed
                    else -> Muted
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Surface1,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, color = OnSurface, fontSize = 14.sp)
                            entry.expires?.let {
                                Text("Läuft ab: $it", color = ErrorRed, fontSize = 11.sp)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = statusColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                entry.status,
                                color = statusColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}