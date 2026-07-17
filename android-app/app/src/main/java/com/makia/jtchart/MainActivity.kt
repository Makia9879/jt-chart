package com.makia.jtchart

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makia.jtchart.ui.ChartViewModel
import com.makia.jtchart.ui.JtChartScreen

class MainActivity : ComponentActivity() {
    private val chartViewModel: ChartViewModel by viewModels {
        (application as JtChartApplication).container.viewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            val state by chartViewModel.state.collectAsStateWithLifecycle()
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = androidx.compose.ui.graphics.Color(0xFF101216),
                    surface = androidx.compose.ui.graphics.Color(0xFF101216),
                ),
            ) {
                JtChartScreen(state, chartViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        chartViewModel.onForegroundChanged(true)
    }

    override fun onStop() {
        chartViewModel.onForegroundChanged(false, configurationChange = isChangingConfigurations)
        super.onStop()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }
}
