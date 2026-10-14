package local.servermanager

/** Runtime-only status; raw files are transferred using the existing checked chunk protocol. */
data class FolderImportProgress(val phase:String,val files:Int=0,val bytes:Long=0,val totalFiles:Int=0,val totalBytes:Long=0,val message:String="",val direction:String="TO_MANAGER") {
    fun validated():FolderImportProgress {
        require(phase in setOf("SCANNING","UPLOADING","VERIFYING","COMPLETE","FAILED"))
        require(files in 0..100000 && totalFiles in 0..100000 && bytes in 0..(1024L*1024*1024*1024) && totalBytes in 0..(1024L*1024*1024*1024))
        require(direction in setOf("TO_MANAGER","TO_RUNNER"))
        return copy(message=message.take(500))
    }
}
