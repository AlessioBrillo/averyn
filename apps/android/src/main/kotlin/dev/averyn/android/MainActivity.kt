package dev.averyn.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.averyn.domain.ActivityState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // Placeholder that proves the app links against shared/. Replaced by the Record screen in MVP-0.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Averyn — ${ActivityState.IDLE}")
                }
            }
        }
    }
}
