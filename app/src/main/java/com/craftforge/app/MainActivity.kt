package com.craftforge.app

import androidx.compose.runtime.Composable
import com.craftforge.app.base.BaseActivity
import com.craftforge.app.ui.navigation.CraftForgeApp

class MainActivity : BaseActivity() {

    @Composable
    override fun Content() {
        CraftForgeApp()
    }
}
