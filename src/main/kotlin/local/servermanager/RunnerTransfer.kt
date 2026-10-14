package local.servermanager

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlin.io.path.*

data class RunnerFileManifest(val files:Map<String,CloudFile>,val directories:Set<String>)
data class RunnerTransferBegin(val manifest:RunnerFileManifest,val importRequest:String?=null)
data class RunnerTransferPlan(val transferId:String,val changedFiles:Set<String>)
private data class PendingTransfer(val id:String,val runnerId:String,val instanceId:String,val manifest:RunnerFileManifest,val changed:Set<String>,val folder:Path,
    val importRequest:String?=null,
    val progress:MutableMap<String,Long> = mutableMapOf(),var bytes:Long=0,var files:Int=0,
    val totalBytes:Long=changed.sumOf {manifest.files.getValue(it).size})

@Service
class RunnerTransfer(private val manager:ServerManager,private val registry:RunnerRegistry) {
    private val pending=mutableMapOf<String,PendingTransfer>()
    private fun report(transfer:PendingTransfer,phase:String,message:String="") {
        val value=FolderImportProgress(phase,transfer.files,transfer.bytes,transfer.changed.size,transfer.totalBytes,message)
        if(transfer.importRequest!=null)manager.folderImportProgress(transfer.instanceId,transfer.runnerId,transfer.importRequest,value)
        else manager.fileTransferProgress(transfer.instanceId,transfer.runnerId,value)
    }
    fun inventory(id:String):RunnerFileManifest {
        val (files,dirs)=FileInventory.collect(manager.serverDir(id))
        return RunnerFileManifest(files,dirs)
    }
    @Synchronized fun begin(runnerId:String,instanceId:String,manifest:RunnerFileManifest,importRequest:String?=null):RunnerTransferPlan {
        require(manager.instance(instanceId).runnerId==runnerId && registry.active(runnerId)?.allowedInstanceIds?.contains(instanceId)==true)
        if(importRequest!=null)manager.checkFolderImport(instanceId,runnerId,importRequest)
        else check(!manager.root.resolve(instanceId).resolve("folder-import.pending").exists()) { "기존 폴더 가져오기 작업이 진행 중입니다" }
        require(manifest.files.size<=100000 && manifest.directories.size<=100000)
        manifest.files.forEach { (path,file) ->
            FileInventory.safePath(manager.serverDir(instanceId),path)
            require(file.size in 0..(1024L*1024*1024*1024) && file.sha256.matches(Regex("[a-f0-9]{64}")))
        }
        manifest.directories.forEach { FileInventory.safePath(manager.serverDir(instanceId),it) }
        require(manifest.files.keys.intersect(manifest.directories).isEmpty())
        if(importRequest!=null)manager.folderImportProgress(instanceId,runnerId,importRequest,FolderImportProgress("SCANNING",message="관리 서버의 기존 파일과 비교하고 있습니다"))
        else manager.fileTransferProgress(instanceId,runnerId,FolderImportProgress("SCANNING",message="관리 서버의 기존 파일과 비교하고 있습니다"))
        val old=inventory(instanceId)
        val changed=manifest.files.filter { (path,file)->old.files[path]!=file }.keys
        pending.values.filter { it.instanceId==instanceId }.forEach { previous ->
            check(previous.runnerId==runnerId) { "다른 실행기가 파일 인계 중입니다" }
            pending.remove(previous.id)
            previous.folder.toFile().deleteRecursively()
            previous.folder.parent.resolve("stage-${previous.id}").toFile().deleteRecursively()
        }
        val id=UUID.randomUUID().toString()
        val folder=manager.root.resolve(instanceId).resolve("runner-transfer").resolve(id).also { it.createDirectories() }
        changed.filter { manifest.files.getValue(it).size==0L }.forEach { path ->
            val target=FileInventory.safePath(folder,path)
            target.parent.createDirectories();target.writeBytes(byteArrayOf())
        }
        val transfer=PendingTransfer(id,runnerId,instanceId,manifest,changed,folder,importRequest,files=changed.count {manifest.files.getValue(it).size==0L})
        pending[id]=transfer;report(transfer,"UPLOADING")
        return RunnerTransferPlan(id,changed)
    }
    @Synchronized fun upload(runnerId:String,instanceId:String,transferId:String,path:String,offset:Long,bytes:ByteArray) {
        val transfer=pending[transferId] ?: error("파일 인계가 만료되었습니다")
        require(transfer.runnerId==runnerId && transfer.instanceId==instanceId)
        transfer.importRequest?.let {manager.checkFolderImport(instanceId,runnerId,it)}
        require(path in transfer.changed && bytes.size in 1..4*1024*1024)
        val file=transfer.manifest.files.getValue(path)
        val progress=transfer.progress[path] ?: 0L
        check(offset==progress && offset+bytes.size<=file.size) { "파일 청크 순서가 맞지 않습니다" }
        val target=FileInventory.safePath(transfer.folder,path)
        target.parent.createDirectories()
        FileChannel.open(target,CREATE,WRITE).use { channel -> channel.position(offset);val remaining=ByteBuffer.wrap(bytes);while(remaining.hasRemaining())channel.write(remaining) }
        transfer.progress[path]=offset+bytes.size
        transfer.bytes+=bytes.size
        if(offset+bytes.size==file.size)transfer.files++
        report(transfer,"UPLOADING")
    }
    @Synchronized fun commit(runnerId:String,instanceId:String,transferId:String) {
        val transfer=pending[transferId] ?: error("파일 인계가 만료되었습니다")
        require(transfer.runnerId==runnerId && transfer.instanceId==instanceId)
        transfer.importRequest?.let {manager.checkFolderImport(instanceId,runnerId,it)}
        report(transfer,"VERIFYING","관리 서버에서 파일 해시를 검증하고 반영하고 있습니다")
        val oldBase=manager.serverDir(instanceId)
        val stage=transfer.folder.parent.resolve("stage-${transfer.id}")
        stage.createDirectories()
        transfer.manifest.directories.forEach { FileInventory.safePath(stage,it).createDirectories() }
        transfer.manifest.files.forEach { (relative,file) ->
            val source=if(relative in transfer.changed) FileInventory.safePath(transfer.folder,relative) else FileInventory.safePath(oldBase,relative)
            check(source.isRegularFile() && source.fileSize()==file.size && FileInventory.digest(source)==file.sha256) { "파일 검증에 실패했습니다: $relative" }
            val destination=FileInventory.safePath(stage,relative)
            destination.parent.createDirectories()
            Files.copy(source,destination,REPLACE_EXISTING)
        }
        check(inventoryFrom(stage)==transfer.manifest) { "인계할 파일 목록이 일치하지 않습니다" }
        transfer.importRequest?.let {manager.checkFolderImport(instanceId,runnerId,it)}
        val backup=manager.root.resolve(instanceId).resolve("runner-backups").also { it.createDirectories() }.resolve("server-${System.currentTimeMillis()}")
        Files.move(oldBase,backup,ATOMIC_MOVE)
        try { Files.move(stage,oldBase,ATOMIC_MOVE) }
        catch(e:Exception) { Files.move(backup,oldBase,ATOMIC_MOVE);throw e }
        pending.remove(transferId)
        transfer.folder.toFile().deleteRecursively()
        stage.toFile().deleteRecursively()
        manager.remoteCommitted(instanceId,runnerId,transfer.importRequest)
    }
    private fun inventoryFrom(base:Path):RunnerFileManifest {
        val (files,dirs)=FileInventory.collect(base)
        return RunnerFileManifest(files,dirs)
    }
}

