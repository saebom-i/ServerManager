package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class RunnerTransferTest {
    @TempDir lateinit var temp:Path

    private fun fixture():Triple<RunnerTransfer,Path,ServerManager> {
        val base=temp.resolve("instance/server").also { it.createDirectories() }
        val manager=mock(ServerManager::class.java)
        val registry=mock(RunnerRegistry::class.java)
        `when`(manager.root).thenReturn(temp)
        `when`(manager.serverDir("instance")).thenReturn(base)
        `when`(manager.instance("instance")).thenReturn(Instance("instance","test",loaderVersion="21.1.252",port=25565,memoryMb=512,javaPath="java",runnerId="runner"))
        `when`(registry.active("runner")).thenReturn(RunnerDevice("runner","Mac","MAC","user","key","now",allowedInstanceIds=setOf("instance")))
        return Triple(RunnerTransfer(manager,registry),base,manager)
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
}
