package com.voxharness.app.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.voxharness.app.llm.ToolDef
import java.io.File

/**
 * Android device tools — gives the LLM access to phone hardware,
 * apps, file system, and OS features via intents.
 */
object DeviceTools {

    private const val TAG = "DeviceTools"

    val TOOLS = listOf(
        ToolDef(
            name = "open_camera",
            description = "Open the camera app to take a photo or video.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "mode" to mapOf(
                        "type" to "string",
                        "description" to "Camera mode: 'photo' or 'video'",
                        "default" to "photo"
                    )
                )
            )
        ),
        ToolDef(
            name = "open_maps",
            description = "Open Google Maps with a location, directions, or search query.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf(
                        "type" to "string",
                        "description" to "Search query, address, or place name (e.g. 'coffee shops near me', '123 Main St')"
                    ),
                    "directions_to" to mapOf(
                        "type" to "string",
                        "description" to "Get directions to this destination"
                    )
                )
            )
        ),
        ToolDef(
            name = "open_app",
            description = "Launch an app by name (e.g. 'YouTube', 'Chrome', 'Spotify', 'WhatsApp').",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "app_name" to mapOf(
                        "type" to "string",
                        "description" to "Name of the app to open"
                    )
                ),
                "required" to listOf("app_name")
            )
        ),
        ToolDef(
            name = "web_search",
            description = "Search the web using the default browser.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf(
                        "type" to "string",
                        "description" to "Search query"
                    )
                ),
                "required" to listOf("query")
            )
        ),
        ToolDef(
            name = "set_volume",
            description = "Set the phone's media volume level.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "level" to mapOf(
                        "type" to "integer",
                        "description" to "Volume level from 0 to 15"
                    )
                ),
                "required" to listOf("level")
            )
        ),
        ToolDef(
            name = "toggle_wifi",
            description = "Open WiFi settings to enable or disable WiFi.",
            parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "toggle_bluetooth",
            description = "Open Bluetooth settings to enable or disable Bluetooth.",
            parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "set_alarm",
            description = "Set an alarm using the clock app.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "hour" to mapOf("type" to "integer", "description" to "Hour (0-23)"),
                    "minute" to mapOf("type" to "integer", "description" to "Minute (0-59)"),
                    "message" to mapOf("type" to "string", "description" to "Alarm label")
                ),
                "required" to listOf("hour", "minute")
            )
        ),
        ToolDef(
            name = "send_text",
            description = "Open the messaging app to send a text message.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "phone_number" to mapOf("type" to "string", "description" to "Phone number"),
                    "message" to mapOf("type" to "string", "description" to "Message text")
                ),
                "required" to listOf("message")
            )
        ),
        ToolDef(
            name = "make_call",
            description = "Start a phone call to a number or contact.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "phone_number" to mapOf("type" to "string", "description" to "Phone number to call")
                ),
                "required" to listOf("phone_number")
            )
        ),
        ToolDef(
            name = "list_files",
            description = "List files in a directory on the phone's storage.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf(
                        "type" to "string",
                        "description" to "Directory path (e.g. 'Downloads', 'DCIM', 'Documents') or full path"
                    )
                ),
                "required" to listOf("path")
            )
        ),
        ToolDef(
            name = "read_file",
            description = "Read the contents of a text file on the phone.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "File path")
                ),
                "required" to listOf("path")
            )
        ),
        ToolDef(
            name = "write_file",
            description = "Write content to a file on the phone's storage.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "File path"),
                    "content" to mapOf("type" to "string", "description" to "File content")
                ),
                "required" to listOf("path", "content")
            )
        ),
        ToolDef(
            name = "play_music",
            description = "Play music by searching YouTube or opening a music app.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf(
                        "type" to "string",
                        "description" to "Song, artist, or genre to play"
                    )
                ),
                "required" to listOf("query")
            )
        ),
        ToolDef(
            name = "show_notification",
            description = "Display a notification on the phone with a title and message.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "title" to mapOf("type" to "string", "description" to "Notification title"),
                    "message" to mapOf("type" to "string", "description" to "Notification body")
                ),
                "required" to listOf("title", "message")
            )
        ),
        ToolDef(
            name = "take_screenshot",
            description = "Inform the user how to take a screenshot (can't be done programmatically).",
            parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
    )

    fun handleTool(context: Context, name: String, args: Map<String, Any?>): Pair<String, Intent?> {
        return when (name) {
            "open_camera" -> {
                val mode = (args["mode"] as? String) ?: "photo"
                val intent = if (mode == "video") {
                    Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                } else {
                    Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                }
                "Opening camera in $mode mode." to intent
            }

            "open_maps" -> {
                val query = args["query"] as? String
                val directions = args["directions_to"] as? String
                val intent = when {
                    directions != null -> Intent(Intent.ACTION_VIEW,
                        Uri.parse("google.navigation:q=${Uri.encode(directions)}"))
                    query != null -> Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
                    else -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
                }
                intent.setPackage("com.google.android.apps.maps")
                val msg = if (directions != null) "Getting directions to $directions." else "Searching maps for $query."
                msg to intent
            }

            "open_app" -> {
                val appName = (args["app_name"] as? String) ?: ""
                val intent = findAppByName(context, appName)
                if (intent != null) {
                    "Opening $appName." to intent
                } else {
                    "Could not find app: $appName." to null
                }
            }

            "web_search" -> {
                val query = (args["query"] as? String) ?: ""
                val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
                "Searching for: $query." to intent
            }

            "set_volume" -> {
                val level = ((args["level"] as? Number)?.toInt()) ?: 7
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val vol = (level.coerceIn(0, 15) * max / 15)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol, AudioManager.FLAG_SHOW_UI)
                "Volume set to $level." to null
            }

            "toggle_wifi" -> {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                "Opening WiFi settings." to intent
            }

            "toggle_bluetooth" -> {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                "Opening Bluetooth settings." to intent
            }

            "set_alarm" -> {
                val hour = (args["hour"] as? Number)?.toInt() ?: 7
                val minute = (args["minute"] as? Number)?.toInt() ?: 0
                val message = (args["message"] as? String) ?: "VoxHarness Alarm"
                val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                    putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message)
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                }
                "Alarm set for $hour:${minute.toString().padStart(2, '0')}." to intent
            }

            "send_text" -> {
                val number = args["phone_number"] as? String ?: ""
                val message = (args["message"] as? String) ?: ""
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                    putExtra("sms_body", message)
                }
                "Opening message to $number." to intent
            }

            "make_call" -> {
                val number = (args["phone_number"] as? String) ?: ""
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                "Dialing $number." to intent
            }

            "list_files" -> {
                val path = resolvePath(args["path"] as? String ?: "")
                val dir = File(path)
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles()?.take(30)?.joinToString("\n") { f ->
                        val suffix = if (f.isDirectory) "/" else " (${formatSize(f.length())})"
                        "${f.name}$suffix"
                    } ?: "Empty directory."
                    files to null
                } else {
                    "Directory not found: $path" to null
                }
            }

            "read_file" -> {
                val path = resolvePath(args["path"] as? String ?: "")
                val file = File(path)
                if (file.exists() && file.isFile) {
                    val content = file.readText().take(3000)
                    content to null
                } else {
                    "File not found: $path" to null
                }
            }

            "write_file" -> {
                val path = resolvePath(args["path"] as? String ?: "")
                val content = (args["content"] as? String) ?: ""
                try {
                    val file = File(path)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    "File written: $path (${content.lines().size} lines)" to null
                } catch (e: Exception) {
                    "Error writing file: ${e.message}" to null
                }
            }

            "play_music" -> {
                val query = (args["query"] as? String) ?: "music"
                // Try YouTube Music first, fall back to YouTube
                val intent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", query)
                }
                "Playing: $query" to intent
            }

            "show_notification" -> {
                val title = (args["title"] as? String) ?: "VoxHarness"
                val message = (args["message"] as? String) ?: ""
                // Notification will be shown by the caller
                "Notification: $title - $message" to null
            }

            "take_screenshot" -> {
                "To take a screenshot, press Power + Volume Down simultaneously." to null
            }

            else -> "Unknown tool: $name" to null
        }
    }

    private fun findAppByName(context: Context, name: String): Intent? {
        val pm = context.packageManager
        val lower = name.lowercase()

        // Common app package mappings
        val knownApps = mapOf(
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "spotify" to "com.spotify.music",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "gmail" to "com.google.android.gm",
            "google maps" to "com.google.android.apps.maps",
            "maps" to "com.google.android.apps.maps",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock",
            "calculator" to "com.google.android.calculator",
            "settings" to "com.android.settings",
            "files" to "com.google.android.documentsui",
            "photos" to "com.google.android.apps.photos",
            "telegram" to "org.telegram.messenger",
            "tiktok" to "com.zhiliaoapp.musically",
        )

        // Check known apps first
        val packageName = knownApps[lower]
        if (packageName != null) {
            return pm.getLaunchIntentForPackage(packageName)
        }

        // Search installed apps
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in apps) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label.contains(lower) || lower.contains(label)) {
                return pm.getLaunchIntentForPackage(app.packageName)
            }
        }

        return null
    }

    private fun resolvePath(path: String): String {
        if (path.startsWith("/")) return path

        // Map friendly names to actual paths
        val storage = Environment.getExternalStorageDirectory().absolutePath
        val mappings = mapOf(
            "downloads" to "$storage/Download",
            "download" to "$storage/Download",
            "dcim" to "$storage/DCIM",
            "photos" to "$storage/DCIM/Camera",
            "documents" to "$storage/Documents",
            "music" to "$storage/Music",
            "movies" to "$storage/Movies",
            "pictures" to "$storage/Pictures",
        )

        val lower = path.lowercase()
        return mappings[lower] ?: "$storage/$path"
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}
