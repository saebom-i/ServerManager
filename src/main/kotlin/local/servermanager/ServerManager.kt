package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import org.springframework.scheduling.annotation.Scheduled
import java.nio.file.*
import java.time.Instant
import java.util.UUID
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.*

data class Instance(val id: String, var name: String, val loader: String = "NeoForge", val minecraft: String = "1.21.1", var loaderVersion: String, var port: Int, var memoryMb: Int, var javaPath: String, var runnerId:String?=null)
data class CreateRequest(val name: String, val loaderVersion: String, val port: Int = 25565, val memoryMb: Int = 2048, val javaPath: String = System.getProperty("java.home") + "/bin/java", val loader:String="NeoForge",val minecraft:String="1.21.1")
data class Event(val seq: Long, val time: String, val kind: String, val text: String)
data class FileEntry(val name: String, val path: String, val directory: Boolean, val size: Long)
data class MemoryCapacity(val totalMb: Long, val freeMb: Long, val reservedMb: Long, val maxHeapMb: Long, val committedHeapMb: Long, val startableHeapMb: Long)
data class ServerSignal(val instanceId:String, val kind:String, val detail:String)
interface RemoteController {
    fun start(instance:Instance)
    fun stop(instance:Instance)
    fun restart(instance:Instance)
    fun command(instance:Instance,value:String)
    fun completed(instance:Instance) {}
    fun sharedGamePort():Int?=null
    fun stopIfEmpty(instance:Instance,requestId:String):Boolean=false
}

class RuntimeState {
    @Volatile var process: Process? = null
    var state = "NEW"
    var seq = 0L
    val events = ArrayDeque<Event>()
    @Volatile var resources: ResourceSample? = null
    @Volatile var players: PlayerSample? = null
    @Volatile var playerQueryInProgress = false
    @Volatile var remoteStopRequested = false
    @Volatile var remoteRestartRequested = false
    @Volatile var remoteWorkerReady = false
    @Volatile var remoteCloudReady = false
    @Volatile var remotePid:Long? = null
    @Volatile var diagnosticActive=false
    @Volatile var activityVersion=0L
    @Volatile var stopReason:String?=null
    @Volatile var idleCheckPending=false
    var idleRequestId:String?=null
    var idleRequestAt=0L
    fun event(kind: String, text: String) = synchronized(this) {
        events.addLast(Event(++seq, Instant.now().toString(), kind, text))
        while(events.size > 10000) events.removeFirst()
    }
}

