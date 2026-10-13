package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.zip.GZIPOutputStream
import kotlin.io.path.*
import kotlin.test.*

class LogFilesTest {
    @TempDir lateinit var dir:Path
    @Test fun `raw pages preserve UTF8 and boundaries without creating derived files`() {
        val raw=(1..701).joinToString("\r\n") { "줄 $it at test.Method(File.java:$it) {unknown:data}" }
        dir.resolve("console.log").writeText(raw)
        val logs=LogFiles(dir);var page=logs.page("console.log");val lines=page.events.toMutableList()
        assertEquals(300,page.events.size)
        while(page.nextOffset!=null){page=logs.page("console.log",page.nextOffset!!,300,page.revision);lines.addAll(page.events)}
        assertEquals(raw.split("\r\n"),lines.map { it.text });assertEquals(701,lines.map { it.seq }.distinct().size)
        assertEquals(listOf("console.log"),Files.list(dir).use { it.map { p->p.name }.toList() })
        assertNull(page.nextOffset)
    }
    @Test fun `compressed archive and crash reports are listed and paged`() {
        val folder=dir.resolve("server/logs").createDirectories();val crash=dir.resolve("server/crash-reports").createDirectories()
        GZIPOutputStream(folder.resolve("old.log.gz").outputStream()).use { it.write("첫 줄\n둘째 줄\n셋째 줄\n".toByteArray()) }
        crash.resolve("crash.txt").writeText("exception");folder.resolve("private.json").writeText("hidden")
        val logs=LogFiles(dir);assertEquals(2,logs.list().size)
        val first=logs.page("server/logs/old.log.gz",0,2)
        assertEquals(listOf("첫 줄","둘째 줄"),first.events.map { it.text })
        val second=logs.page("server/logs/old.log.gz",first.nextOffset!!,2,first.revision)
        assertEquals(listOf("셋째 줄"),second.events.map { it.text });assertNull(second.nextOffset)
    }
    @Test fun `rejects changed files traversal links and oversized lines`() {
        val log=dir.resolve("console.log");log.writeText("a\nb\n")
        val logs=LogFiles(dir);val first=logs.page("console.log",0,1)
        log.appendText("changed\n")
        assertFailsWith<IllegalStateException>{logs.page("console.log",first.nextOffset!!,1,first.revision)}
        assertFailsWith<IllegalArgumentException>{logs.page("../console.log")}
        val folder=dir.resolve("server/logs").createDirectories();Files.createSymbolicLink(folder.resolve("linked.log"),log)
        assertFalse(logs.list().any { it.path.contains("linked") })
        assertFailsWith<IllegalArgumentException>{logs.page("server/logs/linked.log")}
        assertFailsWith<IllegalArgumentException>{logs.page("console.log",-1)}
        log.writeText("x".repeat(1024*1024+1))
        assertFailsWith<IllegalArgumentException>{logs.page("console.log")}
    }
}
