package com.example.testapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PermissionState(
    val hasBasicPermissions: Boolean = false,
    val hasAllFilesAccess: Boolean = false,
    val isRequestingPermissions: Boolean = false,
    val showPermissionDialog: Boolean = false,
    val permissionDialogMessage: String = ""
)

object PermissionHelper {
    private val _permissionState = MutableStateFlow(PermissionState())
    val permissionState: StateFlow<PermissionState> = _permissionState.asStateFlow()
    
    fun checkPermissions(context: Context) {
        val hasBasic = getMissingBasicPermissions(context).isEmpty()
        val hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Not needed for older versions
        }
        
        Log.d("PermissionHelper", "Permission check - Basic: $hasBasic, AllFiles: $hasAllFiles")
        
        _permissionState.value = _permissionState.value.copy(
            hasBasicPermissions = hasBasic,
            hasAllFilesAccess = hasAllFiles
        )
    }
    
    fun updateBasicPermissions(granted: Boolean) {
        _permissionState.value = _permissionState.value.copy(
            hasBasicPermissions = granted,
            isRequestingPermissions = false
        )
    }
    
    fun updateAllFilesAccess() {
        val hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        
        _permissionState.value = _permissionState.value.copy(
            hasAllFilesAccess = hasAllFiles,
            isRequestingPermissions = false
        )
    }
    
    fun setRequestingPermissions(requesting: Boolean) {
        _permissionState.value = _permissionState.value.copy(
            isRequestingPermissions = requesting
        )
    }
    
    fun getMissingBasicPermissions(context: Context): List<String> {
        val permissions = mutableListOf<String>()
        
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+ (API 33+)
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) 
                    != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                // Android 6+ (API 23+)
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                
                // WRITE_EXTERNAL_STORAGE only for Android 10 and below
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && 
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
        
        return permissions
    }
    
    fun hasAllRequiredPermissions(): Boolean {
        val state = _permissionState.value
        return state.hasBasicPermissions && state.hasAllFilesAccess
    }
    
    fun requestStoragePermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    // 마지막 대안: 앱 설정으로 이동
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:${context.packageName}")
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
            }
        }
    }
}