package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.AbstractWebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import java.util.concurrent.ConcurrentHashMap
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class RunnerMessage(val type:String,val instanceId:String?=null,val state:String?=null,val version:String?=null,val detail:String?=null,
    val token:String?=null,val streamId:Int?=null,val command:String?=null,val cpuPercent:Double?=null,val rssMb:Double?=null,
    val uptimeSeconds:Long?=null,val playerCount:Int?=null,val players:List<String>?=null,val pid:Long?=null)

@Service
class RunnerGateway(private val registry:RunnerRegistry,private val manager:ServerManager,private val network:GameNetwork?=null):AbstractWebSocketHandler(),RemoteController {
    private val logger=org.slf4j.LoggerFactory.getLogger(RunnerGateway::class.java)
    private val mapper=jacksonObjectMapper()
    private val sessions=ConcurrentHashMap<String,WebSocketSession>()
    private val tokens=ConcurrentHashMap<String,String>()
    private val sockets=ConcurrentHashMap<Int,GameStream>()
    private val listeners=ConcurrentHashMap<String,ServerSocket>()
    private val streams=AtomicInteger(1)
    private val activeRoutes=ConcurrentHashMap<String,String>()
    private val handshakeSlots=java.util.concurrent.Semaphore(64)
    private var sharedListener:ServerSocket?=null
    private val io=Executors.newCachedThreadPool()
    private data class GameStream(val id:Int,val runnerId:String,val instanceId:String,val socket:Socket,val opened:CountDownLatch=CountDownLatch(1))
    @PostConstruct fun attach() { manager.attachRemoteController(this) }
    override fun afterConnectionEstablished(session:WebSocketSession) {
        val id=session.attributes["runnerId"] as? String ?: run { session.close(CloseStatus.POLICY_VIOLATION);return }
        sessions.put(id,session)?.takeIf { it!==session }?.let { previous ->
            sockets.values.filter {it.runnerId==id}.forEach {closeStream(it,false)}
            manager.remoteDisconnected(id)
            previous.close(CloseStatus.NORMAL)
        }
        session.binaryMessageSizeLimit=128*1024
        val token=ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        tokens[id]=token
        registry.seen(id,registry.device(id)?.version ?: "unknown")
        send(id,RunnerMessage("session",token=token))
    }
    override fun handleTextMessage(session:WebSocketSession,message:TextMessage) {
        val id=session.attributes["runnerId"] as? String ?: return
        if(sessions[id]!==session || registry.active(id)==null) { session.close(CloseStatus.POLICY_VIOLATION);return }
        if(message.payload.length>16384) { session.close(CloseStatus.TOO_BIG_TO_PROCESS);return }
        val event=runCatching { mapper.readValue<RunnerMessage>(message.payload) }.getOrNull() ?: return
        when(event.type) {
            "heartbeat" -> registry.seen(id,event.version?.take(80) ?: "unknown")
            "state" -> {
                registry.seen(id,event.version?.take(80) ?: "unknown")
                val instanceId=event.instanceId ?: return
                if(manager.instance(instanceId).runnerId!=id) return
                if(event.state=="START_FAILED") manager.remoteStartFailed(instanceId,id,event.detail)
                else if(event.state=="STOPPED") manager.remoteWorkerReady(instanceId,id)
                else event.state?.let { manager.remoteState(instanceId,id,it,event.detail) }
                manager.remoteMetrics(instanceId,id,event.pid,event.cpuPercent,event.rssMb,event.uptimeSeconds,event.playerCount,event.players)
            }
            "log" -> event.instanceId?.let { manager.remoteLog(it,id,event.state ?: "output",event.detail ?: "") }
            "opened" -> event.streamId?.let { stream -> sockets[stream]?.takeIf { it.runnerId==id }?.opened?.countDown() }
            "closed" -> event.streamId?.let { stream -> sockets[stream]?.takeIf { it.runnerId==id }?.let { closeStream(it,false) } }
        }
    }
    override fun handleBinaryMessage(session:WebSocketSession,message:BinaryMessage) {
        val id=session.attributes["runnerId"] as? String ?: return
        if(sessions[id]!==session)return
        val buffer=message.payload
        if(buffer.remaining()<4 || buffer.remaining()>65540)return
        val streamId=buffer.int
        val stream=sockets[streamId]?.takeIf { it.runnerId==id } ?: return
        val bytes=ByteArray(buffer.remaining());buffer.get(bytes)
        runCatching { stream.socket.getOutputStream().write(bytes);stream.socket.getOutputStream().flush() }.onFailure { closeStream(stream,true) }
    }
    override fun afterConnectionClosed(session:WebSocketSession,status:CloseStatus) {
        val id=session.attributes["runnerId"] as? String ?: return
        if(sessions.remove(id,session)) {
            tokens.remove(id);registry.disconnected(id);manager.remoteDisconnected(id)
            sockets.values.filter { it.runnerId==id }.forEach { closeStream(it,false) }
        }
    }
    fun connected(id:String)=sessions[id]?.isOpen==true && registry.online(id)
    fun authorized(id:String,token:String?):Boolean = token!=null && registry.active(id)!=null && connected(id) &&
        MessageDigest.isEqual((tokens[id] ?: "").toByteArray(),token.toByteArray())
    fun send(id:String,message:RunnerMessage):Boolean {
        val session=sessions[id] ?: return false
        return runCatching { synchronized(session) { if(!session.isOpen)return false;session.sendMessage(TextMessage(mapper.writeValueAsString(message))) };true }.getOrDefault(false)
    }
    private fun sendBinary(id:String,streamId:Int,bytes:ByteArray,count:Int):Boolean {
        val session=sessions[id] ?: return false
        return runCatching {
            val frame=ByteBuffer.allocate(4+count).putInt(streamId).put(bytes,0,count).flip()
            synchronized(session) { if(!session.isOpen)return false;session.sendMessage(BinaryMessage(frame)) }
            true
        }.getOrDefault(false)
    }
    private fun relayPlayer(socket:Socket,instanceId:String,runnerId:String,initial:ByteArray=byteArrayOf()) {
        if(sockets.size>=500 || sockets.values.count { it.runnerId==runnerId }>=100 || !connected(runnerId)) {socket.close();return}
        val stream=GameStream(streams.incrementAndGet(),runnerId,instanceId,socket)
        sockets[stream.id]=stream
        if(!send(runnerId,RunnerMessage("open",instanceId=instanceId,streamId=stream.id))) {closeStream(stream,false);return}
        io.submit {
            try {
                if(!stream.opened.await(5,TimeUnit.SECONDS)){logger.debug("Game stream open timed out for instance {}",instanceId);return@submit}
                socket.soTimeout=60000
                if(initial.isNotEmpty() && !sendBinary(runnerId,stream.id,initial,initial.size))return@submit
                val input=socket.getInputStream();val buffer=ByteArray(64*1024)
                while(true) {val count=input.read(buffer);if(count<0)break;if(!sendBinary(runnerId,stream.id,buffer,count))break}
            } catch (e:Exception) {logger.debug("Game stream ended: {}",e.javaClass.simpleName)} finally {closeStream(stream,true)}
        }
    }
    @Synchronized private fun openSharedRelay(instance:Instance,runnerId:String) {
        val service=network ?: error("공유 중계를 설정하세요")
        val config=service.config()
        check(config.enabled)
        check(activeRoutes.none { (id,runner)->runner==runnerId && id!=instance.id && manager.instance(id).port==instance.port }) { "이 실행기의 내부 게임 포트를 다른 서버가 사용 중입니다" }
        if(sharedListener?.isClosed!=false) {
            val listener=ServerSocket();listener.reuseAddress=false
            try {listener.bind(InetSocketAddress("0.0.0.0",config.port),50)}
            catch(e:Exception){listener.close();throw IllegalStateException("공유 게임 포트 ${config.port}를 열 수 없습니다")}
            sharedListener=listener
            io.submit {
                while(!listener.isClosed) {
                    val player=runCatching {listener.accept()}.getOrNull() ?: break
                    if(!handshakeSlots.tryAcquire()){player.close();continue}
                    io.submit {
                        var handed=false
                        try {
                            player.soTimeout=5000
                            val handshake=MinecraftHandshake.read(player.getInputStream())
                            val id=service.resolve(handshake.host) ?: run {logger.debug("Unknown game handshake host");return@submit}
                            val runner=activeRoutes[id] ?: return@submit
                            if(!connected(runner))return@submit
                            relayPlayer(player,id,runner,handshake.bytes);handed=true
                        } catch (e:Exception) {logger.debug("Game handshake rejected: {}",e.javaClass.simpleName)} finally {handshakeSlots.release();if(!handed)runCatching {player.close()}}
                    }
                }
            }
        }
        activeRoutes[instance.id]=runnerId
    }
    private fun openRelay(instance:Instance,runnerId:String) {
        if(network?.config()?.enabled==true){openSharedRelay(instance,runnerId);return}
        if(listeners.containsKey(instance.id))return
        val listener=ServerSocket();listener.reuseAddress=false
        try {listener.bind(InetSocketAddress("0.0.0.0",instance.port),50)}
        catch(e:Exception){listener.close();throw IllegalStateException("게임 중계 포트 ${instance.port}를 열 수 없습니다")}
        listeners[instance.id]=listener
        io.submit {while(!listener.isClosed){val socket=runCatching {listener.accept()}.getOrNull() ?: break;relayPlayer(socket,instance.id,runnerId)}}
    }
    override fun sharedGamePort():Int?=network?.config()?.takeIf {it.enabled}?.port
    private fun closeStream(stream:GameStream,notify:Boolean) {
        if(sockets.remove(stream.id,stream)) {
            runCatching { stream.socket.close() }
            if(notify)send(stream.runnerId,RunnerMessage("close",instanceId=stream.instanceId,streamId=stream.id))
        }
    }
    private fun requireRunner(instance:Instance):String {
        val id=instance.runnerId ?: error("실행기를 선택하세요")
        check(registry.active(id)?.allowedInstanceIds?.contains(instance.id)==true) { "기기 등록자의 서버 사용 허용이 필요합니다" }
        check(connected(id)) { "실행기가 오프라인입니다" }
        return id
    }
    override fun start(instance:Instance) {
        val id=requireRunner(instance)
        if(instance.loader!="NeoForge" || instance.minecraft!="1.21.1") {
            val version=registry.active(id)?.version ?: "unknown"
            check(runCatching { SignedUpdates.compare(version,"0.4.0-beta.5")>=0 }.getOrDefault(false)) { "선택한 실행기를 beta.5 이상으로 업데이트한 뒤 이 런처를 시작하세요" }
        }
        openRelay(instance,id)
        if(!send(id,RunnerMessage("start",instanceId=instance.id,detail=mapper.writeValueAsString(instance)))) {
            completed(instance);error("실행기에 시작 명령을 전달하지 못했습니다")
        }
    }
    override fun stop(instance:Instance) { check(send(requireRunner(instance),RunnerMessage("stop",instanceId=instance.id))) { "실행기에 종료 명령을 전달하지 못했습니다" } }
    override fun restart(instance:Instance) { stop(instance) }
    override fun command(instance:Instance,value:String) { check(send(requireRunner(instance),RunnerMessage("command",instanceId=instance.id,command=value))) { "실행기에 콘솔 명령을 전달하지 못했습니다" } }
    @Synchronized override fun completed(instance:Instance) {
        activeRoutes.remove(instance.id)
        if(activeRoutes.isEmpty()){sharedListener?.close();sharedListener=null}
        listeners.remove(instance.id)?.close()
        sockets.values.filter { it.instanceId==instance.id }.forEach { closeStream(it,true) }
    }
}

@Configuration
@EnableWebSocket
class RunnerSocketConfig(private val gateway:RunnerGateway,private val devices:RunnerRegistry):WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry:WebSocketHandlerRegistry) {
        registry.addHandler(gateway,"/api/runners/connect").addInterceptors(object:HandshakeInterceptor {
            override fun beforeHandshake(request:ServerHttpRequest,response:ServerHttpResponse,wsHandler:org.springframework.web.socket.WebSocketHandler,attributes:MutableMap<String,Any>):Boolean {
                val id=request.headers.getFirst("X-SM-Runner-Id")
                val timestamp=request.headers.getFirst("X-SM-Timestamp")?.toLongOrNull()
                val nonce=request.headers.getFirst("X-SM-Nonce")
                val signature=request.headers.getFirst("X-SM-Signature")
                if(id==null || timestamp==null || nonce==null || signature==null || !devices.acceptConnection(id,timestamp,nonce,signature)) {
                    response.setStatusCode(HttpStatus.FORBIDDEN);return false
                }
                attributes["runnerId"]=id
                return true
            }
            override fun afterHandshake(request:ServerHttpRequest,response:ServerHttpResponse,wsHandler:org.springframework.web.socket.WebSocketHandler,exception:Exception?) {}
        })
    }
}
