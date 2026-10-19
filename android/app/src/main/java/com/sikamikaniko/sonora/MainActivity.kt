package com.sikamikaniko.sonora

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.sikamikaniko.sonora.ui.AppTheme
import com.sikamikaniko.sonora.ui.SonoraTheme
import com.sikamikaniko.sonora.ui.WorkstationRoot
import com.sikamikaniko.sonora.ui.WorkstationViewModel

class MainActivity : ComponentActivity() {
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private lateinit var workstationVm: WorkstationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences("mkusic_permissions", MODE_PRIVATE)
            if (!prefs.getBoolean("notifications_asked", false)) {
                prefs.edit().putBoolean("notifications_asked", true).apply()
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        workstationVm = ViewModelProvider(this)[WorkstationViewModel::class.java]
        setContent {
            SonoraTheme(AppTheme.DYNAMIC) { WorkstationRoot(workstationVm) }
        }
    }

    override fun onResume() {
        super.onResume()
        workstationVm.onForeground()
    }
}
