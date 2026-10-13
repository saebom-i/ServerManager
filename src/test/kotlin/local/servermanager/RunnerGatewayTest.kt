package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Collections
import kotlin.test.*

class RunnerGatewayTest {
    @Test fun `public game socket relays bytes to and from authorized runner`() {
        val port=ServerSocket(0).use { it.localPort }
        val manager=mock(ServerManager::class.java)
        val registry=mock(RunnerRegistry::class.java)
        val runner=RunnerDevice("runner","test","MAC","user","key","now",allowedInstanceIds=setOf("instance"))
        `when`(registry.active("runner")).thenReturn(runner)
        `when`(registry.online("runner")).thenReturn(true)
        val session=mock(WebSocketSession::class.java)
        `when`(session.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to "runner"))
        `when`(session.isOpen).thenReturn(true)
        val sent=Collections.synchronizedList(mutableListOf<WebSocketMessage<*>>())
        doAnswer { sent.add(it.arguments[0] as WebSocketMessage<*>);null }.`when`(session).sendMessage(any())
        val gateway=RunnerGateway(registry,manager)
        gateway.afterConnectionEstablished(session)
        val instance=Instance("instance","test",loaderVersion="21.1.252",port=port,memoryMb=512,javaPath="java",runnerId="runner")
        gateway.start(instance)
        try {
            Socket("127.0.0.1",port).use { player ->
                player.soTimeout=5000
                val mapper=jacksonObjectMapper()
                val deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos()
                var open:RunnerMessage?=null
                while(System.nanoTime()<deadline && open==null) {
                    open=sent.filterIsInstance<TextMessage>().map { mapper.readValue<RunnerMessage>(it.payload) }.firstOrNull { it.type=="open" }
                    if(open==null)Thread.sleep(10)
                }
                val stream=open?.streamId ?: error("게임 스트림이 열리지 않았습니다")
                gateway.handleMessage(session,TextMessage(mapper.writeValueAsString(RunnerMessage("opened",streamId=stream))))
                player.getOutputStream().write("ping".toByteArray())
                var upstream:BinaryMessage?=null
                while(System.nanoTime()<deadline && upstream==null) {
                    upstream=sent.filterIsInstance<BinaryMessage>().firstOrNull { it.payload.asReadOnlyBuffer().int==stream }
                    if(upstream==null)Thread.sleep(10)
                }
                val bytes=upstream?.payload?.asReadOnlyBuffer() ?: error("게임 업스트림 데이터가 없습니다")
                assertEquals(stream,bytes.int)
                assertEquals("ping",ByteArray(bytes.remaining()).also { bytes.get(it) }.toString(Charsets.UTF_8))
                gateway.handleMessage(session,BinaryMessage(ByteBuffer.allocate(8).putInt(stream).put("pong".toByteArray()).flip()))
                assertEquals("pong",ByteArray(4).also { player.getInputStream().readNBytes(it,0,4) }.toString(Charsets.UTF_8))
            }
        } finally { gateway.completed(instance) }
    }
}
