package com.craftforge.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.craftforge.app.base.BaseActivity
import com.craftforge.app.ui.navigation.CraftForgeApp
import com.craftforge.app.util.PermissionManager

class MainActivity : BaseActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val deniedCount = results.values.count { !it }
        if (deniedCount > 0) {
            Toast.makeText(this, "Some functions may be limited without permissions", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = PermissionManager.getRequiredPermissions()
        requestPermissionLauncher.launch(permissions)

        if (!PermissionManager.canWriteSettings(this)) {
            PermissionManager.openWriteSettings(this)
        }
    }

    @Composable
    override fun Content() {
        CraftForgeApp()
    }
}