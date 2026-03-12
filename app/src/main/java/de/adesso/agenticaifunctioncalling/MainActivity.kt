package de.adesso.agenticaifunctioncalling

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.adesso.agenticaifunctioncalling.navigation.AppNavHost
import de.adesso.agenticaifunctioncalling.ui.theme.AgenticAiFunctionCallingTheme

class MainActivity : ComponentActivity() {

//    private val permissionsLauncher = registerForActivityResult(
//        ActivityResultContracts.RequestMultiplePermissions()
//    ) { /* each dispatcher checks individually */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

//        permissionsLauncher.launch(
//            arrayOf(
//                Manifest.permission.READ_CALENDAR,
//                Manifest.permission.WRITE_CALENDAR,
//                Manifest.permission.SEND_SMS,
//            )
//        )

        setContent {
            AgenticAiFunctionCallingTheme {
                AppNavHost()
            }
        }
    }
}