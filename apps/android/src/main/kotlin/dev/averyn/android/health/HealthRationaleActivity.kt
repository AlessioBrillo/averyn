package dev.averyn.android.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What Health Connect shows when the user asks why Averyn wants its permissions (a requirement of the platform:
 * an app that asks for health permissions must answer that). It says what is written, when, and what is not read.
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Why Averyn asks for these permissions", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "\nAveryn saves a finished activity to Health Connect only when you ask it to, from the " +
                            "list of activities on this phone: the workout, its route, the heart rate that was " +
                            "recorded and the distance.\n\n" +
                            "It only writes. It never reads anything from Health Connect, and it asks for no " +
                            "permission to read.\n\n" +
                            "Saving the same activity again does not make a copy. You can remove what Averyn wrote " +
                            "at any time in Health Connect, and take the permissions back in its settings.",
                    )
                }
            }
        }
    }
}
