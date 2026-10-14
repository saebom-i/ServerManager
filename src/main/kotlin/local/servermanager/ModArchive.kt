package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.*

data class ModIdentity(val id:String,val version:String?,val name:String,val provides:Set<String> = emptySet())
data class ModRequirement(val owner:String,val id:String,val ranges:List<String>,val kind:String)
data class ModArchiveInfo(val format:String,val mods:List<ModIdentity>,val requirements:List<ModRequirement>,
    val clientOnly:Boolean=false,val nestedJars:Boolean=false,val complete:Boolean=true,val warnings:List<String> = emptyList(),
    val languageLoader:String?=null,val languageLoaderRange:String?=null)

/** Metadata only; declared nested archives are inspected in bounded temporary files, never loaded. */
object ModArchive {
    private const val MAX_METADATA=1024*1024
    private val mapper=jacksonObjectMapper()
    private fun id(value:String)=value.also {require(it.matches(Regex("[a-z][a-z0-9_-]{0,63}"))) {"모드 ID를 확인하세요"}}
    private fun version(value:String?)=value?.trim()?.takeIf {it.isNotEmpty()}?.also {require(it.length<=256 && it.none {c->c.isISOControl()})}
    private fun bytes(jar:ZipFile,entry:ZipEntry,limit:Int=MAX_METADATA):ByteArray {
        require(entry.size in 0..limit.toLong()) {"모드 메타데이터가 너무 큽니다"}
        return jar.getInputStream(entry).use {it.readNBytes(limit+1)}.also {require(it.size<=limit)}
    }
    fun inspect(file:Path,loader:String):ModArchiveInfo = inspect(file,loader,0,Budget())
    private class Budget(var bytes:Long=0,var count:Int=0)
    private fun inspect(file:Path,loader:String,depth:Int,budget:Budget):ModArchiveInfo {
        require(loader in setOf("fabric","forge","neoforge"))
        require(file.isRegularFile(NOFOLLOW_LINKS) && !Files.isSymbolicLink(file) && file.fileSize() in 1..256L*1024*1024)
        return ZipFile(file.toFile()).use {jar->
            val entries=jar.entries().asSequence().take(10001).toList()
            require(entries.size<=10000) {"JAR 항목이 너무 많습니다"}
            require(entries.map {it.name}.distinct().size==entries.size) {"중복 JAR 메타데이터가 있습니다"}
            val nested=entries.any {!it.isDirectory && it.name.endsWith(".jar",true)}
            val warnings=mutableListOf<String>()

            val result=if(loader=="fabric") {
                val entry=jar.getEntry("fabric.mod.json") ?: return@use ModArchiveInfo("unknown",emptyList(),emptyList(),nestedJars=nested,complete=false,warnings=listOf("Fabric 모드 메타데이터가 없습니다"))
                val node=mapper.readTree(bytes(jar,entry));require(node.isObject)
                val modId=id(node.path("id").asText())
                val aliases=node.path("provides")
                require(aliases.isMissingNode || (aliases.isArray && aliases.size()<=128 && aliases.all {it.isTextual}))
                val provides=if(aliases.isMissingNode)emptySet() else aliases.map {id(it.asText())}.toSet()
                if(node.path("schemaVersion").asInt(-1)!=1)warnings.add("지원하지 않는 Fabric 메타데이터 스키마입니다")
                val requirements=mutableListOf<ModRequirement>()
                for((field,kind) in listOf("depends" to "required","recommends" to "optional","suggests" to "optional","breaks" to "incompatible","conflicts" to "discouraged")) {
                    val values=node.path(field)
                    if(values.isMissingNode)continue
                    require(values.isObject && values.size()<=512)
                    values.fields().forEach {(key,value)->
                        val ranges=if(value.isTextual)listOf(value.asText()) else if(value.isArray && value.size()<=64 && value.all {it.isTextual})value.map {it.asText()} else error("알 수 없는 모드 버전 조건입니다")
                        require(ranges.isNotEmpty() && ranges.all {it.length in 1..256 && it.none {c->c.isISOControl()}})
                        requirements.add(ModRequirement(modId,id(key),ranges,kind))
                    }
                }
                val environment=node.path("environment").asText("*")
                require(environment in setOf("*","client","server"))
                if(!node.path("version").isTextual)warnings.add("모드 버전을 확인하지 못했습니다")
                require(node.path("jars").isMissingNode || (node.path("jars").isArray && node.path("jars").size()<=128))
                val declaredNested=node.path("jars").let {it.isArray && !it.isEmpty}
                if(declaredNested && !nested)warnings.add("선언된 중첩 JAR를 확인하지 못했습니다")
                ModArchiveInfo("fabric",listOf(ModIdentity(modId,version(node.path("version").takeIf {it.isTextual}?.asText()),node.path("name").asText(modId).take(200),provides)),requirements,
                    environment=="client",nested||declaredNested,warnings.isEmpty(),warnings)
            } else {
                val entry=(if(loader=="neoforge")jar.getEntry("META-INF/neoforge.mods.toml") else null) ?: jar.getEntry("META-INF/mods.toml")
                    ?: return@use ModArchiveInfo("unknown",emptyList(),emptyList(),nestedJars=nested,complete=false,warnings=listOf("${loader} 모드 메타데이터가 없습니다"))
                val toml=org.tomlj.Toml.parse(bytes(jar,entry).toString(Charsets.UTF_8));require(!toml.hasErrors()) {"모드 TOML을 읽지 못했습니다"}
                val declarations=toml.getArray("mods") ?: error("모드 선언이 없습니다")
                require(declarations.size() in 1..128)
                val jarVersion=jar.getEntry("META-INF/MANIFEST.MF")?.let {Manifest(bytes(jar,it,65536).inputStream()).mainAttributes.getValue("Implementation-Version")}
                val mods=(0 until declarations.size()).map {n->
                    val table=declarations.getTable(n);val modId=id(table.getString("modId") ?: error("모드 ID가 없습니다"))
                    val raw=table.getString("version")
                    val resolved=if(raw=="\${file.jarVersion}")jarVersion else raw
                    if(resolved==null || resolved.contains("\${"))warnings.add("$modId 버전을 해석하지 못했습니다")
                    ModIdentity(modId,version(resolved?.takeUnless {it.contains("\${")}),table.getString("displayName")?.take(200) ?: modId)
                }
                require(mods.map {it.id}.distinct().size==mods.size)
                val requirements=mutableListOf<ModRequirement>()
                toml.getTable("dependencies")?.let {deps->
                    require(deps.keySet().size<=128)
                    deps.keySet().forEach {owner->
                        require(owner in mods.map {it.id}) {"다른 모드의 의존성이 선언되어 있습니다"}
                        val rows=deps.getArray(owner) ?: error("알 수 없는 의존성 선언입니다");require(rows.size()<=512)
                        for(n in 0 until rows.size()) {
                            val d=rows.getTable(n);val side=d.getString("side") ?: "BOTH"
                            require(side in setOf("CLIENT","SERVER","BOTH"))
                            if(side=="CLIENT")continue
                            val kind=d.getString("type") ?: if(d.getBoolean("mandatory")==false)"optional" else "required"
                            if(kind !in setOf("required","optional","incompatible","discouraged")) {warnings.add("알 수 없는 의존성 종류: ${kind.take(80)}");continue}
                            val range=d.getString("versionRange") ?: "*";require(range.length in 1..256 && range.none {it.isISOControl()})
                            requirements.add(ModRequirement(owner,id(d.getString("modId") ?: error("의존성 ID가 없습니다")),listOf(range),kind))
                        }
                    }
                }
                ModArchiveInfo(if(entry.name.endsWith("neoforge.mods.toml"))"neoforge" else "forge",mods,requirements,nestedJars=nested,complete=warnings.isEmpty(),warnings=warnings.distinct(),
                    languageLoader=toml.getString("modLoader")?.let(::id),languageLoaderRange=version(toml.getString("loaderVersion")))
            }
            val declared=if(loader=="fabric") {
                jar.getEntry("fabric.mod.json")?.let {mapper.readTree(bytes(jar,it)).path("jars").map {it.path("file").asText()}}.orEmpty()
            } else jar.getEntry("META-INF/jarjar/metadata.json")?.let {mapper.readTree(bytes(jar,it)).path("jars").map {it.path("path").asText()}}.orEmpty()
            require(declared.size<=128 && declared.distinct().size==declared.size)
            val children=mutableListOf<ModArchiveInfo>()
            for(name in declared) {
                require(depth<4 && ++budget.count<=128) {"중첩 모드 깊이·개수 제한을 초과했습니다"}
                require(name.isNotBlank() && !name.startsWith("/") && !name.contains('\\') && name.split('/').none {it in setOf("..",".","")} && name.endsWith(".jar",true)) {"중첩 모드 경로가 올바르지 않습니다"}
                val entry=jar.getEntry(name) ?: error("선언된 중첩 모드가 없습니다: $name")
                require(entry.size in 1..32L*1024*1024 && budget.bytes+entry.size<=128L*1024*1024) {"중첩 모드 크기 제한을 초과했습니다"}
                budget.bytes+=entry.size
                val tmp=Files.createTempFile("sm-mod-metadata-",".jar")
                try {
                    jar.getInputStream(entry).use {input->Files.newOutputStream(tmp).use {out->
                        val buffer=ByteArray(65536);var count=0L
                        while(true){val n=input.read(buffer);if(n<0)break;count+=n;require(count<=entry.size);out.write(buffer,0,n)}
                        require(count==entry.size)
                    }}
                    // Jar-in-jar may also declare plain Java libraries without mod metadata.
                    val child=inspect(tmp,loader,depth+1,budget)
                    if((child.format!="unknown" || loader=="fabric" || child.nestedJars) && !child.clientOnly)children.add(child)
                } finally {Files.deleteIfExists(tmp)}
            }
            val undeclared=entries.any {!it.isDirectory && it.name.endsWith(".jar",true) && it.name !in declared}
            val allWarnings=(result.warnings+children.flatMap {it.warnings}+if(undeclared)listOf("선언되지 않은 중첩 JAR가 있어 의존성을 확정할 수 없습니다") else emptyList()).distinct()
            result.copy(mods=result.mods+children.flatMap {it.mods},requirements=result.requirements+children.flatMap {it.requirements},nestedJars=nested||declared.isNotEmpty(),complete=result.complete && children.all {it.complete} && !undeclared,warnings=allWarnings)
        }
    }
}
