package de.adesso.agenticaifunctioncalling

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import de.adesso.agenticaifunctioncalling.ui.agent.AgentScreen
import de.adesso.agenticaifunctioncalling.ui.theme.AgenticAiFunctionCallingTheme

class MainActivity : ComponentActivity() {

    // Request all agent permissions up-front
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions granted/denied – dispatcher checks individually */ }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.SEND_SMS,
                Manifest.permission.SCHEDULE_EXACT_ALARM,
            )
        )

        setContent {
            AgenticAiFunctionCallingTheme {
                AgentScreen()
            }
        }
    }
}