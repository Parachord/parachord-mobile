package com.parachord.shared.ios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Kotlin/Native aborts the iOS app (SIGABRT in
 * `Kotlin_ObjCExport_ExceptionAsNSError`) when a suspend function Swift calls
 * throws anything but `CancellationException` and has no `@Throws`. Swift's
 * `try` / `try?` can't catch it. An Amazon Music playlist import crashed the
 * app this way (#792).
 *
 * This test reads the sources and fails when:
 * 1. a public, non-override `suspend fun` in `shared/src/iosMain` has no
 *    `@Throws` (everything there is the Swift-facing bridge), or
 * 2. Swift calls (`try await x.name(` / `try? await x.name(`) a name that is
 *    only declared as a commonMain `suspend fun` without `@Throws`.
 *
 * Rule 2 matches by name, not by type: a name counts as safe when any of its
 * declarations has `@Throws`. [SWIFT_NAMES] lists Swift's own functions whose
 * names happen to match a commonMain suspend function.
 */
class SwiftSuspendThrowsTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "iosApp").isDirectory }

    private data class Decl(val name: String, val throws: Boolean, val where: String)

    private fun decls(dir: String): List<Decl> =
        File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val lines = file.readLines()
            lines.mapIndexedNotNull { i, line ->
                val m = DECL.find(line) ?: return@mapIndexedNotNull null
                var throws = "@Throws" in line
                var j = i - 1
                while (j >= 0 && lines[j].trim().startsWith("@")) {
                    if (lines[j].trim().startsWith("@Throws")) throws = true
                    j--
                }
                Decl(m.groupValues[1], throws, "${file.relativeTo(root)}:${i + 1}")
            }
        }.toList()

    @Test
    fun `every iosMain suspend function has Throws`() {
        val missing = decls("shared/src/iosMain").filterNot { it.throws }
        assertTrue(
            missing.isEmpty(),
            "Add @Throws(Throwable::class) so an exception reaches Swift as an error instead of aborting the app:\n" +
                missing.joinToString("\n") { "  ${it.where} ${it.name}" },
        )
    }

    @Test
    fun `every commonMain suspend function Swift calls has Throws`() {
        val all = decls("shared/src/commonMain") + decls("shared/src/iosMain")
        val byName = all.groupBy { it.name }
        val swift = File(root, "iosApp/Parachord").walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
        val bad = swift.flatMap { file ->
            file.readLines().flatMapIndexed { i, line ->
                CALL.findAll(line).mapNotNull { m ->
                    val name = m.groupValues[1]
                    val ds = byName[name] ?: return@mapNotNull null
                    if (name in SWIFT_NAMES || ds.any { it.throws }) null
                    else "  ${file.relativeTo(root)}:${i + 1} calls $name (${ds.first().where})"
                }.toList()
            }
        }.toList()
        if (bad.isNotEmpty()) {
            fail(
                "Swift calls these Kotlin suspend functions, which have no @Throws. Add " +
                    "@Throws(Throwable::class), or add a Swift function's name to SWIFT_NAMES:\n" +
                    bad.joinToString("\n"),
            )
        }
    }

    private companion object {
        /** A public, non-override, non-private suspend fun; group 1 is its name. */
        val DECL = Regex(
            """^\s*(?:(?:public|open|final|actual)\s+)*suspend\s+fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(\w+)\s*\(""",
        )

        /** `try await x.y.name(` / `try? await $0.name(`; group 1 is the name. */
        val CALL = Regex("""\btry\??\s+await\s+[\w$.?!()\[\]]*?\.(\w+)\s*\(""")

        /** Swift functions that share a name with a commonMain suspend function. */
        val SWIFT_NAMES = setOf(
            "run", // MusicKitDeadline.run
        )
    }
}
