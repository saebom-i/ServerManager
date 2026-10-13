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
    fun collect(base:Path):Pair<Map<String,CloudFile>,Set<String>> {
        val files=linkedMapOf<String,CloudFile>()
        val dirs=linkedSetOf<String>()
        Files.walk(base).use { paths -> paths.forEach { path ->
            if(path==base) return@forEach
            require(!Files.isSymbolicLink(path)) { "심볼릭 링크가 있어 동기화를 중단했습니다" }
            val relative=base.relativize(path).toString().replace('\\','/')
            safePath(base,relative)
            if(path.isDirectory()) dirs.add(relative)
            else if(path.isRegularFile()) files[relative]=CloudFile(digest(path),path.fileSize())
            else error("일반 파일이 아닌 항목이 있어 동기화를 중단했습니다")
        } }
        return files to dirs
    }
}
