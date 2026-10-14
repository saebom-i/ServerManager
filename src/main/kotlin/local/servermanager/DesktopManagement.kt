package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import org.springframework.stereotype.Component
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.context.event.EventListener
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.nio.file.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.concurrent.thread
import kotlin.io.path.*

fun atomicText(path:Path,text:String) { path.parent.createDirectories();val tmp=Files.createTempFile(path.parent,"write-",".tmp");try{tmp.writeText(text);Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}finally{tmp.deleteIfExists()} }
fun validateProfileDirectory(value:String):Path {
    val path=Path.of(value);require(path.isAbsolute) { "절대 경로를 입력하세요" };path.createDirectories();val real=path.toRealPath()
    Files.list(real).use { paths->paths.forEach { entry->require(entry.name.startsWith(".") || entry.name in setOf("security","imports","deleted-instances","neoforge-versions.json") || (entry.isDirectory() && entry.resolve("instance.json").exists())) { "빈 폴더 또는 기존 Server Manager 프로필 폴더를 지정하세요" } } }
    Files.createTempFile(real,".write-check-",".tmp").deleteIfExists();return real
}
@Service
class DesktopLifecycle(private val manager:ServerManager,private val context:ConfigurableApplicationContext) {
    val supervised=System.getenv("SERVER_MANAGER_SUPERVISED")=="1"
    val home:Path=Path.of(System.getenv("SERVER_MANAGER_HOME") ?: System.getProperty("user.home")+"/Library/Application Support/ServerManager")
    val gate=ReentrantReadWriteLock()
    @Volatile var stopping=false
    @Volatile private var requested=false
    fun idle()=manager.list().all { it["pid"]==null && it["diagnosticActive"]!=true && it["modBusy"]!=true && it["state"] in setOf("NEW","STOPPED","FAILED","IMPORT_FAILED") }
    @EventListener(ApplicationReadyEvent::class) fun ready() { System.getenv("SERVER_MANAGER_EXPECTED_VERSION")?.let { expected->check(javaClass.getResourceAsStream("/app-version.txt")!!.bufferedReader().use { it.readText().trim() }==expected) { "업데이트 버전이 일치하지 않습니다" } };System.getenv("SERVER_MANAGER_READY_FILE")?.let { atomicText(Path.of(it),"ready") } }
    fun restartWhenIdle() { check(supervised) { "자동 재시작은 설치 앱에서만 지원합니다" };requested=true }
    @Scheduled(fixedDelay=3000) fun restart() {
        if(!requested||stopping||(!home.resolve("updates/pending.txt").exists()&&!home.resolve("pending-data-location.txt").exists()))return
        gate.write { if(!idle())return;stopping=true }
        thread(name="desktop-restart",isDaemon=false) { Thread.sleep(700);val code=SpringApplication.exit(context,{75});kotlin.system.exitProcess(code) }
    }
}
@Component
class DesktopMaintenanceFilter(private val lifecycle:DesktopLifecycle):OncePerRequestFilter() {
    override fun doFilterInternal(req:HttpServletRequest,res:HttpServletResponse,chain:FilterChain) {
        if(req.method in setOf("GET","HEAD","OPTIONS")){chain.doFilter(req,res);return}
        lifecycle.gate.read { if(lifecycle.stopping){res.status=503;res.characterEncoding="UTF-8";res.contentType="application/json";res.writer.write("{\"message\":\"관리 앱을 재시작하고 있습니다. 잠시 후 새로고침하세요\"}")}else chain.doFilter(req,res) }
    }
}
data class StorageRequest(val path:String)
@RestController
@RequestMapping("/api/admin/storage")
class StorageApi(private val manager:ServerManager,private val desktop:DesktopLifecycle) {
    @GetMapping fun status()=mapOf("supported" to desktop.supervised,"path" to manager.root.toString(),"pending" to desktop.home.resolve("pending-data-location.txt").takeIf { desktop.supervised&&it.exists() }?.readText()?.trim())
    @PutMapping fun change(@RequestBody body:StorageRequest,req:HttpServletRequest):Map<String,Any> {
        freshDesktop(req);check(desktop.supervised) { "저장 위치 변경은 설치 앱에서 지원합니다" };check(desktop.idle()) { "모든 서버와 설치 작업을 정지한 뒤 변경하세요" }
        val next=validateProfileDirectory(body.path);require(next!=manager.root.toRealPath()) { "이미 사용 중인 폴더입니다" }
        atomicText(desktop.home.resolve("pending-data-location.txt"),next.toString());desktop.restartWhenIdle()
        return mapOf("restarting" to true,"path" to next.toString())
    }
}
fun freshDesktop(req:HttpServletRequest) { val at=req.getSession(false)?.getAttribute("sm.verified") as? Long ?: 0;if(System.currentTimeMillis()-at>300000)throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"패스키로 다시 인증하세요") }
