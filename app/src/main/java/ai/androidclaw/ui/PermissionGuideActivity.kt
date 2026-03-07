package ai.androidclaw.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ai.androidclaw.databinding.ActivityPermissionGuideBinding

class PermissionGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionGuideBinding

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "未授予通知权限，后台服务状态可能不可见", Toast.LENGTH_SHORT).show()
        }
        refreshPermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupActions()
        refreshPermissionState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun setupActions() {
        binding.btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnGrantNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                refreshPermissionState()
            }
        }

        binding.btnGrantOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                refreshPermissionState()
            }
        }

        binding.btnGrantBatteryOptimization.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !PermissionChecker.isIgnoringBatteryOptimizations(this)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                runCatching {
                    startActivity(intent)
                }.onFailure {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } else {
                refreshPermissionState()
            }
        }

        binding.btnContinue.setOnClickListener {
            if (PermissionChecker.hasAllRequiredPermissions(this)) {
                enterMainPage()
            } else {
                Toast.makeText(this, "请先完成所有权限授权", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshPermissionState() {
        val accessibilityGranted = PermissionChecker.isAccessibilityServiceEnabled(this)
        val notificationGranted = PermissionChecker.isNotificationPermissionGranted(this)
        val overlayGranted = PermissionChecker.isOverlayPermissionGranted(this)
        val batteryOptimizationGranted = PermissionChecker.isIgnoringBatteryOptimizations(this)

        binding.tvAccessibilityStatus.text = if (accessibilityGranted) "已授权" else "未授权"
        binding.tvNotificationStatus.text = if (notificationGranted) "已授权" else "未授权"
        binding.tvOverlayStatus.text = if (overlayGranted) "已授权" else "未授权"
        binding.tvBatteryStatus.text = if (batteryOptimizationGranted) "已授权" else "未授权"

        binding.btnContinue.isEnabled = accessibilityGranted &&
            notificationGranted &&
            overlayGranted &&
            batteryOptimizationGranted

        if (binding.btnContinue.isEnabled) {
            enterMainPage()
        }
    }

    private fun enterMainPage() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