@RestController
@RequestMapping("/api/runners/transfer")
class RunnerTransferApi(private val transfer:RunnerTransfer,private val gateway:RunnerGateway,private val manager:ServerManager) {
    private fun check(runnerId:String,token:String?,instanceId:String) {
        if(!gateway.authorized(runnerId,token) || manager.instance(instanceId).runnerId!=runnerId)
            throw ResponseStatusException(HttpStatus.FORBIDDEN,"실행기 연결 또는 서버 배정을 확인하세요")
    }
    @PostMapping("/{instanceId}/exit") fun exited(@PathVariable instanceId:String,@RequestBody report:RunnerExitReport,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):Map<String,Boolean> {
        check(runnerId,token,instanceId);manager.remoteExited(instanceId,runnerId,report);return mapOf("ok" to true)
    }
    @GetMapping("/{instanceId}/import-state") fun importState(@PathVariable instanceId:String,@RequestParam request:String,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):Map<String,String> {
        check(runnerId,token,instanceId);return mapOf("state" to manager.folderImportState(instanceId,runnerId,request))
    }
    @GetMapping("/{instanceId}/manifest") fun manifest(@PathVariable instanceId:String,@RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):RunnerFileManifest {
        check(runnerId,token,instanceId);return transfer.inventory(instanceId)
    }
    @GetMapping("/{instanceId}/file") fun file(@PathVariable instanceId:String,@RequestParam path:String,@RequestParam offset:Long,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):ResponseEntity<ByteArray> {
        check(runnerId,token,instanceId)
        val source=manager.path(instanceId,path)
        require(source.isRegularFile() && offset>=0 && offset<=source.fileSize())
        val count=minOf(4L*1024*1024,source.fileSize()-offset).toInt()
        val bytes=ByteArray(count)
        FileChannel.open(source).use { channel -> channel.position(offset);val buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) { if(channel.read(buffer)<0) error("파일이 읽는 중 바뀌었습니다") } }
        return ResponseEntity.ok().header("Cache-Control","no-store").body(bytes)
    }
    @PostMapping("/{instanceId}/begin") fun begin(@PathVariable instanceId:String,@RequestBody request:RunnerTransferBegin,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):RunnerTransferPlan {
        check(runnerId,token,instanceId);return transfer.begin(runnerId,instanceId,request.manifest,request.importRequest)
    }
    @PutMapping("/{instanceId}/chunk") fun chunk(@PathVariable instanceId:String,@RequestParam transferId:String,@RequestParam path:String,@RequestParam offset:Long,@RequestBody bytes:ByteArray,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):Map<String,Boolean> {
        check(runnerId,token,instanceId);transfer.upload(runnerId,instanceId,transferId,path,offset,bytes);return mapOf("ok" to true)
    }
    @PostMapping("/{instanceId}/commit") fun commit(@PathVariable instanceId:String,@RequestParam transferId:String,
        @RequestHeader("X-SM-Runner-Id") runnerId:String,@RequestHeader("X-SM-Session",required=false) token:String?):Map<String,Boolean> {
        check(runnerId,token,instanceId);transfer.commit(runnerId,instanceId,transferId);return mapOf("committed" to true)
    }
}
