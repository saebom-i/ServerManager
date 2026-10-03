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

data class Instance(val id: String, var name: String, val loader: String = "NeoForge", val minecraft: String = "1.21.1", var loaderVersion: String, var port: Int, var memoryMb: Int, var javaPath: String)
data class CreateRequest(val name: String, val loaderVersion: String, val port: Int = 25565, val memoryMb: Int = 2048, val javaPath: String = System.getProperty("java.home") + "/bin/java")
data class Event(val seq: Long, val time: String, val kind: String, val text: String)
data class FileEntry(val name: String, val path: String, val directory: Boolean, val size: Long)
data class MemoryCapacity(val totalMb: Long, val freeMb: Long, val reservedMb: Long, val maxHeapMb: Long, val committedHeapMb: Long, val startableHeapMb: Long)

class RuntimeState {
    @Volatile var process: Process? = null
    var state = "NEW"
    var seq = 0L
    val events = ArrayDeque<Event>()
    @Volatile var resources: ResourceSample? = null
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
    private val metrics = ProcessMetrics()
    @Scheduled(fixedDelay = 2000) fun sampleResources() {
        val pids = mutableSetOf<Long>()
        runtimes.values.forEach { r ->
            val process = r.process
            r.resources = if (process?.isAlive == true) { pids.add(process.pid()); metrics.sample(process.toHandle()) } else null
        }
        metrics.retain(pids)
    }
    init {
        root.createDirectories()
        Files.list(root).use { dirs -> dirs.filter { it.resolve("instance.json").exists() }.forEach {
            val instance: Instance = mapper.readValue(it.resolve("instance.json").readText())
            instances[instance.id] = instance
            runtimes[instance.id] = RuntimeState().apply { state = when { root.resolve(instance.id).resolve("import.pending").exists() -> "IMPORT_FAILED"; root.resolve(instance.id).resolve("installed.ok").exists() -> "STOPPED"; else -> "NEW" } }
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
        return synchronized(r) { mapOf("config" to i.copy(), "state" to r.state, "pid" to r.process?.takeIf { it.isAlive }?.pid(), "resources" to r.resources?.takeIf { r.process?.isAlive == true && it.pid == r.process?.pid() }, "directory" to serverDir(id).toString(), "eulaAccepted" to eulaAccepted(id)) }
    }
    private fun validate(req: CreateRequest) {
        require(req.name.isNotBlank() && req.name.length <= 80) { "이름은 1~80자로 입력하세요" }
        require(req.loaderVersion.matches(Regex("21\\.1\\.[0-9]+(?:-beta)?"))) { "1.21.1용 NeoForge 21.1.x 버전을 선택하세요" }
        require(req.port in 1024..65535) { "포트 범위는 1024~65535입니다" }
        val capacity=capacity()
        require(req.memoryMb.toLong() in 512L..capacity.maxHeapMb) { "이 Mac의 메모리 설정 범위는 512~${capacity.maxHeapMb} MiB입니다 (물리 ${capacity.totalMb} MiB, OS 여유 ${capacity.reservedMb} MiB)" }
        require(Path.of(req.javaPath).isExecutable()) { "실행 가능한 Java 경로를 지정하세요" }
    }
    @Synchronized fun create(req: CreateRequest): Map<String, Any?> {
        validate(req)
        require(instances.values.none { it.port == req.port }) { "다른 인스턴스가 사용하는 포트입니다" }
        val i = Instance(UUID.randomUUID().toString(), req.name, loaderVersion=req.loaderVersion, port=req.port, memoryMb=req.memoryMb, javaPath=req.javaPath)
        serverDir(i.id).createDirectories(); instances[i.id] = i; runtimes[i.id] = RuntimeState()
        writeProperties(i); save(i); acceptEula(i.id); return view(i.id)
    }
    @Synchronized fun update(id: String, req: CreateRequest): Map<String, Any?> {
        validate(req); val r = runtime(id)
        synchronized(r) {
            checkIdle(r)
            val i = instance(id)
            require(req.loaderVersion == i.loaderVersion) { "설치된 로더 버전 변경은 새 인스턴스로 진행하세요" }
            require(instances.values.none { it.id != id && it.port == req.port }) { "다른 인스턴스가 사용하는 포트입니다" }
            i.name=req.name; i.port=req.port; i.memoryMb=req.memoryMb; i.javaPath=req.javaPath
            writeProperties(i); save(i)
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
        props.setProperty("enable-rcon", "false"); props.setProperty("enable-query", "false")
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
    fun versionCatalog(refresh: Boolean) = catalog.get(refresh)
    private fun fetch(url: String): String {
        val res=http.send(HttpRequest.newBuilder(URI(url)).timeout(java.time.Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(res.statusCode()==200) { "다운로드 실패: HTTP ${res.statusCode()}" }; return res.body()
    }
    fun install(id: String) {
        val i=instance(id); val r=runtime(id)
        synchronized(r) {
            checkIdle(r); require(r.state != "IMPORT_FAILED") { "실패한 팩은 원본 mrpack에서 다시 가져오세요" }; require(!root.resolve(id).resolve("installed.ok").exists()) { "이미 설치된 인스턴스입니다" }
            r.state="INSTALLING"; r.event("system", "NeoForge ${i.loaderVersion} 다운로드 중")
        }
        workers.submit {
            try {
                val javaVersion=ProcessBuilder(i.javaPath,"-version").redirectErrorStream(true).start()
                val versionText=javaVersion.inputStream.bufferedReader().readText(); javaVersion.waitFor()
                require(Regex("version \"21[.\"]").containsMatchIn(versionText)) { "Minecraft 1.21.1은 Java 21을 사용하세요: $versionText" }
                val url="https://maven.neoforged.net/releases/net/neoforged/neoforge/${i.loaderVersion}/neoforge-${i.loaderVersion}-installer.jar"
                val jar=root.resolve(id).resolve("installer.jar")
                val res=http.send(HttpRequest.newBuilder(URI(url)).timeout(java.time.Duration.ofMinutes(5)).build(),HttpResponse.BodyHandlers.ofFile(jar))
                check(res.statusCode()==200) { "설치기 다운로드 실패: ${res.statusCode()}" }
                val expected=fetch("$url.sha256").trim().substringBefore(' ')
                val actual=java.security.MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
                check(expected.equals(actual,true)) { "설치기 체크섬 불일치" }
                val p=ProcessBuilder(i.javaPath,"-jar",jar.toString(),"--installServer").directory(serverDir(id).toFile()).redirectErrorStream(true).start()
                synchronized(r) { r.process=p }
                consume(id,p)
                val code=p.waitFor()
                if(code==0) root.resolve(id).resolve("installed.ok").writeText(i.loaderVersion)
                synchronized(r) { r.process=null; r.state=if(code==0) "STOPPED" else "FAILED"; r.event("system","설치 종료 (exit $code)") }
            } catch(e: Exception) { synchronized(r) { r.process=null; r.state="FAILED"; r.event("error", e.message ?: "설치 실패") } }
        }
    }
    private fun checkIdle(r: RuntimeState) { check(r.state!="DELETED") { "삭제된 서버입니다" }; check(r.process?.isAlive != true && r.state !in listOf("INSTALLING","STARTING","STOPPING","IMPORTING")) { "서버가 실행 중이거나 작업 중입니다" } }
    @Synchronized fun start(id: String) {
        val i=instance(id); val r=runtime(id)
        synchronized(r) {
            checkIdle(r); require(r.state != "IMPORT_FAILED") { "실패한 팩은 시작할 수 없습니다. 원본 mrpack에서 다시 가져오세요" }; require(eulaAccepted(id)) { "Minecraft EULA에 먼저 동의하세요" }
            val capacity=capacity(id)
            require(i.memoryMb.toLong() in 512L..capacity.startableHeapMb) { "시작 가능한 최대 힙은 ${capacity.startableHeapMb} MiB입니다. 다른 실행 인스턴스가 ${capacity.committedHeapMb} MiB를 예약하고 있습니다" }
            val args=serverDir(id).resolve("libraries/net/neoforged/neoforge/${i.loaderVersion}/unix_args.txt")
            require(args.exists()) { "먼저 NeoForge를 설치하세요" }
            ServerSocket(i.port,1,InetAddress.getByName("127.0.0.1")).use { }
            writeProperties(i)
            val p=ProcessBuilder(i.javaPath,"-Xms512M","-Xmx${i.memoryMb}M","@${args}","nogui").directory(serverDir(id).toFile()).redirectErrorStream(true).start()
            r.process=p; r.state="STARTING"; r.event("system","서버 시작 · 127.0.0.1:${i.port} · PID ${p.pid()}")
            workers.submit {
                consume(id,p); val code=p.waitFor()
                synchronized(r) { if(r.process===p) { r.process=null; r.state=if(code==0) "STOPPED" else "FAILED" }; r.event("system","서버 종료 (exit $code)") }
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
                val kind=when { line.contains("ERROR") || line.contains("Exception") -> "error"; line.contains("WARN") -> "warn"; else -> "output" }
                synchronized(r) { if(r.state=="STARTING" && line.contains("Done (") && line.contains("For help")) r.state="RUNNING"; r.event(kind,line) }
                writer.appendLine(raw); writer.flush()
            } }
        }
    }
    fun command(id: String, command: String) {
        require(command.isNotBlank() && command.length<=4096 && !command.contains('\n') && !command.contains('\r')) { "한 줄 명령을 입력하세요" }
        val r=runtime(id); synchronized(r) {
            check(r.state in listOf("RUNNING","STARTING")) { "실행 중인 서버가 없습니다" }
            val p=r.process ?: error("프로세스가 없습니다")
            p.outputStream.write((command+"\n").toByteArray()); p.outputStream.flush(); r.event("input",command)
            if(command.trim()=="stop") r.state="STOPPING"
        }
    }
    fun stop(id: String,force: Boolean) { val r=runtime(id); synchronized(r) {
        if(force) { val p=r.process ?: error("프로세스가 없습니다"); r.state="STOPPING"; p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly(); r.event("system","강제 종료 요청") }
        else command(id,"stop")
    } }
    fun restart(id: String) {
        val r=runtime(id)
        val old=synchronized(r) { check(r.state=="RUNNING") { "실행 중인 서버만 재시작할 수 있습니다" }; val p=r.process!!; command(id,"stop"); p }
        workers.submit {
            if(!old.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)) { r.event("error","정상 종료가 지연되어 재시작을 취소했습니다. 상태를 확인하세요."); return@submit }
            synchronized(r) { if(r.process===old) { r.process=null; r.state="STOPPED" } }
            runCatching { start(id) }.onFailure { r.event("error","재시작 실패: ${it.message}") }
        }
    }
    fun events(id: String,after: Long): List<Event> { val r=runtime(id); return synchronized(r) { r.events.filter { it.seq>after }.take(2000) } }
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
        runtimes.values.forEach { r -> val process=synchronized(r) { r.process }; process?.takeIf { it.isAlive }?.let { p ->
            runCatching { p.outputStream.write("stop\n".toByteArray()); p.outputStream.flush() }
            if(!p.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)) { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }
        } }; workers.shutdownNow()
    }
}
