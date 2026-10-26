package dev.averyn.android.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.averyn.android.R

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
                    Text(stringResource(R.string.health_rationale_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.health_rationale_text), modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }
}
