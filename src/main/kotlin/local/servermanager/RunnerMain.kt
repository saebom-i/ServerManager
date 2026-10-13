package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.net.Socket
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.*

data class RunnerIdentity(val managerUrl:String,val id:String,val name:String,val privateKey:String,val platform:String)

object RunnerMain {
    private val mapper=jacksonObjectMapper()
    private val home=Path.of(System.getenv("SERVER_MANAGER_RUNNER_HOME") ?: Path.of(System.getProperty("user.home"),".servermanager-runner").toString()).toAbsolutePath().normalize()
    private val identityFile=home.resolve("identity.json")
    private fun privateFile(path:Path,bytes:ByteArray) {
        path.parent.createDirectories()
        val temp=Files.createTempFile(path.parent,".runner-",".tmp")
        try {
            temp.writeBytes(bytes)
            if(!System.getProperty("os.name").startsWith("Windows")) Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"))
            Files.move(temp,path,ATOMIC_MOVE,REPLACE_EXISTING)
        } finally { temp.deleteIfExists() }
    }
    fun run(args:Array<String>) {
        // Background log files and first-run prompts must not inherit an ASCII
        // process locale and replace Korean text with question marks.
        System.setOut(PrintStream(System.out,true,StandardCharsets.UTF_8))
        System.setErr(PrintStream(System.err,true,StandardCharsets.UTF_8))
        when {
            "--runner-pair" in args -> pair(args)
            "--runner-status" in args -> {
                val identity=if(identityFile.exists()) mapper.readValue<RunnerIdentity>(identityFile.readText()) else null
                println(identity?.let { "${it.name} (${it.id}) · ${it.managerUrl}" } ?: "등록되지 않은 실행기")
            }
            else -> {
                println("Server Manager 실행기 대기 시작")
                if(!identityFile.exists()) println("이 컴퓨터는 아직 등록되지 않았습니다. 관리 웹의 ‘서버 실행기’에서 패스키로 코드를 발급하고 pair.command / pair.cmd로 등록하세요.")
                System.getenv("SERVER_MANAGER_RUNNER_READY_FILE")?.let { Path.of(it).writeText("ready") }
                var agent:RunnerAgent?=null
                var currentId:String?=null
                Runtime.getRuntime().addShutdownHook(Thread { agent?.shutdownGracefully() })
                while(true) {
                    val identity=if(identityFile.exists()) runCatching { mapper.readValue<RunnerIdentity>(identityFile.readText()) }.getOrNull() else null
                    if(identity==null) { Thread.sleep(10000);continue }
                    if(currentId!=identity.id) { agent=RunnerAgent(home,identity);currentId=identity.id }
                    try { agent!!.connectLoop() }
                    catch(e:Exception) { System.err.println("중앙 서버 연결 대기: ${e.javaClass.simpleName}");Thread.sleep(5000) }
                    if(agent!!.restartForUpdate()) kotlin.system.exitProcess(75)
                }
            }
        }
    }
    private fun pair(args:Array<String>) {
        val url=args.firstOrNull { it.startsWith("--manager-url=") }?.substringAfter('=')?.trimEnd('/') ?: error("--manager-url=https://관리주소 를 지정하세요")
        val uri=URI(url)
        require(uri.scheme=="https" && uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null && uri.path.isNullOrEmpty()) { "HTTPS 관리 주소를 입력하세요" }
        require(!identityFile.exists()) { "이미 등록된 실행기입니다. 등록을 폐기한 뒤 다시 등록하세요" }
        val console=System.console()
        if(console==null) { print("실행기 등록 코드: ");System.out.flush() }
        val code=console?.readPassword("실행기 등록 코드: ")?.concatToString()
            ?: System.`in`.bufferedReader().readLine()?.trim()
            ?: error("등록 코드를 입력하세요")
        require(code.length in 30..100)
        val pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKey=Base64.getUrlEncoder().withoutPadding().encodeToString(pair.public.encoded)
        val platform=if(System.getProperty("os.name").startsWith("Windows")) "WINDOWS" else "MAC"
        val version=RunnerAgent.version()
        val body=mapper.writeValueAsBytes(RunnerPairRequest(code,publicKey,platform,version))
        val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
        val response=client.send(HttpRequest.newBuilder(URI("$url/api/runners/pair")).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode()==200) { "실행기 등록 실패: HTTP ${response.statusCode()}" }
        val result=mapper.readValue<Map<String,String>>(response.body())
        val identity=RunnerIdentity(url,result.getValue("id"),result.getValue("name"),Base64.getUrlEncoder().withoutPadding().encodeToString(pair.private.encoded),platform)
        privateFile(identityFile,mapper.writeValueAsBytes(identity))
        println("실행기 등록 완료: ${identity.name}. --runner 로 연결을 시작하세요.")
    }
}

