package com.sikamikaniko.sonora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.sikamikaniko.sonora.ui.AppTheme
import com.sikamikaniko.sonora.ui.SonoraTheme
import com.sikamikaniko.sonora.ui.WorkstationRoot
import com.sikamikaniko.sonora.ui.WorkstationViewModel

class MainActivity : ComponentActivity() {
    private lateinit var workstationVm: WorkstationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
