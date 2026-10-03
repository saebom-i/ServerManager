package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import kotlin.test.*
import java.nio.file.Files
import kotlin.io.path.*

class ServerManagerTest {
    private val manager=ServerManager()
    private val created=mutableListOf<String>()
    private fun instance(port:Int=31001):String {
        val result=manager.create(CreateRequest("test", "21.1.219",port,512))
        return (result["config"] as Instance).id.also { created.add(it) }
    }
    @AfterEach fun cleanup() { manager.shutdown(); created.forEach { manager.root.resolve(it).toFile().deleteRecursively() } }
    @Test fun `file lifecycle preserves backup and detects edit conflicts`() {
        val id=instance()
        manager.mkdir(id,"config")
        manager.write(id,"config/test.txt","first".toByteArray())
        val revision=manager.text(id,"config/test.txt").getValue("revision")
        manager.write(id,"config/test.txt","second".toByteArray(),revision)
        assertFailsWith<IllegalStateException> { manager.write(id,"config/test.txt","stale".toByteArray(),revision) }
        assertEquals("second",manager.text(id,"config/test.txt")["content"])
        assertEquals(1,Files.list(manager.root.resolve(id).resolve("backups")).use { it.count() }.toInt())
        manager.move(id,"config/test.txt","config/renamed.txt")
        manager.delete(id,"config/renamed.txt")
        assertTrue(manager.files(id,"config").isEmpty())
        assertEquals(1,Files.list(manager.root.resolve(id).resolve("trash")).use { it.count() }.toInt())
    }
    @Test fun `paths cannot leave the instance or follow symlinks`() {
        val id=instance()
        assertFailsWith<IllegalArgumentException> { manager.path(id,"../../outside") }
        Files.createSymbolicLink(manager.serverDir(id).resolve("escape"),manager.root)
        assertFailsWith<IllegalArgumentException> { manager.path(id,"escape/secret") }
    }
    @Test fun `memory validation follows actual host capacity`() {
        val cap=manager.capacity()
        assertEquals(cap.totalMb-cap.reservedMb,cap.maxHeapMb)
        assertTrue(cap.reservedMb>=1024)
        assertFailsWith<IllegalArgumentException> { manager.create(CreateRequest("too big","21.1.219",31002,(cap.maxHeapMb+1).toInt())) }
        assertFailsWith<IllegalArgumentException> { manager.create(CreateRequest("too small","21.1.219",31002,511)) }
    }
    @Test fun `ports are unique and settings remain loopback only`() {
        val id=instance()
        assertTrue(manager.eulaAccepted(id))
        assertFailsWith<IllegalArgumentException> { instance() }
        manager.update(id,CreateRequest("renamed","21.1.219",31003,512))
        val text=manager.serverDir(id).resolve("server.properties").readText()
        assertTrue(text.contains("server-port=31003")); assertTrue(text.contains("server-ip=127.0.0.1"))
    }
    @Test fun `writes blocked during installation and command line injection rejected`() {
        val id=instance(); manager.runtime(id).state="INSTALLING"
        assertFailsWith<IllegalStateException> { manager.write(id,"test.txt",byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { manager.command(id,"list\nstop") }
    }
    @Test fun `running instance heaps reduce the available start budget`() {
        val id=instance()
        val process=ProcessBuilder("/bin/sleep","30").start()
        manager.runtime(id).process=process
        try {
            val cap=manager.capacity()
            assertEquals(512L,cap.committedHeapMb)
            assertEquals(cap.maxHeapMb-512,cap.startableHeapMb)
            assertEquals(0L,manager.capacity(id).committedHeapMb)
        } finally { process.destroyForcibly();process.waitFor();manager.runtime(id).process=null }
    }
    @Test fun `instance deletion archives data and rejects in-flight work`() {
        val id=instance(31007)
        manager.write(id,"world/example.dat","world data".toByteArray())
        manager.runtime(id).state="INSTALLING"
        assertFailsWith<IllegalStateException> { manager.deleteInstance(id) }
        assertTrue(manager.serverDir(id).exists())
        manager.runtime(id).state="STOPPED"
        manager.deleteInstance(id)
        assertFalse(manager.serverDir(id).exists())
        assertTrue(manager.list().none { (it["config"] as Instance).id==id })
        val archive=Files.list(manager.root.resolve("deleted-instances")).use { paths->paths.filter { it.name.startsWith(id) }.findFirst().orElseThrow() }
        assertEquals("world data",archive.resolve("server/world/example.dat").readText())
        archive.toFile().deleteRecursively()
    }
}
