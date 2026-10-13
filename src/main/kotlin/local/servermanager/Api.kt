package local.servermanager

import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.core.io.FileSystemResource
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Path
import kotlin.io.path.*

data class CommandRequest(val command: String)
data class WriteRequest(val path: String, val content: String, val revision: String?=null)
data class PathRequest(val path: String)
data class MoveRequest(val from: String,val to: String)
data class RunnerAssignment(val runnerId:String?)

@RestController
@RequestMapping("/api")
class Api(private val manager: ServerManager,private val accounts:AuthStore,private val runners:RunnerRegistry,private val network:GameNetwork) {
    @GetMapping("/health") fun health()=mapOf("status" to "ok", "mode" to "local", "dataDirectory" to manager.root.toString(), "javaPath" to System.getProperty("java.home")+"/bin/java")
    @GetMapping("/versions") fun versions()=manager.versions()
    @GetMapping("/version-catalog") fun versionCatalog(@RequestParam(defaultValue="false") refresh:Boolean,@RequestParam(defaultValue="NeoForge") loader:String,@RequestParam(defaultValue="1.21.1") minecraft:String)=manager.versionCatalog(refresh,loader,minecraft)
    @GetMapping("/minecraft-versions") fun minecraftVersions(@RequestParam(defaultValue="NeoForge") loader:String,@RequestParam(defaultValue="false") refresh:Boolean)=manager.minecraftVersions(loader,refresh)
    @GetMapping("/memory") fun memory()=manager.capacity()
    @GetMapping("/instances") fun list(authentication:org.springframework.security.core.Authentication)=manager.list().filter { accounts.allowed(authentication.name,(it["config"] as Instance).id,false) }
    @PostMapping("/instances") fun create(@RequestBody req: CreateRequest)=manager.create(req)
    @PutMapping("/instances/{id}") fun update(@PathVariable id: String,@RequestBody req: CreateRequest)=manager.update(id,req)
    @PutMapping("/instances/{id}/runner") fun assignRunner(@PathVariable id:String,@RequestBody request:RunnerAssignment,authentication:org.springframework.security.core.Authentication):Map<String,Any?> {
        if(request.runnerId!=null) require(runners.canAssign(authentication.name,id,request.runnerId)) { "이 서버를 실행할 수 있도록 허용된 실행기를 선택하세요" }
        return manager.assignRunner(id,request.runnerId)
    }
    @DeleteMapping("/instances/{id}") fun deleteInstance(@PathVariable id:String,request:jakarta.servlet.http.HttpServletRequest):Map<String,Any> {
        val verified=request.getSession(false)?.getAttribute("sm.verified") as? Long ?: 0
        if(System.currentTimeMillis()-verified>5*60*1000)throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PRECONDITION_REQUIRED,"서버 삭제 전에 패스키로 다시 인증하세요")
        val result=manager.deleteInstance(id);accounts.removeInstancePermissions(id);runners.removeInstance(id);network.removeInstance(id);return result
    }
    @PostMapping("/instances/{id}/install") fun install(@PathVariable id: String): Map<String,String> { manager.install(id); return mapOf("status" to "INSTALLING") }
    @PostMapping("/instances/{id}/start") fun start(@PathVariable id: String)=manager.start(id)
    @PostMapping("/instances/{id}/stop") fun stop(@PathVariable id: String,@RequestParam(defaultValue="false") force: Boolean)=manager.stop(id,force)
    @PostMapping("/instances/{id}/restart") fun restart(@PathVariable id: String)=manager.restart(id)
    @PostMapping("/instances/{id}/command") fun command(@PathVariable id: String,@RequestBody req: CommandRequest)=manager.command(id,req.command)
    @GetMapping("/instances/{id}/events") fun events(@PathVariable id: String,@RequestParam(defaultValue="0") after: Long)=manager.events(id,after)
    @GetMapping("/instances/{id}/event-page") fun eventPage(@PathVariable id:String,@RequestParam(required=false) before:Long?,@RequestParam(required=false) after:Long?,@RequestParam(defaultValue="300") limit:Int)=manager.eventPage(id,before,after,limit)
    @GetMapping("/instances/{id}/log-files") fun logFiles(@PathVariable id:String):ResponseEntity<List<LogFiles.Entry>> {
        manager.instance(id)
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(LogFiles(manager.root.resolve(id)).list())
    }
    @GetMapping("/instances/{id}/log-file-page") fun logFilePage(@PathVariable id:String,@RequestParam path:String,@RequestParam(defaultValue="0") offset:Long,@RequestParam(defaultValue="300") limit:Int,@RequestParam(required=false) revision:String?):ResponseEntity<LogFiles.Page> {
        manager.instance(id)
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(LogFiles(manager.root.resolve(id)).page(path,offset,limit,revision))
    }
    @GetMapping("/instances/{id}/files") fun files(@PathVariable id: String,@RequestParam(defaultValue="") path: String)=manager.files(id,path)
    @GetMapping("/instances/{id}/text") fun text(@PathVariable id: String,@RequestParam path: String)=manager.text(id,path)
    @PutMapping("/instances/{id}/text") fun write(@PathVariable id: String,@RequestBody req: WriteRequest)=manager.write(id,req.path,req.content.toByteArray(),req.revision)
    @PostMapping("/instances/{id}/folders") fun mkdir(@PathVariable id: String,@RequestBody req: PathRequest)=manager.mkdir(id,req.path)
    @PostMapping("/instances/{id}/move") fun move(@PathVariable id: String,@RequestBody req: MoveRequest)=manager.move(id,req.from,req.to)
    @DeleteMapping("/instances/{id}/files") fun delete(@PathVariable id: String,@RequestParam path: String)=manager.delete(id,path)
    @PostMapping("/instances/{id}/upload") fun upload(@PathVariable id: String,@RequestParam(defaultValue="") path: String,@RequestParam file: MultipartFile) {
        val name=Path.of(file.originalFilename ?: "upload.bin").fileName.toString()
        manager.write(id,if(path.isBlank()) name else "$path/$name",file.bytes)
    }
    @GetMapping("/instances/{id}/download") fun download(@PathVariable id: String,@RequestParam path: String): ResponseEntity<FileSystemResource> {
        val file=manager.path(id,path); require(file.isRegularFile()) { "파일이 아닙니다" }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,org.springframework.http.ContentDisposition.attachment().filename(file.name,Charsets.UTF_8).build().toString()).body(FileSystemResource(file))
    }
    @GetMapping("/instances/{id}/logs") fun logs(@PathVariable id: String): ResponseEntity<FileSystemResource> {
        manager.instance(id)
        val file=manager.root.resolve(id).resolve("console.log")
        require(file.exists()) { "아직 로그가 없습니다" }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=console.log").body(FileSystemResource(file))
    }
}

@RestControllerAdvice
class Errors {
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException::class)
    fun status(e:org.springframework.web.server.ResponseStatusException)=ResponseEntity.status(e.statusCode).body(mapOf("message" to (e.reason ?: "요청을 처리할 수 없습니다")))
    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class, java.io.IOException::class)
    fun handle(e: Exception)=ResponseEntity.status(if(e is IllegalStateException) 409 else 400).body(mapOf("message" to (e.message ?: "작업을 완료할 수 없습니다")))
}
