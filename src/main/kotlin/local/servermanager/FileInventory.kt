package local.servermanager

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*

object FileInventory {
    fun safePath(base:Path,relative:String):Path {
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.contains('\\'))
        val path=base.resolve(relative).normalize()
        require(path.startsWith(base) && path!=base && !relative.split('/').contains("..")) { "잘못된 동기화 경로" }
        return path
    }
    fun digest(path:Path):String {
        val hash=MessageDigest.getInstance("SHA-256")
        path.inputStream().use { stream ->
            val buffer=ByteArray(1024*1024)
            while(true) { val count=stream.read(buffer);if(count<0)break;hash.update(buffer,0,count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    fun collect(base:Path,onProgress:(Int,Long)->Unit={_,_->}):Pair<Map<String,CloudFile>,Set<String>> {
        val files=linkedMapOf<String,CloudFile>()
        val dirs=linkedSetOf<String>()
        var bytes=0L
        Files.walk(base).use { paths -> paths.forEach { path ->
            if(path==base) return@forEach
            require(!Files.isSymbolicLink(path)) { "심볼릭 링크가 있어 동기화를 중단했습니다" }
            val relative=base.relativize(path).toString().replace('\\','/')
            safePath(base,relative)
            if(path.isDirectory()) dirs.add(relative)
            else if(path.isRegularFile()) {val size=path.fileSize();files[relative]=CloudFile(digest(path),size);bytes+=size;onProgress(files.size,bytes)}
            else error("일반 파일이 아닌 항목이 있어 동기화를 중단했습니다")
        } }
        return files to dirs
    }
    fun snapshot(base:Path):Map<String,String> = Files.walk(base).use { paths -> paths.filter {it!=base}.iterator().asSequence().associate { path ->
        require(!Files.isSymbolicLink(path)) { "심볼릭 링크가 있어 동기화를 중단했습니다" }
        val a=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes::class.java)
        base.relativize(path).toString() to "${a.isDirectory}|${a.size()}|${a.lastModifiedTime()}|${a.fileKey()}"
    } }
}
