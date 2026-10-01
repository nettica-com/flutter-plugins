package one.mixin.desktop.drop

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.util.concurrent.Executors

class DesktopDropPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    companion object {
        private const val TAG = "DesktopDropPlugin"
    }

    private var channel: MethodChannel? = null

    private var dragView: View? = null
    private var activity: Activity? = null

    private val dragListener = View.OnDragListener { _, event ->
        val channel = channel ?: return@OnDragListener false
        when (event.action) {
            DragEvent.ACTION_DRAG_ENTERED -> {
                channel.invokeMethod("entered", listOf(event.x, event.y))
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                channel.invokeMethod("updated", listOf(event.x, event.y))
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                channel.invokeMethod("exited", null)
            }
            DragEvent.ACTION_DROP -> {
                val activity = activity
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity != null) {
                    handleDrop(event, channel, activity)
                } else {
                    channel.invokeMethod("performOperation", emptyList<String>())
                }
            }
        }
        return@OnDragListener true
    }

    private val copyExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @RequiresApi(Build.VERSION_CODES.N)
    private fun handleDrop(event: DragEvent, channel: MethodChannel, activity: Activity) {
        val uris = event.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
        } ?: emptyList()
        val permission = if (uris.isEmpty()) null else activity.requestDragAndDropPermissions(event)
        if (permission == null) {
            channel.invokeMethod("performOperation", emptyList<String>())
            return
        }

        val context = activity.applicationContext
        copyExecutor.execute {
            val paths = try {
                uris.mapNotNull { cacheDroppedUri(context, it) }
            } finally {
                permission.release()
            }
            mainHandler.post { this.channel?.invokeMethod("performOperation", paths) }
        }
    }

    private fun cacheDroppedUri(context: Context, uri: Uri): String? {
        val dir = File(File(context.cacheDir, "desktop_drop"), System.nanoTime().toString())
        if (!dir.mkdirs()) return null
        val target = File(dir, displayName(context.contentResolver, uri))
        return try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (copied == null) {
                dir.deleteRecursively()
                null
            } else {
                target.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to copy dropped $uri", e)
            dir.deleteRecursively()
            null
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        val queried = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        var name = (queried ?: uri.lastPathSegment ?: "").replace(Regex("[/\u0000]"), "_")
        if (name.isBlank() || name == "." || name == "..") name = "dropped_file"
        if (queried == null && !name.contains('.')) {
            resolver.getType(uri)
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?.let { name = "$name.$it" }
        }
        return name
    }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "desktop_drop")
        channel?.setMethodCallHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        result.notImplemented()
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        copyExecutor.shutdown()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        val content = binding.activity.findViewById<ViewGroup>(android.R.id.content)
        if (content == null) {
            Log.e(TAG, "onAttachedToActivity: can not find android.R.id.content")
            return
        }
        content.setOnDragListener(dragListener)
        dragView = content
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    }

    override fun onDetachedFromActivity() {
        dragView?.setOnDragListener(null)
        activity = null
    }
}

