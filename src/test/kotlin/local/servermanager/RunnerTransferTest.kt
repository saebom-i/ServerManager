package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class RunnerTransferTest {
    @TempDir lateinit var temp:Path

    private data class Fixture(val transfer:RunnerTransfer,val base:Path,val manager:ServerManager,val registry:RunnerRegistry)
    private fun fixture():Fixture {
        val base=temp.resolve("instance/server").also { it.createDirectories() }
        val manager=mock(ServerManager::class.java)
        val registry=mock(RunnerRegistry::class.java)
        `when`(manager.root).thenReturn(temp)
        `when`(manager.serverDir("instance")).thenReturn(base)
        `when`(manager.instance("instance")).thenReturn(Instance("instance","test",loaderVersion="21.1.252",port=25565,memoryMb=512,javaPath="java",runnerId="runner"))
        `when`(registry.active("runner")).thenReturn(RunnerDevice("runner","Mac","MAC","user","key","now",allowedInstanceIds=setOf("instance")))
        return Fixture(RunnerTransfer(manager,registry),base,manager,registry)
    }

    @Test fun `only changed file chunks are requested and deletion is preserved`() {
        val (transfer,base,manager)=fixture()
        base.resolve("keep.txt").writeText("keep")
        base.resolve("remove.txt").writeText("remove")
        val worker=temp.resolve("worker").also { it.createDirectories() }
        worker.resolve("keep.txt").writeText("keep")
        worker.resolve("new.txt").writeText("new bytes")
        val (files,dirs)=FileInventory.collect(worker)
        val plan=transfer.begin("runner","instance",RunnerFileManifest(files,dirs))
        assertEquals(setOf("new.txt"),plan.changedFiles)
        transfer.upload("runner","instance",plan.transferId,"new.txt",0,"new bytes".toByteArray())
        transfer.commit("runner","instance",plan.transferId)
        assertEquals("keep",base.resolve("keep.txt").readText())
        assertEquals("new bytes",base.resolve("new.txt").readText())
        assertFalse(base.resolve("remove.txt").exists())
        verify(manager).remoteCommitted("instance","runner")
        val progress=mockingDetails(manager).invocations.filter {it.method.name=="fileTransferProgress"}.map {it.arguments[2] as FolderImportProgress}
        assertTrue(progress.any {it.phase=="UPLOADING" && it.files==1 && it.bytes==9L && it.totalFiles==1 && it.totalBytes==9L})
        assertEquals("VERIFYING",progress.last().phase)
    }

    @Test fun `bad hash cannot replace central server data`() {
        val (transfer,base,manager)=fixture()
        base.resolve("world.dat").writeText("original")
        val worker=temp.resolve("worker").also { it.createDirectories() }
        worker.resolve("world.dat").writeText("changed")
        val (files,dirs)=FileInventory.collect(worker)
        val plan=transfer.begin("runner","instance",RunnerFileManifest(files,dirs))
        transfer.upload("runner","instance",plan.transferId,"world.dat",0,"corrupt".toByteArray())
        assertFailsWith<IllegalStateException> { transfer.commit("runner","instance",plan.transferId) }
        assertEquals("original",base.resolve("world.dat").readText())
        verify(manager,never()).remoteCommitted("instance","runner")
    }
    @Test fun `initial import requires its ticket and backs up old files before replacing without a game process`() {
        val (transfer,base,manager)=fixture();val request="fixture-import-request"
        base.resolve("old-world.dat").writeText("original")
        val worker=temp.resolve("existing-source").also {it.createDirectories()};worker.resolve("imported.dat").writeText("existing world")
        val (files,dirs)=FileInventory.collect(worker)
        val plan=transfer.begin("runner","instance",RunnerFileManifest(files,dirs),request)
        verify(manager).checkFolderImport("instance","runner",request)
        transfer.upload("runner","instance",plan.transferId,"imported.dat",0,"existing world".toByteArray())
        transfer.commit("runner","instance",plan.transferId)
        assertEquals("existing world",base.resolve("imported.dat").readText())
        val backup=temp.resolve("instance/runner-backups").listDirectoryEntries().single();assertEquals("original",backup.resolve("files/old-world.dat").readText())
        verify(manager).remoteCommitted("instance","runner",request)
        verify(manager,never()).start(anyString())
    }
    @Test fun `a busy commit does not block a different runner instance upload`() {
        val (transfer,base,manager,registry)=fixture()
        val second=temp.resolve("second/server").also {it.createDirectories()}
        `when`(manager.serverDir("second")).thenReturn(second)
        `when`(manager.instance("second")).thenReturn(Instance("second","second",loaderVersion="21.1.252",port=25565,memoryMb=512,javaPath="java",runnerId="runner"))
        `when`(registry.active("runner")).thenReturn(RunnerDevice("runner","Mac","MAC","user","key","now",allowedInstanceIds=setOf("instance","second")))
        val wanted=temp.resolve("wanted").createDirectory().also {it.resolve("data").writeText("new")}
        val manifest=FileInventory.collect(wanted).let {(f,d)->RunnerFileManifest(f,d)}
        val first=transfer.begin("runner","instance",manifest);val next=transfer.begin("runner","second",manifest)
        transfer.upload("runner","instance",first.transferId,"data",0,"new".toByteArray())
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        doAnswer {call->
            if(call.getArgument<String>(0)=="instance" && call.getArgument<FolderImportProgress>(2).phase=="VERIFYING") {entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS))}
            null
        }.`when`(manager).fileTransferProgress(anyString(),anyString(),any(FolderImportProgress::class.java) ?: FolderImportProgress("VERIFYING"))
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val commit=pool.submit {transfer.commit("runner","instance",first.transferId)}
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            pool.submit {transfer.upload("runner","second",next.transferId,"data",0,"new".toByteArray())}.get(3,java.util.concurrent.TimeUnit.SECONDS)
            release.countDown();commit.get(5,java.util.concurrent.TimeUnit.SECONDS)
            transfer.commit("runner","second",next.transferId)
            assertEquals("new",base.resolve("data").readText());assertEquals("new",second.resolve("data").readText())
        } finally {release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS))}
    }
}
