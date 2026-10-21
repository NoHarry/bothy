package cc.noharry.bothy.playground

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Before the car's navigation can start: the user agrees to AMap's privacy terms (the SDK may not run before that), then
 * grants location. Nothing is shown in a build without an AMap key.
 */
@Composable
internal fun AMapConsent() {
    if (!BuildConfig.AMAP_CONFIGURED) return
    val context = LocalContext.current
    var agreed by remember { mutableStateOf(AMapPrivacy.agreed(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Playground.engine?.ensureStarted() }
    LaunchedEffect(agreed) {
        if (!agreed) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) Playground.engine?.ensureStarted() else ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    if (agreed) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("使用高德地图导航") },
        text = {
            Text(
                "Playground 的车载导航用高德地图 SDK 规划路线、导航和搜索地点。使用前需要你同意《高德地图开放平台隐私权政策》，" +
                    "高德会处理定位和网络信息来提供这些服务。不同意的话，导航不可用，其他功能照常。",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                AMapPrivacy.agree(context)
                agreed = true
            }) { Text("同意") }
        },
        dismissButton = {
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AMapPrivacy.POLICY_URL))) }) { Text("查看隐私政策") }
        },
    )
}
