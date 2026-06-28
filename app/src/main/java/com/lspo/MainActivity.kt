package com.lspo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lspo.ui.theme.LspoTheme
import kotlinx.coroutines.Dispatchers
import rikka.shizuku.Shizuku
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MainActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        enableEdgeToEdge()

        setContent {
            val permissionState = remember { mutableStateOf(ShizukuState.IDLE) }
            val permissionText = remember { mutableStateOf("Shizuku") }

            LspoTheme {
                ActivityMonitorScreen(
                    contentResolver = contentResolver,
                    permissionState = permissionState.value,
                    permissionText = permissionText.value,
                    onRequestShizuku = {
                        requestShizukuPermission(permissionState, permissionText)
                    },
                    onClear = { clearRecords() },
                    onCopyActivity = { text -> copyToClipboard(text) },
                    onDisablePackage = { pkg -> disablePackage(pkg) },
                    onDisableActivity = { pkg, cls -> disableActivity(pkg, cls) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun requestShizukuPermission(
        state: MutableState<ShizukuState>,
        label: MutableState<String>
    ) {
        // 如果已连接，先清除状态，再重新申请
        if (state.value == ShizukuState.GRANTED) {
            state.value = ShizukuState.IDLE
            label.value = "Shizuku"
        }

        state.value = ShizukuState.REQUESTING
        label.value = "Shizuku..."

        // ===== Phase 1: 先走 Shizuku 通道 =====
        tryPhase1(state, label)
    }

    private fun tryPhase1(
        state: MutableState<ShizukuState>,
        label: MutableState<String>
    ) {
        val deferred = CompletableDeferred<Int?>()

        val listener = { requestCode: Int, grantResult: Int ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                deferred.complete(grantResult)
            }
        }

        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (_: Exception) {
            // Shizuku 未运行（binder 不存在），直接 fallback 到 Sui
            Shizuku.removeRequestPermissionResultListener(listener)
            tryPhase2(state, label)
            return
        }

        lifecycleScope.launch {
            try {
                val result = withTimeout(5000L) { deferred.await() }
                handleResult(result, state, label)
            } catch (_: TimeoutCancellationException) {
                // Phase 1 超时 → fallback 到 Phase 2: Sui
                Shizuku.removeRequestPermissionResultListener(listener)
                Toast.makeText(this@MainActivity, "Shizuku 无响应，尝试 Sui", Toast.LENGTH_SHORT).show()
                tryPhase2(state, label)
            }
        }
    }

    private fun tryPhase2(
        state: MutableState<ShizukuState>,
        label: MutableState<String>
    ) {
        // 手动初始化 Sui（已在 App.attachBaseContext 中禁用了自动初始化）
        val suiAvailable = try {
            rikka.sui.Sui.init(this.packageName)
        } catch (_: Exception) {
            false
        }

        if (!suiAvailable) {
            state.value = ShizukuState.IDLE
            label.value = "Shizuku"
            Toast.makeText(this@MainActivity, "Sui 不可用", Toast.LENGTH_SHORT).show()
            return
        }

        label.value = "Sui..."

        val deferred = CompletableDeferred<Int?>()

        val listener = { requestCode: Int, grantResult: Int ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                deferred.complete(grantResult)
            }
        }

        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (_: Exception) {
            // Sui binder 也不可用，安全重置
            Shizuku.removeRequestPermissionResultListener(listener)
            state.value = ShizukuState.IDLE
            label.value = "Shizuku"
            Toast.makeText(this@MainActivity, "权限服务不可用", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val result = withTimeout(5000L) { deferred.await() }
                handleResult(result, state, label)
            } catch (_: TimeoutCancellationException) {
                state.value = ShizukuState.IDLE
                label.value = "Shizuku"
                Toast.makeText(this@MainActivity, "获取权限超时", Toast.LENGTH_SHORT).show()
            } finally {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
        }
    }

    private fun handleResult(
        result: Int?,
        state: MutableState<ShizukuState>,
        label: MutableState<String>
    ) {
        if (result == PackageManager.PERMISSION_GRANTED) {
            state.value = ShizukuState.GRANTED
            label.value = detectPermissionSource()
        } else {
            state.value = ShizukuState.IDLE
            label.value = "Shizuku"
            Toast.makeText(this@MainActivity, "权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    private fun detectPermissionSource(): String {
        return try {
            val uid = Shizuku.getUid()
            val level = when (uid) {
                0 -> "root"
                2000 -> "shell"
                else -> "uid=$uid"
            }
            val source = if (rikka.sui.Sui.isSui()) "sui" else "shizuku"
            "$source($level)"
        } catch (_: Exception) {
            "shizuku(?)"
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("activity_class", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    private fun disablePackage(pkg: String) {
        executeDisablePackage(pkg)
    }

    private fun executeDisablePackage(pkg: String) {
        kotlinx.coroutines.MainScope().launch {
            withContext(Dispatchers.IO) {
                try {
                    val process = Shizuku.newProcess(arrayOf("/system/bin/sh", "-c", "pm disable --user 0 $pkg"), null, null)
                    val exitCode = process.waitFor()
                    @Suppress("DEPRECATION")
                    val stdout = process.inputStream.bufferedReader().readText().trim()
                    val stderr = process.errorStream.bufferedReader().readText().trim()
                    val msg = if (stdout.isNotEmpty()) stdout else stderr
                    withContext(Dispatchers.Main) {
                        if (exitCode == 0 || msg.contains("already")) {
                            Toast.makeText(this@MainActivity,
                                "Disabled package: $pkg", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity,
                                "Failed: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity,
                            "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun disableActivity(pkg: String, activityClass: String) {
        executeDisableActivity(pkg, activityClass)
    }

    private fun executeDisableActivity(pkg: String, activityClass: String) {
        val component = "$pkg/$activityClass"
        kotlinx.coroutines.MainScope().launch {
            withContext(Dispatchers.IO) {
                try {
                    val process = Shizuku.newProcess(arrayOf("/system/bin/sh", "-c", "pm disable --user 0 $component"), null, null)
                    val exitCode = process.waitFor()
                    @Suppress("DEPRECATION")
                    val stdout = process.inputStream.bufferedReader().readText().trim()
                    val stderr = process.errorStream.bufferedReader().readText().trim()
                    val msg = if (stdout.isNotEmpty()) stdout else stderr
                    withContext(Dispatchers.Main) {
                        if (exitCode == 0 || msg.contains("already")) {
                            Toast.makeText(this@MainActivity,
                                "Disabled activity: $component", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity,
                                "Failed: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity,
                            "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Activity Monitor",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Activity save notifications"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun clearRecords() {
        try {
            contentResolver.delete(ActivityProvider.CONTENT_URI, null, null)
            Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Clear failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToDownloads(records: List<ActivityRecord>) {
        if (records.isEmpty()) {
            Toast.makeText(this, "No records to save", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "activity_monitor_$dateStr.txt"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10+
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
                )

                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        OutputStreamWriter(os).use { writer ->
                            writeRecords(writer, records)
                        }
                    }
                    showSaveNotification(fileName, uri)
                } else {
                    Toast.makeText(this, "Failed to create file", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Legacy path for Android 9-
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!dir.exists()) dir.mkdirs()
                val file = java.io.File(dir, fileName)
                file.bufferedWriter().use { writer ->
                    writeRecords(writer, records)
                }
                showSaveNotificationLegacy(fileName)
            }

            Toast.makeText(this, "Saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun writeRecords(writer: java.io.Writer, records: List<ActivityRecord>) {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        writer.write("=== Activity Monitor Report ===\n")
        writer.write("Generated: ${df.format(Date())}\n")
        writer.write("Total: ${records.size} activities\n\n")
        records.forEachIndexed { index, r ->
            val time = df.format(Date(r.timestamp))
            writer.write("${index + 1}. [$time] ${r.packageName}\n")
            writer.write("   Activity: ${r.activityClass}\n\n")
        }
    }

    private fun showSaveNotification(fileName: String, uri: Uri) {
        val nm = NotificationManagerCompat.from(this)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Saved to Downloads")
            .setContentText(fileName)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID++, notification)
    }

    private fun showSaveNotificationLegacy(fileName: String) {
        val nm = NotificationManagerCompat.from(this)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Saved to Downloads")
            .setContentText(fileName)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID++, notification)
    }

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 1001
        private const val CHANNEL_ID = "activity_monitor_save"
        private var NOTIFICATION_ID = 1000
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityMonitorScreen(
    contentResolver: ContentResolver,
    permissionState: ShizukuState,
    permissionText: String,
    onRequestShizuku: () -> Unit,
    onClear: () -> Unit,
    onCopyActivity: (String) -> Unit,
    onDisablePackage: (String) -> Unit,
    onDisableActivity: (String, String) -> Unit
) {
    val records = remember { mutableStateListOf<ActivityRecord>() }
    val filtered = remember { mutableStateListOf<ActivityRecord>() }
    var searchQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // ContentObserver to auto-refresh
    LaunchedEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scope.launch {
                    loadRecords(contentResolver, records)
                    applyFilter(records, searchQuery, filtered)
                }
            }
        }
        contentResolver.registerContentObserver(ActivityProvider.CONTENT_URI, true, observer)

        // Initial load
        loadRecords(contentResolver, records)
        applyFilter(records, searchQuery, filtered)

        // Wait until cancelled
        kotlinx.coroutines.awaitCancellation()

        contentResolver.unregisterContentObserver(observer)
    }

    // Apply filter when search changes
    LaunchedEffect(searchQuery) {
        applyFilter(records, searchQuery, filtered)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity Monitor") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    androidx.compose.material3.Surface(
                        onClick = onRequestShizuku,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (permissionState == ShizukuState.REQUESTING) {
                                Text(
                                    text = "...",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                Text(
                                    text = permissionText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onClear) {
                Icon(Icons.Filled.Clear, contentDescription = "Clear")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Search box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search package or activity...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )

            Spacer(Modifier.height(4.dp))

            // Count
            Text(
                text = "${filtered.size} activities" +
                        if (searchQuery.isNotBlank() && filtered.size != records.size)
                            " (filtered from ${records.size})" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )

            // List
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No matches" else "No activities yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered, key = { it.id }) { record ->
                        ActivityItem(
                            record = record,
                            onCopyActivity = { onCopyActivity(record.activityClass) },
                            onDisablePackage = { onDisablePackage(record.packageName) },
                            onDisableActivity = { onDisableActivity(record.packageName, record.activityClass) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityItem(
    record: ActivityRecord,
    onCopyActivity: () -> Unit,
    onDisablePackage: () -> Unit,
    onDisableActivity: () -> Unit
) {
    val df = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Activity info (clickable to copy class name)
        Column(
            modifier = Modifier
                .weight(1f)
                .let { mod ->
                    mod.clickable { onCopyActivity() }
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.packageName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = df.format(Date(record.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = record.activityClass,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Action buttons
        // Disable activity (text button)
        Text(
            text = "Act",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clickable { onDisableActivity() }
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
        // Disable package (text button)
        Text(
            text = "Pkg",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clickable { onDisablePackage() }
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}

private suspend fun loadRecords(
    contentResolver: ContentResolver,
    target: MutableList<ActivityRecord>
) {
    try {
        val cursor = contentResolver.query(
            ActivityProvider.CONTENT_URI, null, null, null, null
        )
        cursor?.use { c ->
            target.clear()
            while (c.moveToNext()) {
                target.add(
                    ActivityRecord(
                        id = c.getLong(c.getColumnIndexOrThrow("_id")),
                        packageName = c.getString(c.getColumnIndexOrThrow("package_name")),
                        activityClass = c.getString(c.getColumnIndexOrThrow("activity_class")),
                        timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"))
                    )
                )
            }
        }
    } catch (_: Exception) {
        // Provider not ready or no permission
    }
}

enum class ShizukuState {
    IDLE,
    REQUESTING,
    GRANTED
}

private fun applyFilter(
    all: List<ActivityRecord>,
    query: String,
    target: MutableList<ActivityRecord>
) {
    target.clear()
    if (query.isBlank()) {
        target.addAll(all)
    } else {
        val q = query.lowercase()
        target.addAll(
            all.filter {
                it.packageName.lowercase().contains(q) ||
                it.activityClass.lowercase().contains(q)
            }
        )
    }
}

