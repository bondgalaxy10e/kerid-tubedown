package com.example.testapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.testapp.ui.theme.TestAppTheme
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.ffmpeg.FFmpeg

class MainActivity : ComponentActivity() {
    
    private val basicPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        Log.d("MainActivity", "Basic permissions result: $allGranted")
        PermissionHelper.updateBasicPermissions(allGranted)
        
        if (allGranted) {
            // Check for MANAGE_EXTERNAL_STORAGE if needed
            PermissionHelper.checkPermissions(this)
            if (!PermissionHelper.hasAllRequiredPermissions()) {
                requestAllFilesAccess()
            }
        }
    }
    
    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        PermissionHelper.updateAllFilesAccess()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // youtubedl-android와 FFmpeg 초기화
        try {
            YoutubeDL.getInstance().init(this)
            Log.d("MainActivity", "YoutubeDL initialized successfully")
            
            FFmpeg.getInstance().init(this)
            Log.d("MainActivity", "FFmpeg initialized successfully")
        } catch (e: YoutubeDLException) {
            Log.e("MainActivity", "Failed to initialize YoutubeDL", e)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize FFmpeg", e)
        }
        
        enableEdgeToEdge()
        setContent {
            TestAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SearchScreen(
                        modifier = Modifier.padding(innerPadding),
                        onRequestPermissions = ::requestPermissions
                    )
                }
            }
        }
        
        // Check permissions on startup
        PermissionHelper.checkPermissions(this)
        if (!PermissionHelper.hasAllRequiredPermissions()) {
            requestPermissions()
        }
    }
    
    private fun requestPermissions() {
        val missingPermissions = PermissionHelper.getMissingBasicPermissions(this)
        
        if (missingPermissions.isNotEmpty()) {
            PermissionHelper.setRequestingPermissions(true)
            basicPermissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            PermissionHelper.checkPermissions(this)
            if (!PermissionHelper.hasAllRequiredPermissions()) {
                requestAllFilesAccess()
            }
        }
    }
    
    private fun requestAllFilesAccess() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = android.net.Uri.parse("package:$packageName")
            allFilesAccessLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to open file access settings", e)
        }
    }
    
    override fun onResume() {
        super.onResume()
        PermissionHelper.checkPermissions(this)
    }
}