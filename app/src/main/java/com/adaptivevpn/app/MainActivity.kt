package com.adaptivevpn.app

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adaptivevpn.app.profile.ProfileStore
import com.adaptivevpn.app.profile.SingBoxProfileValidator
import com.adaptivevpn.app.vpn.AdaptiveVpnService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var profileStatus by mutableStateOf("هنوز پروفایلی وارد نشده است.")
    private var profiles by mutableStateOf<List<ProfileStore.StoredProfile>>(emptyList())
    private var connectionStatus by mutableStateOf("قطع")

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpnService() else connectionStatus = "مجوز VPN داده نشد"
    }

    private val profilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        profileStatus = try {
            val raw = contentResolver.openInputStream(uri)?.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > SingBoxProfileValidator.MAX_PROFILE_BYTES) break
                    output.write(buffer, 0, count)
                }
                if (total > SingBoxProfileValidator.MAX_PROFILE_BYTES) null else output.toByteArray().toString(Charsets.UTF_8)
            }
            if (raw == null) {
                "فایل بیش از حد بزرگ است یا خوانده نشد. سقف حجم ۱ مگابایت است."
            } else {
                val result = SingBoxProfileValidator.validate(raw)
                if (result.valid && result.rawJson != null) {
                    val saved = ProfileStore.save(this, result.rawJson, queryDisplayName(uri))
                    refreshProfiles()
                    "${result.summary}\nپروفایل «${saved.name}» در حافظه خصوصی ذخیره شد."
                } else result.summary
            }
        } catch (e: Exception) {
            "خواندن یا ذخیره پروفایل ناموفق بود: ${e.message?.take(100) ?: "خطای نامشخص"}"
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) ?: "sing-box profile.json" else "sing-box profile.json"
        } catch (_: Exception) {
            "sing-box profile.json"
        } finally {
            cursor?.close()
        }
    }

    private fun refreshProfiles() { profiles = ProfileStore.list(this) }

    private fun startVpnService() {
        val config = ProfileStore.active(this) ?: run {
            connectionStatus = "خطا: پروفایل فعال پیدا نشد"
            return
        }
        val intent = Intent(this, AdaptiveVpnService::class.java)
            .setAction(AdaptiveVpnService.ACTION_START)
            .putExtra(AdaptiveVpnService.EXTRA_CONFIG_PATH, config.absolutePath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        connectionStatus = "در حال آماده‌سازی؛ هنوز تأیید نشده"
    }

    private fun refreshConnectionStatus() {
        val status = getSharedPreferences(AdaptiveVpnService.PREFS, MODE_PRIVATE)
            .getString(AdaptiveVpnService.KEY_STATUS, null)
        connectionStatus = when {
            status == null || status == "DISCONNECTED" -> "قطع"
            status == "STARTED_UNVERIFIED" -> "موتور شروع شده؛ سلامت اتصال تأیید نشده"
            status == "TRAFFIC_PROBE_PASSED" -> "آزمون‌های HTTPS موفق شدند؛ نشت ترافیک بررسی نشده"
            status.startsWith("PROBE_FAILED:") -> "آزمون ترافیک ناموفق بود؛ اتصال تأیید نشده (${status.removePrefix("PROBE_FAILED:").trim().take(120)})"
            status.startsWith("ERROR:") -> "خطا: ${status.removePrefix("ERROR:").trim().take(140)}"
            else -> status
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProfiles()
        refreshConnectionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshProfiles()
        refreshConnectionStatus()
        setContent {
            LaunchedEffect(Unit) {
                while (true) {
                    refreshConnectionStatus()
                    refreshProfiles()
                    delay(1000)
                }
            }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top
                    ) {
                        Text("AdaptiveVPN", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("نسخه آزمایشی — مدیریت پروفایل‌ها")
                        Spacer(Modifier.height(12.dp))
                        Text("وضعیت اتصال: $connectionStatus", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { profilePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("افزودن فایل تنظیمات sing-box") }
                        Spacer(Modifier.height(8.dp))
                        Text(profileStatus, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        Text("پروفایل‌های ذخیره‌شده (${profiles.size})", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        if (profiles.isEmpty()) Text("ابتدا یک فایل JSON معتبر وارد کن.")
                        profiles.forEach { profile ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(if (profile.active) "● ${profile.name} (فعال)" else profile.name)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!profile.active) {
                                        OutlinedButton(
                                            onClick = { ProfileStore.select(this@MainActivity, profile.id); refreshProfiles() },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("انتخاب") }
                                    } else {
                                        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("پروفایل فعال") }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            if (connectionStatus != "قطع" && profile.active) {
                                                profileStatus = "برای حذف پروفایل فعال، ابتدا اتصال را قطع کن."
                                            } else {
                                                ProfileStore.delete(this@MainActivity, profile.id)
                                                refreshProfiles()
                                                profileStatus = "پروفایل حذف شد."
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("حذف") }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                if (connectionStatus == "قطع" || connectionStatus.startsWith("خطا") || connectionStatus == "مجوز VPN داده نشد") {
                                    val config = ProfileStore.active(this@MainActivity)
                                    if (config != null) {
                                        val consent = VpnService.prepare(this@MainActivity)
                                        if (consent == null) startVpnService() else vpnConsent.launch(consent)
                                    } else profileStatus = "پروفایل فعالی برای اتصال وجود ندارد."
                                } else {
                                    startService(Intent(this@MainActivity, AdaptiveVpnService::class.java).setAction(AdaptiveVpnService.ACTION_STOP))
                                    connectionStatus = "قطع"
                                }
                            },
                            enabled = profiles.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (connectionStatus == "قطع" || connectionStatus.startsWith("خطا") || connectionStatus == "مجوز VPN داده نشد") "درخواست اتصال" else "قطع اتصال") }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "پس از شروع هسته، دو آزمون HTTPS به‌صورت خودکار اجرا می‌شوند. موفقیت آن‌ها فقط دسترسی HTTPS را نشان می‌دهد؛ نشت ترافیک، DNS و مسیر IPv4/IPv6 هنوز روی دستگاه بررسی نشده‌اند.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