@Service
class ServerManager {
    val root: Path = Path.of(System.getenv("SERVER_MANAGER_DATA") ?: ".local-data").toAbsolutePath().normalize()
    private val mapper = jacksonObjectMapper()
    private val instances = ConcurrentHashMap<String, Instance>()
    private val runtimes = ConcurrentHashMap<String, RuntimeState>()
    private val workers = Executors.newCachedThreadPool()
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(20)).build()
    private val catalog by lazy { VersionCatalog(root.resolve("neoforge-versions.json")) }
    private val software by lazy { LauncherCatalog(root.resolve("launcher-catalogs")) }
    private val installer by lazy { LauncherInstaller(software) }
    private val metrics = ProcessMetrics()
    private val query = MinecraftQuery()
    private val signalListeners = java.util.concurrent.CopyOnWriteArrayList<(ServerSignal)->Unit>()
    @Volatile private var remoteController:RemoteController?=null
    @Volatile private var powerStatus:((String)->PowerStatus)?=null
    fun attachPowerStatus(provider:(String)->PowerStatus){powerStatus=provider}
    private val joined=Regex("(?:logged in with entity id|joined the game)")
    private fun playerActivity(r:RuntimeState,line:String){if(joined.containsMatchIn(line))synchronized(r){r.activityVersion++}}
    fun setDiagnosticActive(id:String,active:Boolean){val r=runtime(id);synchronized(r){check(!active||!r.idleCheckPending){"무인 종료 직전 확인이 끝난 뒤 진단을 시작하세요"};r.diagnosticActive=active;r.activityVersion++}}
    fun attachRemoteController(controller:RemoteController) { remoteController=controller }
    fun onSignal(listener:(ServerSignal)->Unit):AutoCloseable { signalListeners.add(listener); return AutoCloseable { signalListeners.remove(listener) } }
    private fun signal(id:String,kind:String,detail:String) { signalListeners.forEach { listener -> runCatching { listener(ServerSignal(id,kind,detail)) } } }
    @Scheduled(fixedDelay = 2000) fun sampleResources() {
        val pids = mutableSetOf<Long>()
        runtimes.forEach { (id,r) ->
            if(instances[id]?.runnerId!=null) return@forEach
            val process = r.process
            r.resources = if (process?.isAlive == true) { pids.add(process.pid()); metrics.sample(process.toHandle()) } else null
        }
        metrics.retain(pids)
    }
    @Scheduled(fixedDelay = 10000) fun samplePlayers() {
        instances.values.forEach { i ->
            val r=runtimes[i.id] ?: return@forEach
            if(i.runnerId!=null) return@forEach
            if(r.process?.isAlive!=true || r.state!="RUNNING") { r.players=null;return@forEach }
            if(r.playerQueryInProgress) return@forEach
            r.playerQueryInProgress=true
            workers.submit {
                try { r.players=query.players(i.port) }
                finally { r.playerQueryInProgress=false }
            }
        }
    }
    init {
        root.createDirectories()
        Files.list(root).use { dirs -> dirs.filter { it.resolve("instance.json").exists() }.forEach {
            val instance: Instance = mapper.readValue(it.resolve("instance.json").readText())
            instances[instance.id] = instance
            runtimes[instance.id] = RuntimeState().apply { state = when { root.resolve(instance.id).resolve("remote-lease").exists() -> "UNKNOWN"; root.resolve(instance.id).resolve("import.pending").exists() -> "IMPORT_FAILED"; root.resolve(instance.id).resolve("installed.ok").exists() -> "STOPPED"; else -> "NEW" } }
        } }
    }
    fun instance(id: String): Instance = instances[id] ?: throw IllegalArgumentException("인스턴스를 찾을 수 없습니다")
    fun runtime(id: String): RuntimeState { instance(id); return runtimes.getValue(id) }
    fun serverDir(id: String): Path = root.resolve(id).resolve("server")
    private fun save(i: Instance) {
        val file = root.resolve(i.id).resolve("instance.json")
        val tmp = file.resolveSibling("instance.json.tmp")
        tmp.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(i))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    fun list() = instances.values.sortedBy { it.name }.map { view(it.id) }
    @Synchronized fun <T> withIdle(action:()->T):T {
        check(list().all { it["pid"]==null && it["state"] in setOf("NEW","STOPPED","FAILED","IMPORT_FAILED") }) { "모든 서버와 작업을 종료한 뒤 네트워크 설정을 변경하세요" }
        return action()
    }
    @Synchronized fun <T> withStopped(id:String,action:()->T):T {
        val r=runtime(id)
        synchronized(r) {checkIdle(r);check(r.state in setOf("NEW","STOPPED","FAILED","IMPORT_FAILED"));return action()}
    }
    fun capacity(excluding: String? = null): MemoryCapacity {
        val os=java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val total=os.totalMemorySize / (1024*1024)
        val reserve=maxOf(1024L, (total+9)/10)
        val max=maxOf(0L,total-reserve)
        val committed=instances.values.filter { it.id!=excluding && runtimes[it.id]?.process?.isAlive==true }.sumOf { it.memoryMb.toLong() }
        return MemoryCapacity(total,os.freeMemorySize/(1024*1024),reserve,max,committed,maxOf(0L,max-committed))
    }
    fun view(id: String): Map<String, Any?> {
        val i = instance(id); val r = runtime(id)
        val result=synchronized(r) { mapOf("config" to i.copy(), "state" to r.state, "pid" to (if(i.runnerId!=null) r.remotePid else r.process?.takeIf { it.isAlive }?.pid()), "resources" to r.resources?.takeIf { (i.runnerId!=null || r.process?.isAlive == true) && (i.runnerId!=null || it.pid == r.process?.pid()) }, "players" to r.players?.takeIf { r.state=="RUNNING" && java.time.Duration.between(Instant.parse(it.sampledAt),Instant.now()).seconds<30 }, "directory" to serverDir(id).toString(), "eulaAccepted" to eulaAccepted(id),"activityVersion" to r.activityVersion,"diagnosticActive" to r.diagnosticActive) }
        return result+mapOf("powerSaving" to powerStatus?.invoke(id))
    }
    private fun validate(req: CreateRequest) {
        require(req.name.isNotBlank() && req.name.length <= 80) { "이름은 1~80자로 입력하세요" }
        Launchers.validate(req.loader,req.minecraft,req.loaderVersion)
        require(req.port in 1024..65535) { "포트 범위는 1024~65535입니다" }
        val capacity=capacity()
        require(req.memoryMb.toLong() in 512L..capacity.maxHeapMb) { "이 Mac의 메모리 설정 범위는 512~${capacity.maxHeapMb} MiB입니다 (물리 ${capacity.totalMb} MiB, OS 여유 ${capacity.reservedMb} MiB)" }
        require(Path.of(req.javaPath).isExecutable()) { "실행 가능한 Java 경로를 지정하세요" }
    }
    @Synchronized fun create(req: CreateRequest): Map<String, Any?> {
        validate(req)
        require(remoteController?.sharedGamePort()!=null || instances.values.none { it.port == req.port }) { "다른 인스턴스가 사용하는 포트입니다" }
        val i = Instance(UUID.randomUUID().toString(), req.name, loader=req.loader,minecraft=req.minecraft,loaderVersion=req.loaderVersion, port=req.port, memoryMb=req.memoryMb, javaPath=req.javaPath)
        serverDir(i.id).createDirectories(); instances[i.id] = i; runtimes[i.id] = RuntimeState()
        writeProperties(i); save(i); acceptEula(i.id); return view(i.id)
    }
    @Synchronized fun update(id: String, req: CreateRequest): Map<String, Any?> {
        validate(req); val r = runtime(id)
        synchronized(r) {
            checkIdle(r)
            val i = instance(id)
            require(req.loaderVersion == i.loaderVersion && req.loader==i.loader && req.minecraft==i.minecraft) { "설치된 로더 버전 변경은 새 인스턴스로 진행하세요" }
            require(remoteController?.sharedGamePort()!=null || instances.values.none { it.id != id && it.port == req.port }) { "다른 인스턴스가 사용하는 포트입니다" }
            i.name=req.name; i.port=req.port; i.memoryMb=req.memoryMb; i.javaPath=req.javaPath
            writeProperties(i); save(i)
        }
        return view(id)
    }
    @Synchronized fun assignRunner(id:String,runnerId:String?):Map<String,Any?> {
        val r=runtime(id)
        synchronized(r) {
            checkIdle(r)
            check(r.state in setOf("NEW","STOPPED","FAILED","IMPORT_FAILED")) { "서버가 완전히 종료된 뒤 실행기를 변경하세요" }
            instance(id).runnerId=runnerId
            save(instance(id))
        }
        return view(id)
    }
    @Synchronized fun deleteInstance(id:String):Map<String,Any> {
        val r=runtime(id)
        synchronized(r) {
            checkIdle(r)
            val archive=root.resolve("deleted-instances").also { it.createDirectories() }
                .resolve("$id-${System.currentTimeMillis()}")
            Files.move(root.resolve(id),archive,StandardCopyOption.ATOMIC_MOVE)
            r.state="DELETED"
            instances.remove(id);runtimes.remove(id)
            return mapOf("deleted" to id,"archived" to true)
        }
    }
    private fun writeProperties(i: Instance) {
        val path=serverDir(i.id).resolve("server.properties")
        val props=Properties(); if(path.exists()) path.inputStream().use { props.load(it) }
        props.setProperty("server-ip", "127.0.0.1"); props.setProperty("server-port", i.port.toString())
        props.setProperty("enable-rcon", "false"); props.setProperty("enable-query", "true"); props.setProperty("query.port",i.port.toString())
        if(!props.containsKey("online-mode")) props.setProperty("online-mode", "true")
        if(!props.containsKey("view-distance")) props.setProperty("view-distance", "6")
        path.outputStream().use { props.store(it, "Local Server Manager") }
    }
    fun eulaAccepted(id: String): Boolean {
        val path=serverDir(id).resolve("eula.txt"); if(!path.exists()) return false
        return Properties().apply { path.inputStream().use { load(it) } }.getProperty("eula") == "true"
    }
    fun acceptEula(id: String) { val r=runtime(id); synchronized(r) { checkIdle(r); serverDir(id).resolve("eula.txt").writeText("# Accepted through local UI at ${Instant.now()}\neula=true\n") } }
    fun versions(): List<String> = catalog.get().versions
    fun versionCatalog(refresh: Boolean,loader:String="NeoForge",minecraft:String="1.21.1") = if(loader=="NeoForge" && minecraft=="1.21.1")catalog.get(refresh) else software.versions(loader,minecraft,refresh)
    fun minecraftVersions(loader:String,refresh:Boolean=false)=software.games(loader,refresh)
    private fun fetch(url: String): String {
        val res=http.send(HttpRequest.newBuilder(URI(url)).timeout(java.time.Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(res.statusCode()==200) { "다운로드 실패: HTTP ${res.statusCode()}" }; return res.body()
    }
    fun install(id: String) {
        val i=instance(id); val r=runtime(id)
        synchronized(r) {
            checkIdle(r); require(r.state != "IMPORT_FAILED") { "실패한 팩은 원본 mrpack에서 다시 가져오세요" }; require(!root.resolve(id).resolve("installed.ok").exists()) { "이미 설치된 인스턴스입니다" }
            r.state="INSTALLING"; r.event("system", "${i.loader} ${i.loaderVersion} 설치 준비 중")
        }
        workers.submit {
            try {
                installer.install(i,serverDir(id),root.resolve(id).resolve("installer.jar"),{ args ->
                    val p=ProcessBuilder(args).directory(serverDir(id).toFile()).redirectErrorStream(true).start()
                    synchronized(r) { r.process=p }
                    consume(id,p)
                    p.waitFor().also { synchronized(r) { r.process=null } }
                }) { text -> r.event("system",text) }
                root.resolve(id).resolve("installed.ok").writeText(i.loaderVersion)
                synchronized(r) { r.process=null;r.state="STOPPED";r.event("system","${i.loader} 설치 완료") }
            } catch(e: Exception) { synchronized(r) { r.process=null; r.state="FAILED"; r.event("error", e.message ?: "설치 실패") } }
        }
    }
    private fun checkIdle(r: RuntimeState) { check(r.state!="DELETED") { "삭제된 서버입니다" }; check(r.process?.isAlive != true && r.state !in listOf("INSTALLING","STARTING","RUNNING","STOPPING","IMPORTING","TRANSFERRING","SYNCING","SYNC_FAILED","UNKNOWN")) { "서버가 실행 중이거나 작업 중입니다" } }
    @Synchronized fun start(id: String) {
        val i=instance(id); val r=runtime(id)
        synchronized(r) {
            checkIdle(r); require(r.state != "IMPORT_FAILED") { "실패한 팩은 시작할 수 없습니다. 원본 mrpack에서 다시 가져오세요" }; require(eulaAccepted(id)) { "Minecraft EULA에 먼저 동의하세요" }
            r.activityVersion++;r.stopReason=null;r.idleCheckPending=false;r.idleRequestId=null
            if(i.runnerId!=null) {
                val controller=remoteController ?: error("원격 실행기 연결 기능을 사용할 수 없습니다")
                root.resolve(id).resolve("remote-lease").writeText(i.runnerId!!)
                r.remoteStopRequested=false;r.remoteRestartRequested=false;r.state="TRANSFERRING";r.players=null;r.resources=null
                try { controller.start(i.copy()) } catch(e:Exception) { root.resolve(id).resolve("remote-lease").deleteIfExists();r.state="STOPPED";throw e }
                r.event("system","실행기 ${i.runnerId}로 서버 데이터를 인계하고 있습니다")
                return
            }
            require(remoteController?.sharedGamePort()==null) { "공유 중계 모드에서는 별도 실행기를 등록하고 이 서버에 배정하세요" }
            require(instances.values.none { other -> other.id!=id && other.runnerId==null && other.port==i.port && runtimes[other.id]?.process?.isAlive==true }) { "이 컴퓨터에서 같은 게임 포트를 사용하는 서버가 실행 중입니다" }
            val capacity=capacity(id)
            require(i.memoryMb.toLong() in 512L..capacity.startableHeapMb) { "시작 가능한 최대 힙은 ${capacity.startableHeapMb} MiB입니다. 다른 실행 인스턴스가 ${capacity.committedHeapMb} MiB를 예약하고 있습니다" }
            val args=Launchers.arguments(i,serverDir(id))
            Launchers.verifyJava(i.javaPath,Launchers.requiredJava(i,serverDir(id)))
            ServerSocket(i.port,1,InetAddress.getByName("127.0.0.1")).use { }
            writeProperties(i)
            val p=ProcessBuilder(args).directory(serverDir(id).toFile()).redirectErrorStream(true).start()
            r.process=p; r.state="STARTING"; r.players=null; r.event("system","서버 시작 · 127.0.0.1:${i.port} · PID ${p.pid()}")
            workers.submit {
                consume(id,p); val code=p.waitFor()
                val requested=synchronized(r) { val stopped=r.state=="STOPPING"; if(r.process===p) { r.process=null; r.state=if(code==0) "STOPPED" else "FAILED" }; r.event("system","서버 종료 (exit $code)"); stopped }
                signal(id,"stop",if(requested && r.stopReason=="IDLE")"무인 절전 요청 후 서버가 종료되었습니다 (exit $code)." else if(requested) "종료 요청 후 서버가 종료되었습니다 (exit $code)." else "예기치 않게 서버가 종료되었습니다 (exit $code).")
            }
        }
    }
    private fun consume(id: String,p: Process) {
        val r=runtime(id)
        val log=root.resolve(id).resolve("console.log")
        log.toFile().appendText("\n--- ${Instant.now()} ---\n")
        java.io.FileOutputStream(log.toFile(),true).bufferedWriter().use { writer ->
            p.inputStream.bufferedReader().useLines { lines -> lines.forEach { raw ->
                val line=raw.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
                playerActivity(r,line)
                val kind=when { line.contains("ERROR") || line.contains("Exception") -> "error"; line.contains("WARN") -> "warn"; else -> "output" }
                synchronized(r) { if(r.state=="STARTING" && line.contains("Done (") && line.contains("For help")) r.state="RUNNING"; r.event(kind,line) }
                if(kind=="error") signal(id,"error","서버 로그에서 오류가 감지되었습니다. 관리 웹의 콘솔에서 내용을 확인하세요.")
                writer.appendLine(raw); writer.flush()
            } }
        }
    }
    fun command(id: String, command: String) {
        require(command.isNotBlank() && command.length<=4096 && !command.contains('\n') && !command.contains('\r')) { "한 줄 명령을 입력하세요" }
        val r=runtime(id); synchronized(r) {
            check(r.state in listOf("RUNNING","STARTING")) { "실행 중인 서버가 없습니다" }
            val instance=instance(id)
            if(instance.runnerId!=null) {
                val controller=remoteController ?: error("원격 실행기 연결 기능을 사용할 수 없습니다")
                if(command.trim()=="stop") { controller.stop(instance);r.remoteStopRequested=true;r.state="STOPPING" }
                else controller.command(instance,command)
                r.event("input",command)
                return
            }
            val p=r.process ?: error("프로세스가 없습니다")
            p.outputStream.write((command+"\n").toByteArray()); p.outputStream.flush(); r.event("input",command)
            if(command.trim()=="stop") r.state="STOPPING"
        }
    }
    fun stop(id: String,force: Boolean) { val r=runtime(id); synchronized(r) {
        if(instance(id).runnerId!=null) { require(!force) { "원격 서버는 정상 종료를 사용하세요" };command(id,"stop");return }
        if(force) { val p=r.process ?: error("프로세스가 없습니다"); r.state="STOPPING"; p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly(); r.event("system","강제 종료 요청") }
        else command(id,"stop")
    } }
    fun restart(id: String) {
        val r=runtime(id)
        if(instance(id).runnerId!=null) {
            synchronized(r) { check(r.state=="RUNNING") { "실행 중인 서버만 재시작할 수 있습니다" };remoteController?.restart(instance(id)) ?: error("실행기 연결이 없습니다");r.remoteStopRequested=true;r.remoteRestartRequested=true;r.state="STOPPING";r.event("system","원격 서버 재시작 요청") }
            return
        }
        val old=synchronized(r) { check(r.state=="RUNNING") { "실행 중인 서버만 재시작할 수 있습니다" }; val p=r.process!!; command(id,"stop"); p }
        workers.submit {
            if(!old.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)) { r.event("error","정상 종료가 지연되어 재시작을 취소했습니다. 상태를 확인하세요."); return@submit }
            synchronized(r) { if(r.process===old) { r.process=null; r.state="STOPPED" } }
            runCatching { start(id) }.onFailure { r.event("error","재시작 실패: ${it.message}") }
        }
    }
    fun remoteState(id:String,runnerId:String,state:String,detail:String?=null) {
        val i=instance(id);if(i.runnerId!=runnerId || state !in setOf("STARTING","RUNNING","STOPPING","SYNCING","SYNC_FAILED","UNKNOWN")) return
        val r=runtime(id)
        synchronized(r) { r.state=state;detail?.take(1000)?.let { r.event("system",it) } }
    }
    fun remoteStartFailed(id:String,runnerId:String,detail:String?) {
        val i=instance(id);if(i.runnerId!=runnerId)return
        val r=runtime(id)
        synchronized(r) {
            if(r.state!="TRANSFERRING" || r.remotePid!=null)return
            root.resolve(id).resolve("remote-lease").deleteIfExists()
            r.state="FAILED";r.remoteRestartRequested=false
            r.event("error",detail?.take(1000) ?: "실행기에서 서버를 시작하지 못했습니다")
            remoteController?.completed(i)
        }
    }
    fun remoteWorkerReady(id:String,runnerId:String) {
        if(instance(id).runnerId!=runnerId)return
        val r=runtime(id)
        synchronized(r) { if(r.state=="STOPPED") r.remoteWorkerReady=true }
        resumeRemoteRestart(id)
    }
    fun remoteCloudReady(id:String) {
        val r=runtime(id)
        synchronized(r) { if(r.state=="STOPPED") r.remoteCloudReady=true }
        resumeRemoteRestart(id)
    }
    private fun resumeRemoteRestart(id:String) {
        val r=runtime(id)
        val restart=synchronized(r) { if(r.remoteRestartRequested && r.remoteWorkerReady && r.remoteCloudReady && r.state=="STOPPED") { r.remoteRestartRequested=false;true } else false }
        if(restart) workers.submit { runCatching { start(id) }.onFailure { synchronized(r) { r.event("error","원격 서버 재시작 실패: ${it.message}") } } }
    }
    fun remoteLog(id:String,runnerId:String,kind:String,line:String) {
        if(instance(id).runnerId!=runnerId)return
        val r=runtime(id)
        playerActivity(r,line)
        synchronized(r) { r.event(if(kind in setOf("error","warn","output")) kind else "output",line.take(4096)) }
        if(kind=="error") signal(id,"error","원격 서버 로그에서 오류가 감지되었습니다. 관리 웹의 콘솔에서 확인하세요.")
    }
    fun remoteMetrics(id:String,runnerId:String,pid:Long?,cpu:Double?,rss:Double?,uptime:Long?,playerCount:Int?,names:List<String>?) {
        if(instance(id).runnerId!=runnerId)return
        val r=runtime(id)
        synchronized(r) {
            if(r.state !in setOf("STARTING","RUNNING","STOPPING")) return
            r.remotePid=pid?.takeIf { it>0 }
            r.resources=ResourceSample(r.remotePid ?: 0,cpu?.takeIf { it.isFinite() && it>=0 },rss?.takeIf { it.isFinite() && it>=0 },uptime?.takeIf { it>=0 },Instant.now().toString(),"runner")
            r.players=playerCount?.takeIf { it in 0..100000 }?.let { PlayerSample(it,names.orEmpty().take(100).map(String::trim).filter { name->name.isNotBlank() && name.length<=32 },Instant.now().toString()) }
        }
    }
    fun remoteDisconnected(runnerId:String) {
        instances.values.filter { it.runnerId==runnerId }.forEach { i ->
            val r=runtime(i.id)
            synchronized(r) { if(r.state in setOf("STARTING","RUNNING","STOPPING","TRANSFERRING","SYNCING")) { r.state="UNKNOWN";r.players=null;r.resources=null;r.remotePid=null;r.event("system","실행기 연결이 끊겨 서버 상태를 확인할 수 없습니다") } }
        }
    }
    fun remoteCommitted(id:String,runnerId:String) {
        val i=instance(id);require(i.runnerId==runnerId)
        val r=runtime(id)
        val requested=synchronized(r) { val value=r.remoteStopRequested;r.remoteStopRequested=false;r.remoteWorkerReady=false;r.remoteCloudReady=false;r.state="STOPPED";r.players=null;r.resources=null;r.remotePid=null;r.event("system","원격 서버 파일 인계를 완료했습니다");value }
        root.resolve(id).resolve("remote-lease").deleteIfExists()
        remoteController?.completed(i)
        signal(id,"stop",if(requested && r.stopReason=="IDLE")"무인 절전 요청 후 서버 종료와 데이터 인계를 완료했습니다." else if(requested) "종료 요청 후 서버 데이터 인계까지 완료되었습니다." else "서버가 종료되어 데이터 인계를 완료했습니다.")
    }
    fun events(id: String,after: Long): List<Event> { val r=runtime(id); return synchronized(r) { r.events.filter { it.seq>after }.take(2000) } }
    fun stopIfEmpty(id:String,activity:Long):Boolean {
        val i=instance(id);val r=runtime(id)
        val process=synchronized(r) {
            if(r.state!="RUNNING"||r.diagnosticActive||r.activityVersion!=activity||r.idleCheckPending)return false
            if(i.runnerId!=null) {
                val request=UUID.randomUUID().toString();r.idleRequestId=request;r.idleRequestAt=System.nanoTime();r.idleCheckPending=true
                if(remoteController?.stopIfEmpty(i.copy(),request)==true)return true
                r.idleCheckPending=false;r.idleRequestId=null;return false
            }
            r.idleCheckPending=true;r.idleRequestAt=System.nanoTime();r.process
        } ?: run {synchronized(r){r.idleCheckPending=false};return false}
        try {
            val count=query.currentCount(i.port)
            synchronized(r) {
                if(r.process!==process||!process.isAlive||r.state!="RUNNING"||r.diagnosticActive||r.activityVersion!=activity||count!=0){r.players=null;r.activityVersion++;return false}
                r.stopReason="IDLE";command(id,"stop");r.event("system","${powerStatus?.invoke(id)?.config?.minutes ?: 30}분 연속 무인 상태를 확인하여 저장 후 정상 종료합니다")
                return true
            }
        } finally {synchronized(r){r.idleCheckPending=false}}
    }
    fun remoteIdleResult(id:String,runnerId:String,request:String?,accepted:Boolean) {
        if(instance(id).runnerId!=runnerId)return
        val r=runtime(id)
        synchronized(r) {
            if(!r.idleCheckPending||request==null||r.idleRequestId!=request)return
            r.idleCheckPending=false;r.idleRequestId=null
            if(accepted){r.stopReason="IDLE";r.remoteStopRequested=true;if(r.state=="RUNNING")r.state="STOPPING";r.event("system","실행기에서 무인 상태를 다시 확인하여 저장 후 정상 종료합니다")}
            else {r.activityVersion++;r.event("system","실행기의 무인 상태를 확인하지 못해 자동 종료를 취소했습니다")}
        }
    }
    fun expireIdleCheck(id:String) {
        val r=runtime(id);synchronized(r){if(r.idleCheckPending&&System.nanoTime()-r.idleRequestAt>20_000_000_000L){r.idleCheckPending=false;r.idleRequestId=null;r.activityVersion++}}
    }
    fun eventPage(id:String,before:Long?,after:Long?,limit:Int):Map<String,Any?> {
        require(limit in 1..500) { "로그 조회 개수는 1~500입니다" }
        require(before==null || after==null) { "이전 또는 이후 커서 중 하나만 지정하세요" }
        val r=runtime(id)
        return synchronized(r) {
            val rows=when { before!=null -> r.events.filter { it.seq<before }.takeLast(limit); after!=null -> r.events.filter { it.seq>after }.take(limit); else -> r.events.takeLast(limit) }
            mapOf("events" to rows,"hasOlder" to (rows.firstOrNull()?.let { first->r.events.firstOrNull()?.seq?.let { it<first.seq } } ?: false),"oldest" to r.events.firstOrNull()?.seq)
        }
    }
    fun path(id: String, relative: String): Path {
        instance(id); val base=serverDir(id).toRealPath(); val p=base.resolve(relative).normalize()
        require(p.startsWith(base)) { "인스턴스 외부 경로입니다" }
        var ancestor: Path?=p
        while(ancestor!=null && ancestor.startsWith(base)) { require(!Files.isSymbolicLink(ancestor)) { "심볼릭 링크는 지원하지 않습니다" }; ancestor=ancestor.parent }
        return p
    }
    fun files(id: String,relative: String): List<FileEntry> {
        val p=path(id,relative); require(p.isDirectory()) { "폴더가 아닙니다" }
        return Files.list(p).use { entries -> entries.filter { !Files.isSymbolicLink(it) }.map { FileEntry(it.name,serverDir(id).relativize(it).toString(),it.isDirectory(),if(it.isDirectory()) 0 else it.fileSize()) }.toList().sortedWith(compareBy<FileEntry> { !it.directory }.thenBy { it.name }) }
    }
    fun text(id: String,relative: String): Map<String,String> {
        val p=path(id,relative); require(p.isRegularFile() && p.fileSize()<=2*1024*1024) { "2 MiB 이하 텍스트 파일만 편집할 수 있습니다" }
        val bytes=p.readBytes(); require(!bytes.contains(0.toByte())) { "바이너리 파일은 다운로드하세요" }
        return mapOf("content" to bytes.toString(Charsets.UTF_8), "revision" to digest(bytes))
    }
    private fun digest(bytes: ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun write(id: String,relative: String,bytes: ByteArray,revision: String?=null) { val r=runtime(id); synchronized(r) {
        checkIdle(r); val p=path(id,relative); require(p!=serverDir(id)) { "파일 경로를 지정하세요" }
        if(revision!=null) check(p.exists() && digest(p.readBytes())==revision) { "파일이 변경되었습니다. 다시 열어주세요" }
        p.parent.createDirectories()
        if(p.exists()) { val backup=root.resolve(id).resolve("backups").resolve(UUID.randomUUID().toString()+"-"+p.name); backup.parent.createDirectories(); Files.copy(p,backup) }
        val tmp=Files.createTempFile(p.parent,".save-",".tmp")
        try { tmp.writeBytes(bytes); Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) } finally { tmp.deleteIfExists() }
    } }
    fun mkdir(id: String,relative: String) { val r=runtime(id); synchronized(r) { checkIdle(r); path(id,relative).createDirectories() } }
    fun move(id: String,from: String,to: String) { val r=runtime(id); synchronized(r) { checkIdle(r); val source=path(id,from); val target=path(id,to); require(source!=serverDir(id) && !target.exists()) { "대상 경로를 확인하세요" }; Files.move(source,target) } }
    fun delete(id: String,relative: String) { val r=runtime(id); synchronized(r) { checkIdle(r); val p=path(id,relative); require(p!=serverDir(id)) { "서버 루트는 삭제할 수 없습니다" }; val trash=root.resolve(id).resolve("trash"); trash.createDirectories(); Files.move(p,trash.resolve(UUID.randomUUID().toString()+"-"+p.name)) } }
    @PreDestroy fun shutdown() {
        val processes=runtimes.values.mapNotNull { r -> synchronized(r) { r.process } }.filter { it.isAlive }
        processes.forEach { p ->
            runCatching { p.outputStream.write("stop\n".toByteArray()); p.outputStream.flush() }
        }
        processes.forEach { it.waitFor() }
        workers.shutdownNow()
    }
}
