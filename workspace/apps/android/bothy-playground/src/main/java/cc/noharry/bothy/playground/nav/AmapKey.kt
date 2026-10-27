package cc.noharry.bothy.playground.nav
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import cc.noharry.bothy.playground.BuildConfig
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.AMapNavi
import com.amap.api.services.core.ServiceSettings
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * The AMap key the head unit runs with (owner 2026-10-25: whoever installs it applies for their own key and types it
 * in): the one typed here, else the one built in from local.properties. An AMap key is bound to a package and the SHA1
 * of the certificate the APK is signed with, so [packageName] and [sha1] are what the AMap console asks for, shown to
 * copy. A key typed here is handed to the SDK's parts before they start ([apply]); a new one takes a restart.
 */
internal object AmapKey {
    const val CONSOLE_URL = "https://console.amap.com/dev/key/app"

    fun configured(context: Context): Boolean = saved(context).isNotEmpty() || BuildConfig.AMAP_CONFIGURED

    /** Typed in here (else the build's own is used, from the manifest); read once, the screen asks every second. */
    fun saved(context: Context): String = cached
        ?: runCatching { File(context.noBackupFilesDir, KEY_FILE).readText(Charsets.UTF_8).trim() }.getOrDefault("").also { cached = it }

    @Volatile private var cached: String? = null

    /** Null when saved, else why not. */
    fun save(context: Context, key: String): String? {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return "key 不能为空或包含空白字符"
        if (!KEY_SHAPE.matches(trimmed)) return "这不像高德的 Android key（32 位字母和数字）"
        return try {
            context.noBackupFilesDir.mkdirs()
            File(context.noBackupFilesDir, KEY_FILE).writeText(trimmed, Charsets.UTF_8)
            cached = trimmed
            null
        } catch (_: IOException) {
            "保存失败"
        }
    }

    fun clear(context: Context) {
        File(context.noBackupFilesDir, KEY_FILE).delete()
        cached = ""
    }

    /** Hands the typed key to the map, navigation, search and location parts; the build's own key needs nothing. */
    fun apply(context: Context) {
        val key = saved(context).ifEmpty { return }
        runCatching {
            MapsInitializer.setApiKey(key)
            AMapNavi.setApiKey(context.applicationContext, key)
            ServiceSettings.getInstance().setApiKey(key)
            AMapLocationClient.setApiKey(key)
        }
    }

    fun packageName(context: Context): String = context.packageName

    /** The SHA1 of the certificate this APK is signed with, as the AMap console takes it (AA:BB:…). */
    fun sha1(context: Context): String = runCatching {
        val pm = context.packageManager
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        MessageDigest.getInstance("SHA-1").digest(signature!!.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("")

    /** Starts the head unit again in a fresh process, where the SDK takes the new key. */
    fun restart(activity: Activity) {
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        exitProcess(0)
    }

    private const val KEY_FILE = "amap-key"
    private val KEY_SHAPE = Regex("[0-9a-zA-Z]{32}")
}