class RunnerAgent(private val home:Path,private val identity:RunnerIdentity):WebSocket.Listener {
    private val mapper=jacksonObjectMapper()
    private val client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build()
    private val workers=Executors.newCachedThreadPool()
    private val updater=if(System.getenv("RUNNER_PACKAGE_HOME")!=null) RunnerUpdater(home,version()) else null
    @Volatile private var lastUpdateCheck=0L
    private val connection=AtomicReference<WebSocket?>()
    @Volatile private var closed=CountDownLatch(1)
    private val textBuffer=StringBuilder()
    private val binaryBuffer=ByteArrayOutputStream()
    private val sendLock=Any()
    @Volatile private var sessionToken:String?=null
    private val processes=ConcurrentHashMap<String,Process>()
    private val gameStreams=ConcurrentHashMap<Int,Socket>()
    private val configs=ConcurrentHashMap<String,Instance>()
    private val uploading=ConcurrentHashMap.newKeySet<String>()
    private val stopping=ConcurrentHashMap.newKeySet<String>()
    private val query=MinecraftQuery()
    private val metrics=ProcessMetrics()
    private fun instanceRoot(id:String)=home.resolve("instances").resolve(id)
    private fun serverDir(id:String)=instanceRoot(id).resolve("server")
    private fun dirty(id:String)=instanceRoot(id).resolve("dirty")
    private fun orphanAlive(id:String):Boolean {
        val marker=dirty(id)
        if(!marker.exists() || processes[id]?.isAlive==true)return false
        val parts=runCatching { marker.readText().trim().split('|') }.getOrNull() ?: return true
        if(parts.size!=2)return true // Older markers have no PID; never assume the world is idle.
        val pid=parts[0].toLongOrNull() ?: return true
        val started=parts[1].toLongOrNull() ?: return true
        val process=ProcessHandle.of(pid).orElse(null) ?: return false
        return process.isAlive && process.info().startInstant().orElse(null)?.toEpochMilli()==started
    }
    private fun javaBin()=Path.of(System.getProperty("java.home"),"bin",if(identity.platform=="WINDOWS") "java.exe" else "java").toString()
    private fun signingKey()=KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(Base64.getUrlDecoder().decode(identity.privateKey)))
    fun connectLoop() {
        closed=CountDownLatch(1)
        val timestamp=System.currentTimeMillis()
        val nonce=ByteArray(24).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val bytes="sm-runner-v1\n${identity.id}\n$timestamp\n$nonce\n".toByteArray()
        val signature=Signature.getInstance("Ed25519").apply { initSign(signingKey());update(bytes) }.sign()
        val encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        val uri=URI(identity.managerUrl.replaceFirst("https://","wss://")+"/api/runners/connect")
        val socket=client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(20))
            .header("X-SM-Runner-Id",identity.id).header("X-SM-Timestamp",timestamp.toString())
            .header("X-SM-Nonce",nonce).header("X-SM-Signature",encoded)
            .buildAsync(uri,this).join()
        connection.set(socket)
        if(socket.isOutputClosed) closed.countDown()
        while(closed.count>0 && socket.isOutputClosed.not()) {
            val now=System.currentTimeMillis()
            val updateClient=updater
            if(updateClient!=null && now-lastUpdateCheck>6*60*60*1000L) {
                lastUpdateCheck=now
                workers.submit { runCatching { updateClient.check() }.onFailure { System.err.println("실행기 업데이트 확인 실패: ${it.javaClass.simpleName}") } }
            }
            if(restartForUpdate()) kotlin.system.exitProcess(75)
            send(RunnerMessage("heartbeat",version=version()))
            processes.forEach { (id,process) -> report(id,process) }
            if(sessionToken!=null) home.resolve("instances").takeIf { it.exists() }?.let { root -> Files.list(root).use { dirs -> dirs.filter { it.isDirectory() && it.resolve("dirty").exists() && processes[it.fileName.toString()]?.isAlive!=true }.forEach { dir ->
                val id=dir.fileName.toString()
                configs[id] ?: runCatching { mapper.readValue<Instance>(dir.resolve("instance.json").readText()) }.getOrNull()?.let { configs[id]=it }
                if(configs[id]!=null) {
                    if(orphanAlive(id))send(RunnerMessage("state",id,"UNKNOWN",version(),"실행기 재시작 전 프로세스가 실행 중이어서 데이터 인계를 보류합니다"))
                    else workers.submit { uploadStopped(id) }
                }
            } } }
            closed.await(10,TimeUnit.SECONDS)
        }
        sessionToken=null;connection.set(null)
        gameStreams.values.forEach { runCatching { it.close() } };gameStreams.clear()
    }
    fun restartForUpdate():Boolean {
        if(updater?.pending()!=true || processes.values.any { it.isAlive } || uploading.isNotEmpty())return false
        val root=home.resolve("instances")
        return !root.exists() || Files.list(root).use { paths -> paths.noneMatch { it.resolve("dirty").exists() } }
    }
    fun shutdownGracefully() {
        processes.keys.forEach { stop(it) }
        processes.values.forEach { runCatching { it.waitFor() } }
        home.resolve("instances").takeIf { it.exists() }?.let { root -> Files.list(root).use { paths -> paths.filter { it.resolve("dirty").exists() }.forEach { dir -> uploadStopped(dir.fileName.toString()) } } }
    }
    override fun onOpen(webSocket:WebSocket) { webSocket.request(1) }
    override fun onText(webSocket:WebSocket,data:CharSequence,last:Boolean):CompletionStage<*> {
        synchronized(textBuffer) {
            textBuffer.append(data)
            if(last) {
                val raw=textBuffer.toString();textBuffer.setLength(0)
                runCatching { mapper.readValue<RunnerMessage>(raw) }.getOrNull()?.let { message ->
                    when(message.type) {
                        "session" -> { sessionToken=message.token;recoverStates() }
                        "start" -> if(message.instanceId!=null && message.detail!=null) workers.submit { beginStart(message.instanceId,message.detail) }
                        "stop" -> message.instanceId?.let { stop(it) }
                        "restart" -> message.instanceId?.let { stop(it) }
                        "command" -> if(message.instanceId!=null && message.command!=null) command(message.instanceId,message.command)
                        "open" -> if(message.instanceId!=null && message.streamId!=null) openGame(message.instanceId,message.streamId)
                        "close" -> message.streamId?.let { gameStreams.remove(it)?.close() }
                    }
                }
            }
        }
        webSocket.request(1);return CompletableFuture.completedFuture(null)
    }
    override fun onBinary(webSocket:WebSocket,data:ByteBuffer,last:Boolean):CompletionStage<*> {
        synchronized(binaryBuffer) {
            val bytes=ByteArray(data.remaining());data.get(bytes)
            if(binaryBuffer.size()+bytes.size>65540) { binaryBuffer.reset();webSocket.request(1);return CompletableFuture.completedFuture(null) }
            binaryBuffer.write(bytes)
            if(last) {
                val frame=ByteBuffer.wrap(binaryBuffer.toByteArray());binaryBuffer.reset()
                if(frame.remaining()>=4) {
                    val stream=gameStreams[frame.int]
                    if(stream!=null) {
                        val chunk=ByteArray(frame.remaining());frame.get(chunk)
                        runCatching { stream.getOutputStream().write(chunk);stream.getOutputStream().flush() }.onFailure { gameStreams.entries.removeIf { it.value===stream };runCatching { stream.close() } }
                    }
                }
            }
        }
        webSocket.request(1);return CompletableFuture.completedFuture(null)
    }
    override fun onClose(webSocket:WebSocket,statusCode:Int,reason:String):CompletionStage<*> { if(connection.get()===webSocket)closed.countDown();return CompletableFuture.completedFuture(null) }
    override fun onError(webSocket:WebSocket,error:Throwable) { if(connection.get()===webSocket)closed.countDown() }
    private fun send(message:RunnerMessage):Boolean {
        val socket=connection.get() ?: return false
        return runCatching { synchronized(sendLock) { socket.sendText(mapper.writeValueAsString(message),true).join() };true }.getOrDefault(false)
    }
    private fun sendBinary(streamId:Int,bytes:ByteArray,count:Int):Boolean {
        val socket=connection.get() ?: return false
        return runCatching {
            val frame=ByteBuffer.allocate(4+count).putInt(streamId).put(bytes,0,count).flip()
            synchronized(sendLock) { socket.sendBinary(frame,true).join() };true
        }.getOrDefault(false)
    }
    private fun openGame(instanceId:String,streamId:Int) {
        val port=configs[instanceId]?.port ?: return
        if(processes[instanceId]?.isAlive!=true || gameStreams.size>=100)return
        workers.submit {
            val socket=Socket()
            try {
                socket.connect(InetSocketAddress("127.0.0.1",port),3000)
                socket.soTimeout=60000
                gameStreams[streamId]=socket
                if(!send(RunnerMessage("opened",instanceId=instanceId,streamId=streamId)))return@submit
                val input=socket.getInputStream();val buffer=ByteArray(64*1024)
                while(true) { val count=input.read(buffer);if(count<0)break;if(!sendBinary(streamId,buffer,count))break }
            } catch (_:Exception) {}
            finally {
                gameStreams.remove(streamId,socket)
                runCatching { socket.close() }
                send(RunnerMessage("closed",instanceId=instanceId,streamId=streamId))
            }
        }
    }
    private fun http(method:String,path:String,body:HttpRequest.BodyPublisher=HttpRequest.BodyPublishers.noBody(),contentType:String?=null):HttpResponse<ByteArray> {
        val token=sessionToken ?: error("실행기 연결이 끊겼습니다")
        val builder=HttpRequest.newBuilder(URI(identity.managerUrl+path)).timeout(Duration.ofMinutes(10))
            .header("X-SM-Runner-Id",identity.id).header("X-SM-Session",token)
        if(contentType!=null) builder.header("Content-Type",contentType)
        return client.send(builder.method(method,body).build(),HttpResponse.BodyHandlers.ofByteArray())
    }
    private fun encode(value:String)=URLEncoder.encode(value,Charsets.UTF_8)
    private fun beginStart(id:String,rawConfig:String) {
        try {
            check(processes[id]?.isAlive!=true) { "이미 실행 중입니다" }
            check(!dirty(id).exists()) { "이전 실행의 데이터 인계가 완료되지 않았습니다" }
            val instance=mapper.readValue<Instance>(rawConfig)
            require(instance.id==id && instance.runnerId==identity.id)
            configs[id]=instance
            val root=instanceRoot(id).also { it.createDirectories() }
            root.resolve("instance.json").writeText(mapper.writeValueAsString(instance))
            send(RunnerMessage("state",id,"TRANSFERRING",version(),"서버 파일을 받고 있습니다"))
            pull(id)
            startProcess(instance)
        } catch(e:Exception) { send(RunnerMessage("state",id,"SYNC_FAILED",version(),e.message?.take(300) ?: "시작 실패")) }
    }
    private fun pull(id:String) {
        val response=http("GET","/api/runners/transfer/$id/manifest")
        check(response.statusCode()==200) { "서버 파일 목록을 받지 못했습니다: HTTP ${response.statusCode()}" }
        val manifest=mapper.readValue<RunnerFileManifest>(response.body())
        val root=instanceRoot(id)
        val stage=root.resolve("stage-${UUID.randomUUID()}").also { it.createDirectories() }
        try {
            manifest.directories.forEach { FileInventory.safePath(stage,it).createDirectories() }
            val existing=serverDir(id).takeIf { it.exists() }?.let { FileInventory.collect(it).first }.orEmpty()
            manifest.files.forEach { (relative,file) ->
                val target=FileInventory.safePath(stage,relative)
                target.parent.createDirectories()
                if(existing[relative]==file) Files.copy(FileInventory.safePath(serverDir(id),relative),target)
                else {
                    target.outputStream().use { output ->
                        var offset=0L
                        while(offset<file.size) {
                            val part=http("GET","/api/runners/transfer/$id/file?path=${encode(relative)}&offset=$offset")
                            check(part.statusCode()==200 && part.body().isNotEmpty()) { "서버 파일 다운로드 실패: $relative" }
                            output.write(part.body());offset+=part.body().size
                        }
                    }
                }
                check(target.fileSize()==file.size && FileInventory.digest(target)==file.sha256) { "서버 파일 해시 불일치: $relative" }
            }
            val current=serverDir(id)
            if(current.exists()) {
                val backup=root.resolve("backup-${System.currentTimeMillis()}")
                Files.move(current,backup,ATOMIC_MOVE)
                try { Files.move(stage,current,ATOMIC_MOVE) } catch(e:Exception) { Files.move(backup,current,ATOMIC_MOVE);throw e }
            } else Files.move(stage,current,ATOMIC_MOVE)
        } finally { if(stage.exists())stage.toFile().deleteRecursively() }
    }
    private fun startProcess(instance:Instance) {
        val id=instance.id
        val current=processes.values.filter { it.isAlive }.sumOf { p -> configs.values.firstOrNull { processes[it.id]===p }?.memoryMb?.toLong() ?: 0 }
        val os=java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val limit=maxOf(0L,os.totalMemorySize/(1024*1024)-maxOf(1024L,os.totalMemorySize/(1024*1024)/10)-current)
        require(instance.memoryMb.toLong() in 512L..limit) { "실행기 메모리가 부족합니다 (최대 $limit MiB)" }
        val args=serverDir(id).resolve("libraries/net/neoforged/neoforge/${instance.loaderVersion}/${if(identity.platform=="WINDOWS") "win_args.txt" else "unix_args.txt"}")
        require(args.isRegularFile()) { "NeoForge 실행 파일이 없습니다" }
        val properties=serverDir(id).resolve("server.properties")
        val values=Properties();properties.inputStream().use { values.load(it) }
        values.setProperty("server-ip","127.0.0.1");values.setProperty("server-port",instance.port.toString())
        values.setProperty("enable-query","true");values.setProperty("query.port",instance.port.toString())
        properties.outputStream().use { values.store(it,"Server Manager Runner") }
        val java=javaBin()
        val process=ProcessBuilder(java,"-Xms512M","-Xmx${instance.memoryMb}M","@${args}","nogui")
            .directory(serverDir(id).toFile()).redirectErrorStream(true).start()
        processes[id]=process
        dirty(id).writeText("${process.pid()}|${process.info().startInstant().orElse(Instant.now()).toEpochMilli()}")
        send(RunnerMessage("state",id,"STARTING",version(),"원격 Minecraft 서버 시작",pid=process.pid()))
        workers.submit {
            val log=instanceRoot(id).resolve("console.log")
            log.toFile().appendText("\n--- ${Instant.now()} ---\n")
            FileOutputStream(log.toFile(),true).bufferedWriter().use { writer ->
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                val kind=if(line.contains("ERROR") || line.contains("Exception")) "error" else if(line.contains("WARN")) "warn" else "output"
                writer.appendLine(line);writer.flush()
                send(RunnerMessage("log",id,kind,version(),line.take(4096)))
                if(line.contains("Done (") && line.contains("For help")) send(RunnerMessage("state",id,"RUNNING",version(),pid=process.pid()))
            } } }
            val code=process.waitFor()
            stopping.remove(id)
            processes.remove(id,process)
            send(RunnerMessage("state",id,"SYNCING",version(),"종료 코드 $code · 변경 파일 인계 중"))
            uploadStopped(id)
        }
    }
    private fun uploadStopped(id:String) {
        if(!dirty(id).exists() || processes[id]?.isAlive==true || orphanAlive(id) || sessionToken==null || !uploading.add(id))return
        try {
            val (files,dirs)=FileInventory.collect(serverDir(id))
            val manifest=RunnerFileManifest(files,dirs)
            val response=http("POST","/api/runners/transfer/$id/begin",HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(RunnerTransferBegin(manifest))),"application/json")
            check(response.statusCode()==200) { "파일 인계 시작 실패: HTTP ${response.statusCode()}" }
            val plan=mapper.readValue<RunnerTransferPlan>(response.body())
            plan.changedFiles.forEach { path ->
                val file=FileInventory.safePath(serverDir(id),path)
                file.inputStream().use { input ->
                    var offset=0L
                    while(true) {
                        val bytes=input.readNBytes(4*1024*1024)
                        if(bytes.isEmpty())break
                        val part=http("PUT","/api/runners/transfer/$id/chunk?transferId=${plan.transferId}&path=${encode(path)}&offset=$offset",HttpRequest.BodyPublishers.ofByteArray(bytes),"application/octet-stream")
                        check(part.statusCode()==200) { "파일 청크 업로드 실패: HTTP ${part.statusCode()}" }
                        offset+=bytes.size
                    }
                }
            }
            val done=http("POST","/api/runners/transfer/$id/commit?transferId=${plan.transferId}")
            check(done.statusCode()==200) { "서버 파일 인계 완료 실패: HTTP ${done.statusCode()}" }
            dirty(id).deleteIfExists()
            send(RunnerMessage("state",id,"STOPPED",version(),"서버 파일 인계 완료"))
        } catch(e:Exception) { send(RunnerMessage("state",id,"SYNC_FAILED",version(),e.message?.take(300) ?: "서버 파일 인계 실패")) }
        finally { uploading.remove(id) }
    }
    private fun stop(id:String) {
        val process=processes[id] ?: return
        stopping.add(id)
        runCatching { process.outputStream.write("stop\n".toByteArray());process.outputStream.flush() }
        send(RunnerMessage("state",id,"STOPPING",version(),"저장 후 종료 중",pid=process.pid()))
    }
    private fun command(id:String,value:String) {
        if(value.isBlank() || value.length>4096 || value.contains('\n') || value.contains('\r')) return
        processes[id]?.takeIf { it.isAlive }?.let { runCatching { it.outputStream.write((value+"\n").toByteArray());it.outputStream.flush() } }
    }
    private fun report(id:String,process:Process) {
        if(!process.isAlive)return
        val sample=metrics.sample(process.toHandle())
        val players=configs[id]?.let { query.players(it.port) }
        send(RunnerMessage("state",id,if(id in stopping)"STOPPING" else "RUNNING",version(),pid=process.pid(),cpuPercent=sample.cpuPercent,rssMb=sample.rssMb,
            uptimeSeconds=sample.uptimeSeconds,playerCount=players?.count,players=players?.names))
    }
    private fun recoverStates() {
        processes.forEach { (id,process)->report(id,process) }
        home.resolve("instances").takeIf { it.exists() }?.let { root -> Files.list(root).use { dirs -> dirs.filter { it.isDirectory() && it.resolve("dirty").exists() && processes[it.fileName.toString()]?.isAlive!=true }.forEach { dir ->
            val id=dir.fileName.toString()
            if(orphanAlive(id))send(RunnerMessage("state",id,"UNKNOWN",version(),"실행기 재시작 전 Minecraft 프로세스가 계속 실행 중입니다"))
            else workers.submit { uploadStopped(id) }
        } } }
    }
    companion object { fun version()=RunnerAgent::class.java.getResourceAsStream("/app-version.txt")?.bufferedReader()?.use { it.readText().trim() } ?: "dev" }
}
