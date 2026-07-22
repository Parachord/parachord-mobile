package com.parachord.shared.plugin

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS implementation of [PluginFileAccess].
 *
 * - **Bundled** plugins live under `Bundle.main/axe-plugins/` (added at build
 *   time when the iOS app shell ships).
 *
 *   The directory is `axe-plugins`, NOT `plugins`: iOS reserves `PlugIns/` for
 *   app extensions, and the build filesystem on macOS is case-INSENSITIVE, so a
 *   bundled resource folder named `plugins` merges with it. That silently swept
 *   `ShareExtension.appex` into the resources folder and produced an archive
 *   with no `PlugIns/` at all — App Store validation rejects it (90680 / 90171 /
 *   90354) and the extension can't register on a real (case-sensitive) device.
 *   The simulator hides the bug because it runs off the host's case-insensitive
 *   filesystem. Keep this name distinct from `PlugIns` in any case-folding.
 * - **Cached** plugins live under `<NSApplicationSupportDirectory>/plugins/`
 *   so PluginSyncService can write hot-reload updates between app launches.
 *   (That path is outside the app bundle, so it can't collide — left as-is.)
 *
 * Filename validation matches the Android implementation (security: H2)
 * — the same `^[A-Za-z0-9_.-]+\.axe$` regex prevents path traversal via
 * attacker-controlled manifest IDs.
 */
@OptIn(ExperimentalForeignApi::class)
actual class PluginFileAccess {

    private val pluginsCacheDir: String by lazy {
        val paths = NSFileManager.defaultManager.URLsForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomains = NSUserDomainMask,
        )
        val appSupport = (paths.firstOrNull() as? NSURL)?.path ?: ""
        val dir = "$appSupport/plugins"
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        dir
    }

    /**
     * Allowed filename pattern for cached plugins.
     * security: H2 — prevents path traversal via attacker-controlled manifest.id
     */
    private val safeFilenameRegex = Regex("^[A-Za-z0-9_.-]+\\.axe$")

    private fun validateSafeFilename(filename: String) {
        // Kotlin/Native has no `SecurityException`; use `IllegalArgumentException`.
        // The exception type isn't load-bearing — callers catch generic Exception.
        if (!safeFilenameRegex.matches(filename)) {
            throw IllegalArgumentException("Unsafe plugin filename: '$filename'")
        }
        if (filename.contains("..") || filename.startsWith(".")) {
            throw IllegalArgumentException("Unsafe plugin filename: '$filename'")
        }
    }

    actual fun listBundledPlugins(): List<String> {
        val bundlePath = NSBundle.mainBundle.pathForResource("axe-plugins", ofType = null)
            ?: return emptyList()
        val contents = NSFileManager.defaultManager.contentsOfDirectoryAtPath(bundlePath, null)
            ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (contents as List<String>).filter { safeFilenameRegex.matches(it) }
    }

    actual fun readBundledPlugin(filename: String): String {
        validateSafeFilename(filename)
        val bundlePath = NSBundle.mainBundle.pathForResource("axe-plugins", ofType = null)
            ?: return ""
        val filePath = "$bundlePath/$filename"
        return readFileAsString(filePath)
    }

    actual fun listCachedPlugins(): List<String> {
        val contents = NSFileManager.defaultManager.contentsOfDirectoryAtPath(pluginsCacheDir, null)
            ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (contents as List<String>).filter { safeFilenameRegex.matches(it) }
    }

    actual fun readCachedPlugin(filename: String): String {
        validateSafeFilename(filename)
        val filePath = "$pluginsCacheDir/$filename"
        return readFileAsString(filePath)
    }

    actual fun writeCachedPlugin(filename: String, content: String) {
        validateSafeFilename(filename)
        val filePath = "$pluginsCacheDir/$filename"
        // Encode to NSData and atomically write. Going through NSData (rather
        // than `NSString.writeToFile`) avoids the ambiguous Objective-C overload
        // and gives us explicit UTF-8 control.
        val data = (content as platform.Foundation.NSString)
            .dataUsingEncoding(NSUTF8StringEncoding)
        data?.writeToFile(filePath, atomically = true)
    }

    actual val cacheDir: String
        get() = pluginsCacheDir

    /**
     * Read a file as a UTF-8 string via NSData → NSString. Avoids the
     * `NSString.stringWithContentsOfFile:encoding:error:` overload which
     * doesn't resolve cleanly in the Kotlin/Native bridge.
     */
    private fun readFileAsString(path: String): String {
        val data: NSData = NSData.dataWithContentsOfFile(path) ?: return ""
        @Suppress("CAST_NEVER_SUCCEEDS")
        return NSString.create(data = data, encoding = NSUTF8StringEncoding) as? String ?: ""
    }
}
