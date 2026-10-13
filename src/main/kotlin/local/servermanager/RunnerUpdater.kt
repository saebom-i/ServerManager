package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.*

/** Downloads the same signed application JAR used by the desktop updater. The runner supervisor applies it after idle. */
class RunnerUpdater(private val home:Path,private val current:String) {
    private val mapper=jacksonObjectMapper()
    private val client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build()
    private val repository=RunnerUpdater::class.java.getResourceAsStream("/update-repository.txt")!!.bufferedReader().use { it.readText().trim() }
    private val key=RunnerUpdater::class.java.getResourceAsStream("/update-public-key.der")!!.use { it.readAllBytes() }
    private val beta="-beta." in current
    private val checking=AtomicBoolean(false)
    private val updates=home.resolve("updates")
    fun pending():Boolean {
        val version=updates.resolve("pending.txt").takeIf { it.exists() }?.readText()?.trim() ?: return false
        return runCatching { SignedUpdates.compare(version,current)>0 }.getOrDefault(false)
    }
    fun check() {
        if(!checking.compareAndSet(false,true))return
        try {
            updates.createDirectories()
            val bytes=get(URI("https://api.github.com/repos/$repository/releases?per_page=100"),4*1024*1024)
            val releases=mapper.readTree(bytes).toList()
            val release=releases.filter { !it.path("draft").asBoolean() && SignedUpdates.eligible(it.path("tag_name").asText().removePrefix("v"),it.path("prerelease").asBoolean(),beta) }
                .maxWithOrNull { a,b->SignedUpdates.compare(a.path("tag_name").asText().removePrefix("v"),b.path("tag_name").asText().removePrefix("v")) } ?: return
            val version=release.path("tag_name").asText().removePrefix("v")
            if(SignedUpdates.compare(version,current)<=0 || home.resolve("failed-update-version.txt").takeIf { it.exists() }?.readText()?.trim()==version)return
            val base="https://github.com/$repository/releases/download/v$version/"
            val manifest=get(URI(base+"update.properties"),65536)
            val signature=get(URI(base+"update.sig"),1024)
            val properties=SignedUpdates.manifest(manifest,signature,key)
            require(properties.getProperty("version")==version)
            val folder=updates.resolve(version).also { it.createDirectories() }
            val jar=folder.resolve(properties.getProperty("file"))
            if(!SignedUpdates.validFile(jar,properties)) {
                val temp=Files.createTempFile(folder,".download-",".part")
                try {
                    val limit=properties.getProperty("size").toLong()
                    response(URI(base+properties.getProperty("file"))).body().use { input->temp.outputStream().use { output ->
                        val buffer=ByteArray(65536);var total=0L
                        while(true) { val n=input.read(buffer);if(n<0)break;total+=n;require(total<=limit);output.write(buffer,0,n) }
                        require(total==limit)
                    } }
                    require(SignedUpdates.validFile(temp,properties)) { "실행기 업데이트 해시 불일치" }
                    Files.move(temp,jar,ATOMIC_MOVE,REPLACE_EXISTING)
                } finally { temp.deleteIfExists() }
            }
            folder.resolve("update.properties").writeBytes(manifest)
            folder.resolve("update.sig").writeBytes(signature)
            atomicText(updates.resolve("pending.txt"),version)
        } finally { checking.set(false) }
    }
    private fun response(uri:URI):HttpResponse<java.io.InputStream> {
        var next=uri
        repeat(5) {
            require(next.scheme=="https" && next.userInfo==null && next.host in setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com"))
            val result=client.send(HttpRequest.newBuilder(next).timeout(Duration.ofMinutes(2)).header("User-Agent","ServerManager-Runner/$current").GET().build(),HttpResponse.BodyHandlers.ofInputStream())
            if(result.statusCode() in setOf(301,302,303,307,308)) { result.body().close();next=next.resolve(result.headers().firstValue("Location").orElseThrow());return@repeat }
            check(result.statusCode()==200) { "업데이트 서버 HTTP ${result.statusCode()}" }
            return result
        }
        error("업데이트 리디렉션 한도 초과")
    }
    private fun get(uri:URI,limit:Int)=response(uri).body().use { input->input.readNBytes(limit+1).also { require(it.size<=limit) } }
}
