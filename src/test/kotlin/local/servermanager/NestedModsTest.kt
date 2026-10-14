package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class NestedModsTest {
    @TempDir lateinit var temp:Path
    private fun jar(entries:Map<String,ByteArray>):ByteArray=ByteArrayOutputStream().also {bytes->ZipOutputStream(bytes).use {z->entries.forEach {(name,data)->z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry()}}}.toByteArray()
    private fun fabric(id:String,extra:String="")="""{"schemaVersion":1,"id":"$id","version":"1.0"$extra}""".toByteArray()
    @Test fun `declared embedded providers and their dependencies belong to their outer file`() {
        val inner=jar(mapOf("fabric.mod.json" to fabric("nested",",\"provides\":[\"alias\"],\"depends\":{\"library\":\"*\"}")))
        val outer=jar(mapOf("fabric.mod.json" to fabric("outer",",\"jars\":[{\"file\":\"META-INF/jars/nested.jar\"}]"),"META-INF/jars/nested.jar" to inner))
        val file=temp.resolve("outer.jar").also {it.writeBytes(outer)}
        val metadata=ModArchive.inspect(file,"fabric")
        assertTrue(metadata.complete);assertTrue(metadata.nestedJars);assertEquals(setOf("outer","nested"),metadata.mods.map {it.id}.toSet())
        assertEquals("library",metadata.requirements.single().id)
        val unknown=temp.resolve("unknown.jar").also {it.writeBytes(jar(mapOf("fabric.mod.json" to fabric("outer"),"META-INF/jars/nested.jar" to inner)))}
        assertFalse(ModArchive.inspect(unknown,"fabric").complete)
    }
    @Test fun `declared nested archive paths limits and corrupt archives fail closed`() {
        for(path in listOf("../escape.jar","/absolute.jar","a\\b.jar","missing.jar")) {
            val file=temp.resolve("${path.hashCode()}.jar").also {it.writeBytes(jar(mapOf("fabric.mod.json" to fabric("outer",",\"jars\":[{\"file\":\"${path.replace("\\","\\\\")}\"}]"))))}
            assertFails {ModArchive.inspect(file,"fabric")}
        }
        var nested=jar(mapOf("fabric.mod.json" to fabric("inner")))
        repeat(6){nested=jar(mapOf("fabric.mod.json" to fabric("level$it",",\"jars\":[{\"file\":\"inner.jar\"}]"),"inner.jar" to nested))}
        val deep=temp.resolve("deep.jar").also {it.writeBytes(nested)};assertFails {ModArchive.inspect(deep,"fabric")}
    }
    @Test fun `NeoForge jarjar plain libraries are allowed and embedded mods are inspected`() {
        val declaration="""modLoader="javafml"
            loaderVersion="[4,)"
            [[mods]]
            modId="outer"
            version="1.0"
        """.trimIndent().toByteArray()
        val library=jar(mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\n\r\n".toByteArray()))
        val file=temp.resolve("neo.jar").also {it.writeBytes(jar(mapOf("META-INF/neoforge.mods.toml" to declaration,"META-INF/jarjar/metadata.json" to """{"jars":[{"path":"META-INF/jarjar/lib.jar"}]}""".toByteArray(),"META-INF/jarjar/lib.jar" to library)))}
        assertTrue(ModArchive.inspect(file,"neoforge").complete)
    }
}
