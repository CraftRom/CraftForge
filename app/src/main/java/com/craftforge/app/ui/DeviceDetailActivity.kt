package com.craftforge.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.craftforge.app.ui.screens.DeviceDetailScreen
import com.craftforge.app.ui.theme.DeviceTheme
import com.craftforge.app.ui.viewmodel.DeviceDetailViewModel

class DeviceDetailActivity : ComponentActivity() {

    private lateinit var viewModel: DeviceDetailViewModel
    private lateinit var factory: ViewModelProvider.Factory

    // Лаунчер для запиту кількох дозволів одночасно
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            viewModel.loadData()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        factory = DeviceDetailViewModel.provideFactory(applicationContext)
        viewModel = factory.create(DeviceDetailViewModel::class.java)

        // 1. Формуємо список необхідних дозволів
        val requiredPermissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Для Android 12 (API 31) і вище потрібен спеціальний дозвіл для Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // 2. Фільтруємо ті дозволи, які ще не надані
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        // 3. Запитуємо дозволи або одразу вантажимо дані
        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            viewModel.loadData()
        }

        setContent {
            DeviceTheme {
                DeviceDetailScreen(viewModel)
            }
        }
    }
}