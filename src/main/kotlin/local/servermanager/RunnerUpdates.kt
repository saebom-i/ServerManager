package local.servermanager

/** Runtime telemetry only. Never carries a token, URL, file path or exception body. */
data class RunnerUpdateStatus(val phase:String="IDLE",val latest:String?=null,val downloadedBytes:Long=0,val totalBytes:Long=0) {
    fun validated():RunnerUpdateStatus {
        require(phase in setOf("IDLE","REQUESTED","CHECKING","DOWNLOADING","READY","WAITING","PAUSING","RESTARTING","CURRENT","FAILED","UNSUPPORTED"))
        latest?.let {SignedUpdates.compare(it,"0.0.0")}
        require(downloadedBytes in 0..150L*1024*1024 && totalBytes in 0..150L*1024*1024 && downloadedBytes<=totalBytes)
        return this
    }
}
data class RunnerUpdateRequest(val pauseTransfer:Boolean=false)
object RunnerUpdateSupport {
    fun supported(version:String)=runCatching {SignedUpdates.compare(version,"0.4.4")>=0}.getOrDefault(false)
}
