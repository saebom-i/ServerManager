package local.servermanager

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ResourceSample(val pid: Long, val cpuPercent: Double?, val rssMb: Double?, val uptimeSeconds: Long?, val sampledAt: String, val cpuSource: String = "unavailable")

class ProcessMetrics {
    private data class Cpu(val at: Long, val cpu: Long)
    private val previous = ConcurrentHashMap<Long, Cpu>()
    fun sample(handle: ProcessHandle): ResourceSample {
        val info = handle.info()
        val now = System.nanoTime()
        val cpu = info.totalCpuDuration().orElse(null)?.toNanos()
        val old = if (cpu != null) previous.put(handle.pid(), Cpu(now, cpu)) else null
        val deltaPercent = if (cpu != null && old != null && now > old.at) maxOf(0.0, 100.0 * (cpu-old.cpu)/(now-old.at)) else null
        val ps = runCatching {
            val builder = ProcessBuilder("/bin/ps", "-o", "%cpu=,rss=", "-p", handle.pid().toString())
            builder.environment()["LC_ALL"] = "C"
            val process = builder.start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); null }
            else if (process.exitValue() == 0) parsePs(process.inputStream.bufferedReader().readText()) else null
        }.getOrNull()
        val percent = deltaPercent ?: ps?.first
        val uptime = info.startInstant().orElse(null)?.let { java.time.Duration.between(it, Instant.now()).seconds }
        return ResourceSample(handle.pid(), percent, ps?.second, uptime, Instant.now().toString(), if(deltaPercent!=null) "cpu-time-delta" else if(ps!=null) "ps" else "unavailable")
    }
    fun retain(pids: Set<Long>) { previous.keys.retainAll(pids) }
    companion object {
        fun parsePs(text: String): Pair<Double,Double>? {
            val parts=text.trim().split(Regex("\\s+"))
            if(parts.size!=2)return null
            val cpu=parts[0].toDoubleOrNull() ?: return null
            val rss=parts[1].toDoubleOrNull() ?: return null
            return if(cpu.isFinite() && rss.isFinite() && cpu>=0 && rss>=0) cpu to rss/1024 else null
        }
    }
}
