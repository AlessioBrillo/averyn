package dev.averyn.tracking

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.random.Random

// Test helpers on kotlinx-io, so the tests run on every target (iOS included) and not only on the JVM.

internal fun createTestDir(prefix: String): Path {
    val dir = Path(SystemTemporaryDirectory, "$prefix-${Random.nextLong().toULong()}")
    SystemFileSystem.createDirectories(dir)
    return dir
}

internal fun Path.deleteRecursively() {
    if (SystemFileSystem.metadataOrNull(this)?.isDirectory ==
        true
    ) {
        SystemFileSystem.list(this).forEach { it.deleteRecursively() }
    }
    SystemFileSystem.delete(this, mustExist = false)
}

internal fun Path.readBytes(): ByteArray = SystemFileSystem.source(this).buffered().use { it.readByteArray() }

internal fun Path.appendText(text: String) =
    SystemFileSystem.sink(this, append = true).buffered().use {
        it.writeString(text)
    }

internal fun Path.writeText(text: String) =
    SystemFileSystem.sink(this, append = false).buffered().use {
        it.writeString(text)
    }

internal fun Path.readText(): String = readBytes().decodeToString()

/** Every file under this directory, however deep. */
internal fun Path.filesUnder(): List<Path> =
    SystemFileSystem.list(this).flatMap {
        if (SystemFileSystem.metadataOrNull(it)?.isDirectory == true) it.filesUnder() else listOf(it)
    }
