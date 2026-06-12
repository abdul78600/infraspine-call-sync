package com.infraspine.callsync.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.R
import com.infraspine.callsync.databinding.ActivityMainBinding
import com.infraspine.callsync.ui.common.PermissionHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: sync still works without notifications, just less visible */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navHostFragment = supportFragmentManager
            .findFragmentById(binding.navHostFragment.id) as NavHostFragment
        val navController = navHostFragment.navController
        val app = application as CallSyncApplication
        val graph = navController.navInflater.inflate(R.navigation.nav_graph).apply {
            setStartDestination(
                if (app.container.settingsStore.hasValidSession()) {
                    R.id.dashboardFragment
                } else {
                    R.id.loginFragment
                }
            )
        }
        navController.graph = graph

        val appBarConfiguration = AppBarConfiguration(setOf(R.id.dashboardFragment, R.id.loginFragment))
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)

        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()

        val app = application as CallSyncApplication
        if (!app.container.settingsStore.hasValidSession()) return

        lifecycleScope.launch {
            app.container.syncRepository.syncCallLogsOnAppOpen()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionHelper.hasNotificationPermission(this)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
