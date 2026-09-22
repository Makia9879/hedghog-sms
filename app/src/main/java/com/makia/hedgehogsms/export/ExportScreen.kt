package com.makia.hedgehogsms.export

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.makia.hedgehogsms.data.AndroidSmsSource
import com.makia.hedgehogsms.data.SmsPermissionUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExportUiState(
    val running: Boolean = false,
    val readCount: Int = 0,
    val fileCount: Int = 0,
    val status: String = "按卡槽导出全部短信，打包成一个压缩包。",
    val error: String? = null,
    val savedName: String? = null,
)

class ExportViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(ExportUiState())
        private set
    var location by mutableStateOf(AndroidZipStore.location(application))
        private set

    fun rememberFolder(uri: Uri) {
        val app = getApplication<Application>()
        runCatching { AndroidZipStore.rememberFolder(app, uri) }
            .onSuccess { location = ExportLocation.Folder(uri) }
            .onFailure { state = state.copy(error = "这个文件夹不能保存，请重新选择。") }
    }

    fun useDownloads() {
        AndroidZipStore.clearFolder(getApplication())
        location = ExportLocation.Downloads
    }

    fun export() {
        if (state.running) return
        viewModelScope.launch {
            state = ExportUiState(running = true, status = "正在读取短信")
            try {
                val app = getApplication<Application>()
                val messages = AndroidSmsSource(app.contentResolver).readAll { count ->
                    state = state.copy(readCount = count, status = "已读取 $count 条")
                }
                val directory = AndroidSlotDirectory(app).load()
                val parts = planTextParts(messages, directory.identities, directory.logicalSlotCount)
                if (parts.isEmpty()) {
                    state = ExportUiState(status = "没有可导出的短信。")
                    return@launch
                }
                val fileName = archiveZipName(System.currentTimeMillis())
                state = state.copy(readCount = messages.size, fileCount = parts.size, status = "正在写入 ${parts.size} 个文本文件")
                withContext(Dispatchers.IO) {
                    AndroidZipStore(app).save(location, fileName, parts)
                }
                state = ExportUiState(
                    readCount = messages.size,
                    fileCount = parts.size,
                    status = "已保存到${locationLabel(location)}",
                    savedName = fileName,
                )
            } catch (_: SmsPermissionUnavailableException) {
                state = ExportUiState(error = "没有短信读取权限，无法导出。")
            } catch (error: Exception) {
                state = ExportUiState(error = error.message?.take(120) ?: "导出失败")
            }
        }
    }
}

@Composable
fun ExportScreen(modifier: Modifier = Modifier, model: ExportViewModel = viewModel()) {
    val context = LocalContext.current
    var numberGranted by remember { mutableStateOf(hasPhoneNumberPermission(context)) }
    val numberLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        numberGranted = granted
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.rememberFolder(uri)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) numberGranted = hasPhoneNumberPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val ui = model.state
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("导出短信", style = MaterialTheme.typography.headlineMedium)
        Text("每个卡槽写成 TXT，文件名用这张卡的手机号。超过 $MESSAGES_PER_FILE 条就拆成多个文件，再打成一个压缩包。")
        Text("不会发送、删除或修改系统里的短信。")
        Text("保存位置：${locationLabel(model.location)}")
        Button(onClick = { folderLauncher.launch(null) }, enabled = !ui.running, modifier = Modifier.fillMaxWidth()) {
            Text("选择手机上的文件夹")
        }
        if (model.location !is ExportLocation.Downloads) {
            Button(onClick = model::useDownloads, enabled = !ui.running, modifier = Modifier.fillMaxWidth()) {
                Text("改回下载文件夹")
            }
        }
        if (!numberGranted) {
            Text("还没有本机号码权限。文件名会暂时用卡槽 1、卡槽 2 或未知卡槽。")
            Button(
                onClick = { numberLauncher.launch(Manifest.permission.READ_PHONE_NUMBERS) },
                enabled = !ui.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("允许用卡槽号码做文件名") }
        }
        Button(onClick = model::export, enabled = !ui.running, modifier = Modifier.fillMaxWidth()) {
            Text(if (ui.running) "正在导出" else "开始导出")
        }
        Text(ui.status)
        if (ui.readCount > 0) Text("短信 ${ui.readCount} 条${if (ui.fileCount > 0) "，文本 ${ui.fileCount} 个" else ""}")
        ui.savedName?.let { Text(it) }
        ui.error?.let { Text(it) }
    }
}

private fun hasPhoneNumberPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
