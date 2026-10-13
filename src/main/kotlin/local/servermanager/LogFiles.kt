package local.servermanager

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.io.path.*

/** Raw files only. Neither parsed logs nor indexes are written to disk. */
class LogFiles(private val directory: Path) {
    data class Entry(val path:String,val name:String,val size:Long,val modifiedAt:String,val compressed:Boolean)
    data class Line(val seq:Long,val time:String?,val kind:String,val text:String)
    data class Page(val events:List<Line>,val nextOffset:Long?,val revision:String,val bytesRead:Long)
    private fun allowed(name:String)=name=="console.log" || Regex("server/logs/[^/]+\\.log(?:\\.gz)?").matches(name) || Regex("server/crash-reports/[^/]+\\.txt").matches(name)
    private fun resolve(name:String):Path {
        require(allowed(name) && !name.contains('\\')) { "지원하지 않는 로그 파일입니다" }
        val base=directory.toAbsolutePath().normalize();val p=base.resolve(name).normalize()
        require(p.startsWith(base)) { "잘못된 로그 경로입니다" }
        var part:Path?=p
        while(part!=null && part.startsWith(base)){require(!Files.isSymbolicLink(part)) { "로그의 심볼릭 링크는 지원하지 않습니다" };part=part.parent}
        require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)) { "로그 파일이 없습니다" }
        return p
    }
    fun list():List<Entry> {
        val names=mutableListOf("console.log")
        for(sub in listOf("server/logs","server/crash-reports")) {
            val folder=directory.resolve(sub)
            if(Files.isSymbolicLink(directory.resolve("server")) || Files.isSymbolicLink(folder) || !Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))continue
            Files.list(folder).use { paths->paths.forEach { names.add("$sub/${it.fileName}") } }
        }
        return names.filter(::allowed).mapNotNull { name->runCatching { val p=resolve(name);Entry(name,name,p.fileSize(),p.getLastModifiedTime().toInstant().toString(),name.endsWith(".gz")) }.getOrNull() }.sortedByDescending { it.modifiedAt }
    }
    fun page(name:String,offset:Long=0,limit:Int=300,expectedRevision:String?=null):Page {
        require(offset>=0 && limit in 1..500) { "로그 조회 범위를 확인하세요" }
        val p=resolve(name);val attrs=Files.readAttributes(p,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        val revision="${attrs.fileKey()}:${attrs.size()}:${attrs.lastModifiedTime()}"
        check(expectedRevision==null || revision==expectedRevision) { "로그 파일이 변경되었습니다. 다시 불러오세요." }
        val gzip=name.endsWith(".gz")
        require(!gzip || offset<128L*1024*1024) { "압축 로그는 펼친 크기 128 MiB까지 조회할 수 있습니다" }
        require(gzip || offset<=attrs.size()) { "로그 커서가 파일 범위를 벗어났습니다" }
        val channel=Files.newByteChannel(p,setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))
        if(!gzip)channel.position(offset)
        val raw=java.nio.channels.Channels.newInputStream(channel)
        val input=try { if(gzip)GZIPInputStream(raw) else raw }catch(e:Exception){raw.close();throw e}
        input.buffered().use { stream->
            if(gzip){var skipped=0L;while(skipped<offset){val n=stream.skip(offset-skipped);require(n>0) { "로그 커서가 파일 범위를 벗어났습니다" };skipped+=n}}
            val events=mutableListOf<Line>();var position=offset;var eof=false
            while(events.size<limit && position-offset<2*1024*1024) {
                val start=position;val bytes=ByteArrayOutputStream()
                while(true){val b=stream.read();if(b<0){eof=true;break};position++;require(!gzip || position<=128L*1024*1024) { "압축 로그는 펼친 크기 128 MiB까지 조회할 수 있습니다" };if(b==10)break;require(bytes.size()<1024*1024) { "1 MiB를 넘는 로그 행은 원본 파일로 확인하세요" };bytes.write(b)}
                if(position==start)break
                val text=bytes.toString(Charsets.UTF_8).removeSuffix("\r")
                val kind=when { text.contains("ERROR")||text.contains("Exception")||text.startsWith("Caused by:")->"error";text.contains("WARN")->"warn";else->"output" }
                events.add(Line(start+1,null,kind,text))
                if(eof)break
            }
            // Peek only to distinguish an exact page boundary from EOF.
            if(!eof)eof=stream.read()<0
            return Page(events,if(eof)null else position,revision,position-offset)
        }
    }
}
