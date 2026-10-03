package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.*
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.net.http.*
import java.nio.file.*
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Properties
import kotlin.io.path.*

object SignedUpdates {
    fun compare(a:String,b:String):Int {
        require(a.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))&&b.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "잘못된 버전" }
        return a.split('.').map(String::toInt).zip(b.split('.').map(String::toInt)).map { (x,y)->x.compareTo(y) }.firstOrNull { it!=0 } ?: 0
    }
    fun manifest(bytes:ByteArray,signature:ByteArray,key:ByteArray):Properties {
        val verifier=Signature.getInstance("Ed25519");verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(key)));verifier.update(bytes)
        require(verifier.verify(signature)) { "업데이트 서명을 확인할 수 없습니다" }
        val p=Properties().apply { bytes.inputStream().use { load(it) } };val version=p.getProperty("version","")
        compare(version,"0.0.0");require(p.getProperty("protocol")=="1") { "새 설치 파일로 앱을 업데이트하세요" }
        require(p.getProperty("file")=="server-manager-$version.jar" && p.getProperty("sha256","").matches(Regex("[0-9a-f]{64}")))
        require(p.getProperty("size").toLong() in 1..150L*1024*1024)
        return p
    }
    fun validFile(path:Path,p:Properties):Boolean {
        if(!path.exists()||path.fileSize()!=p.getProperty("size").toLong())return false
        val hash=MessageDigest.getInstance("SHA-256");path.inputStream().use { input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)} }
        return hash.digest().joinToString("") { "%02x".format(it) }==p.getProperty("sha256")
    }
}
@Service
class AppUpdates(private val desktop:DesktopLifecycle) {
    private val mapper=jacksonObjectMapper()
    private val client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build()
    val current=javaClass.getResourceAsStream("/app-version.txt")!!.bufferedReader().use { it.readText().trim() }
    val repository=javaClass.getResourceAsStream("/update-repository.txt")!!.bufferedReader().use { it.readText().trim() }
    private val key=javaClass.getResourceAsStream("/update-public-key.der")!!.use { it.readAllBytes() }
    private val disabled=desktop.home.resolve("updates-disabled")
    @Volatile private var latest:String?=null
    @Volatile private var message="아직 확인하지 않았습니다"
    @Volatile private var checked:String?=null
    @Volatile private var checking=false
    fun status()=mapOf("supported" to desktop.supervised,"enabled" to (desktop.supervised&&!disabled.exists()),"current" to current,"latest" to latest,"message" to message,"checkedAt" to checked,"checking" to checking,"repository" to "https://github.com/$repository")
    @Synchronized fun enabled(value:Boolean){check(desktop.supervised);if(value){disabled.deleteIfExists()}else{atomicText(disabled,"disabled");desktop.home.resolve("updates/pending.txt").deleteIfExists()}}
    @Scheduled(initialDelay=60000,fixedDelay=21600000) fun periodic(){if(desktop.supervised&&!disabled.exists())checkNow()}
    @Synchronized fun checkNow():Map<String,Any?> {
        check(desktop.supervised) { "자동 업데이트는 설치 앱에서 지원합니다" }
        if(checking)return status();checking=true
        try {
            val release=mapper.readTree(get(URI("https://api.github.com/repos/$repository/releases/latest"),1024*1024))
            val version=release.path("tag_name").asText().removePrefix("v");SignedUpdates.compare(version,current);latest=version;checked=Instant.now().toString()
            if(release.path("draft").asBoolean()||release.path("prerelease").asBoolean()||SignedUpdates.compare(version,current)<=0){message="최신 버전입니다";return status()}
            if(desktop.home.resolve("failed-update-version.txt").takeIf { it.exists() }?.readText()?.trim()==version){message="이 버전은 시작 검증에 실패해 자동 적용을 중지했습니다. 다음 릴리스를 기다리거나 설치 파일을 확인하세요.";return status()}
            val base="https://github.com/$repository/releases/download/v$version/"
            val manifest=get(URI(base+"update.properties"),65536);val signature=get(URI(base+"update.sig"),1024);val p=SignedUpdates.manifest(manifest,signature,key)
            require(p.getProperty("version")==version)
            val dir=desktop.home.resolve("updates").resolve(version);dir.createDirectories()
            val target=dir.resolve(p.getProperty("file"))
            if(!SignedUpdates.validFile(target,p)){
                val tmp=Files.createTempFile(dir,"download-",".part")
                try { download(URI(base+p.getProperty("file")),tmp,p.getProperty("size").toLong());require(SignedUpdates.validFile(tmp,p)) { "업데이트 파일 무결성 검증 실패" };Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }finally{tmp.deleteIfExists()}
            }
            Files.write(dir.resolve("update.properties"),manifest);Files.write(dir.resolve("update.sig"),signature)
            atomicText(desktop.home.resolve("updates/pending.txt"),version)
            message="업데이트를 받았습니다. 모든 게임 서버가 정지되면 자동 적용합니다."
            desktop.restartWhenIdle()
        }catch(e:Exception){checked=Instant.now().toString();message=e.message ?: "업데이트 확인 실패"}finally{checking=false}
        return status()
    }
    private fun response(uri:URI):HttpResponse<java.io.InputStream> {
        var next=uri
        repeat(5){
            require(next.scheme=="https"&&next.userInfo==null&&next.host in setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")) { "허용되지 않은 업데이트 주소" }
            val r=client.send(HttpRequest.newBuilder(next).timeout(Duration.ofSeconds(120)).header("User-Agent","ServerManager/$current").GET().build(),HttpResponse.BodyHandlers.ofInputStream())
            if(r.statusCode() in listOf(301,302,303,307,308)){r.body().close();next=next.resolve(r.headers().firstValue("Location").orElseThrow());return@repeat}
            if(r.statusCode()!=200){r.body().close();error(if(r.statusCode()==404)"아직 공개된 릴리스가 없습니다" else "GitHub 업데이트 응답: ${r.statusCode()}")};return r
        };error("업데이트 리디렉션 한도 초과")
    }
    private fun get(uri:URI,limit:Int):ByteArray=response(uri).body().use { input->val bytes=input.readNBytes(limit+1);require(bytes.size<=limit);bytes }
    private fun download(uri:URI,target:Path,limit:Long) { response(uri).body().use { input->target.outputStream().use { out->val buffer=ByteArray(65536);var total=0L;while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=limit) { "업데이트 파일 크기 초과" };out.write(buffer,0,n)};require(total==limit) } } }
}
data class UpdatePreference(val enabled:Boolean)
@RestController
@RequestMapping("/api/admin/updates")
class UpdateApi(private val updates:AppUpdates) {
    @GetMapping fun status()=updates.status()
    @PutMapping fun enabled(@RequestBody body:UpdatePreference,req:HttpServletRequest):Map<String,Any?> { freshDesktop(req);updates.enabled(body.enabled);return updates.status() }
    @PostMapping("/check") fun check(req:HttpServletRequest):Map<String,Any?> { freshDesktop(req);return updates.checkNow() }
}
