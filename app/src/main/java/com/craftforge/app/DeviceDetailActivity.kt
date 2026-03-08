package com.craftforge.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.craftforge.app.ui.screens.DeviceDetailScreen
import com.craftforge.app.ui.theme.DeviceTheme
import com.craftforge.app.ui.viewmodel.DeviceDetailViewModel

class DeviceDetailActivity : ComponentActivity() {

    private val viewModel: DeviceDetailViewModel by viewModels {
        DeviceDetailViewModel.provideFactory(applicationContext)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "READ_PHONE_STATE denied. Telephony data may be limited, but device info will still load.", Toast.LENGTH_SHORT).show()
        }
        viewModel.loadData()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            viewModel.loadData()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }

        setContent {
            DeviceTheme {
                DeviceDetailScreen(viewModel = viewModel)
            }
        }
    }
}
