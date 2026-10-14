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
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlin.io.path.*

data class RunnerFileManifest(val files:Map<String,CloudFile>,val directories:Set<String>)
data class RunnerTransferBegin(val manifest:RunnerFileManifest,val importRequest:String?=null)
data class RunnerTransferPlan(val transferId:String,val changedFiles:Set<String>)
private data class PendingTransfer(val id:String,val runnerId:String,val instanceId:String,val manifest:RunnerFileManifest,val changed:Set<String>,val folder:Path,
    val before:RunnerFileManifest,val importRequest:String?=null,
    val progress:MutableMap<String,Long> = mutableMapOf(),var bytes:Long=0,var files:Int=0,
    val totalBytes:Long=changed.sumOf {manifest.files.getValue(it).size})

@Service
class RunnerTransfer(private val manager:ServerManager,private val registry:RunnerRegistry) {
    private val pending=java.util.concurrent.ConcurrentHashMap<String,PendingTransfer>()
    private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>()
    private fun <T> locked(id:String,action:()->T):T=synchronized(locks.computeIfAbsent(id){Any()}) {action()}
    private fun history(id:String)=manager.profileDir(id).resolve("runner-backups")
    private fun report(transfer:PendingTransfer,phase:String,message:String="") {
        val value=FolderImportProgress(phase,transfer.files,transfer.bytes,transfer.changed.size,transfer.totalBytes,message)
        if(transfer.importRequest!=null)manager.folderImportProgress(transfer.instanceId,transfer.runnerId,transfer.importRequest,value)
        else manager.fileTransferProgress(transfer.instanceId,transfer.runnerId,value)
    }
    fun inventory(id:String):RunnerFileManifest=locked(id) {
        manager.checkTransferReady(id)
        val (files,dirs)=FileInventory.collect(manager.serverDir(id))
        RunnerFileManifest(files,dirs)
    }
    fun begin(runnerId:String,instanceId:String,manifest:RunnerFileManifest,importRequest:String?=null):RunnerTransferPlan=locked(instanceId) {
        require(manager.instance(instanceId).runnerId==runnerId && registry.active(runnerId)?.allowedInstanceIds?.contains(instanceId)==true)
        if(importRequest!=null)manager.checkFolderImport(instanceId,runnerId,importRequest)
        else check(!manager.root.resolve(instanceId).resolve("folder-import.pending").exists()) { "기존 폴더 가져오기 작업이 진행 중입니다" }
        manager.checkTransferReady(instanceId)
        DeltaTransfer.validate(manifest,manager.serverDir(instanceId))
        DeltaTransfer.recover(manager.serverDir(instanceId),history(instanceId))
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
        val transfer=PendingTransfer(id,runnerId,instanceId,manifest,changed,folder,old,importRequest,files=changed.count {manifest.files.getValue(it).size==0L})
        pending[id]=transfer;report(transfer,"UPLOADING")
        RunnerTransferPlan(id,changed)
    }
    fun upload(runnerId:String,instanceId:String,transferId:String,path:String,offset:Long,bytes:ByteArray)=locked(instanceId) {
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
    fun commit(runnerId:String,instanceId:String,transferId:String)=locked(instanceId) {
        val transfer=pending[transferId] ?: error("파일 인계가 만료되었습니다")
        require(transfer.runnerId==runnerId && transfer.instanceId==instanceId)
        fun checkRequest() {transfer.importRequest?.let {manager.checkFolderImport(instanceId,runnerId,it)}}
        checkRequest()
        report(transfer,"VERIFYING","변경 파일을 검증하고 반영하고 있습니다")
        DeltaTransfer.apply(manager.serverDir(instanceId),transfer.folder,transfer.before,transfer.manifest,history(instanceId),::checkRequest)
        pending.remove(transferId)
        transfer.folder.toFile().deleteRecursively()
        manager.remoteCommitted(instanceId,runnerId,transfer.importRequest)
    }
    fun chunk(instanceId:String,path:String,offset:Long):ByteArray=locked(instanceId) {
        val source=manager.path(instanceId,path)
        require(source.isRegularFile() && offset>=0 && offset<=source.fileSize())
        val bytes=ByteArray(minOf(4L*1024*1024,source.fileSize()-offset).toInt())
        FileChannel.open(source).use {channel->channel.position(offset);val buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())check(channel.read(buffer)>=0) {"파일이 읽는 중 바뀌었습니다"}}
        bytes
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
        val bytes=transfer.chunk(instanceId,path,offset)
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
