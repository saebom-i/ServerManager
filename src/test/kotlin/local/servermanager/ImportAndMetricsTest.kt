package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class ImportAndMetricsTest {
    @TempDir lateinit var temp: Path
    private val mapper=jacksonObjectMapper()
    private fun archive(deps:Map<String,String> = mapOf("minecraft" to "1.21.1","neoforge" to "21.1.252"),extra:Map<String,String> = emptyMap()):Path {
        val file=temp.resolve("${System.nanoTime()}.mrpack")
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries=mapOf("modrinth.index.json" to mapper.writeValueAsString(mapOf("formatVersion" to 1,"game" to "minecraft","name" to "Test pack","versionId" to "1","dependencies" to deps,"files" to emptyList<Any>())))+extra
            entries.forEach { (name,text) -> zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry() }
        };return file
    }
    @Test fun `version catalog parses numeric order beta and keeps cache during outage`() {
        var offline=false
        val catalog=VersionCatalog(temp.resolve("versions.json")) { if(offline) error("offline");"<version>21.1.9</version><version>21.1.12-beta</version><version>21.1.12</version><version>21.2.1</version>" }
        assertEquals(listOf("21.1.12","21.1.12-beta","21.1.9"),catalog.get().versions)
        offline=true
        val result=catalog.get(true)
        assertTrue(result.stale);assertTrue(result.warning!!.contains("offline"));assertEquals(3,result.versions.size)
        assertEquals(3,VersionCatalog(temp.resolve("versions.json")) { error("no network") }.get().versions.size)
    }
    @Test fun `uncached empty upstream response is a visible error`() {
        assertFailsWith<IllegalStateException> { VersionCatalog(temp.resolve("empty.json")) { "<metadata/>" }.get() }
    }
    @Test fun `server overrides win and client overrides are never extracted`() {
        val zip=archive(extra=mapOf("overrides/config/example.txt" to "common","server-overrides/config/example.txt" to "server","client-overrides/config/client.txt" to "client"))
        val plan=PackFormat.inspect(zip);assertEquals(2,plan.overrides.size)
        val out=temp.resolve("stage");PackFormat.overrides(zip,out)
        assertEquals("server",out.resolve("config/example.txt").readText())
        assertFalse(out.resolve("config/client.txt").exists())
    }
    @Test fun `unsupported loaders and traversal are rejected before instance creation`() {
        assertFailsWith<IllegalArgumentException> { PackFormat.inspect(archive(deps=mapOf("minecraft" to "1.21.1","fabric-loader" to "0.16.0"))) }
        assertFailsWith<IllegalArgumentException> { PackFormat.inspect(archive(extra=mapOf("overrides/../../escape" to "bad"))) }
        for(path in listOf("../escape","/tmp/escape","C:/escape","a\\b")) assertFailsWith<IllegalArgumentException> { PackFormat.relative(path) }
        assertFailsWith<IllegalArgumentException> { PackFormat.url("http://localhost/private") }
    }
    @Test fun `files require matching size and both hashes`() {
        val path=temp.resolve("mod.jar");path.writeText("payload")
        fun hash(name:String)=MessageDigest.getInstance(name).digest(path.readBytes()).joinToString("") { "%02x".format(it) }
        val file=PackFile("mods/mod.jar",mapOf("sha1" to hash("SHA-1"),"sha512" to hash("SHA-512")),listOf("https://cdn.modrinth.com/mod.jar"),path.fileSize(),"required")
        PackFormat.verify(path,file)
        path.writeText("corrupt")
        assertFailsWith<IllegalArgumentException> { PackFormat.verify(path,file) }
    }
    private fun mod(name:String,id:String,dependency:String?=null):Path {
        val file=temp.resolve("stage/mods/$name.jar");file.parent.createDirectories()
        val manifest="[[mods]]\nmodId = \"$id\"\nversion = \"1.0\"\n"+(dependency?.let { "[[dependencies.$id]]\nmodId = \"$it\"\ntype = \"required\"\nside = \"BOTH\"\n" } ?: "")
        ZipOutputStream(file.outputStream()).use { zip ->zip.putNextEntry(ZipEntry("META-INF/neoforge.mods.toml"));zip.write(manifest.toByteArray());zip.closeEntry() };return file
    }
    @Test fun `client exclusion refuses required dependency conflicts`() {
        mod("client","clientmod");mod("server","servermod","clientmod")
        val plan=PackPlan("test","1","1.21.1","21.1.252",listOf(PackFile("mods/client.jar",emptyMap(),emptyList(),1,"unsupported")),emptyList(),emptyList())
        assertFailsWith<IllegalArgumentException> { ModInspector.exclude(temp.resolve("stage"),plan,emptySet()) {} }
        temp.resolve("stage/mods/server.jar").deleteExisting()
        ModInspector.exclude(temp.resolve("stage"),plan,emptySet()) {}
        assertFalse(temp.resolve("stage/mods/client.jar").exists())
    }
    @Test fun `process resource sampling reports real RSS and successive CPU samples`() {
        val metrics=ProcessMetrics();val handle=ProcessHandle.current()
        val first=metrics.sample(handle);val second=metrics.sample(handle)
        assertTrue(first.rssMb!!>0);assertNotNull(first.cpuPercent)
        assertNotNull(second.cpuPercent);assertTrue(second.cpuPercent>=0);assertNotNull(second.uptimeSeconds)
    }
    @Test fun `ps fallback parses CPU without Java process info`() {
        assertEquals(125.5 to 2.0,ProcessMetrics.parsePs(" 125.5 2048\n"))
        assertEquals(0.0 to 0.5,ProcessMetrics.parsePs("0.0 512"))
        assertNull(ProcessMetrics.parsePs("permission denied"))
    }
}
