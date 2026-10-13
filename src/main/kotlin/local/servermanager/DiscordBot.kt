package local.servermanager

import jakarta.annotation.PreDestroy
import jakarta.servlet.http.HttpServletRequest
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.StatusChangeEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.read

/** Bounded aggregation: no raw log lines, network work or unbounded queue on the process reader. */
class DiscordAlerts {
    private data class Pending(var count:Int, var detail:String)
    private val pending=linkedMapOf<Pair<String,String>,Pending>()
    private val last=mutableMapOf<Pair<String,String>,Long>()
    @Synchronized fun offer(signal:ServerSignal) {
        val key=signal.instanceId to signal.kind
        if(key !in pending && pending.size>=1000) return
        pending.getOrPut(key) { Pending(0,signal.detail) }.apply { count=(count+1).coerceAtMost(1000000); detail=signal.detail }
    }
    @Synchronized fun drain(now:Long):List<Pair<ServerSignal,Int>> {
        last.entries.removeIf { now-it.value>120000 }
        val result=mutableListOf<Pair<ServerSignal,Int>>()
        val iterator=pending.iterator()
        while(iterator.hasNext()) {
            val (key,p)=iterator.next()
            if(key.second=="error" && now-(last[key] ?: Long.MIN_VALUE/2)<60000) continue
            result.add(ServerSignal(key.first,key.second,p.detail) to p.count);last[key]=now;iterator.remove()
        }
        return result
    }
    @Synchronized fun clear() { pending.clear(); last.clear() }
}

data class DiscordBotStatus(val state:String, val message:String, val inviteUrl:String?, val guilds:List<Map<String,String>>)

@Service
class DiscordBot(private val manager:ServerManager, private val audit:AuditLog, private val desktop:DesktopLifecycle):ListenerAdapter() {
    private val settings=DiscordSettingsStore(manager.root.resolve("security/discord"))
    private val lifecycle=Executors.newSingleThreadExecutor()
    private val generation=AtomicLong()
    private val alerts=DiscordAlerts()
    private val signalSubscription=manager.onSignal { signal -> if(settings.view().config.enabled) alerts.offer(signal) }
    @Volatile private var client:JDA?=null
    @Volatile private var message="봇 토큰을 입력하고 연동을 켜세요."
    @Volatile private var connecting=false
    private val requests=mutableMapOf<String,Long>()
    private val lastStart=mutableMapOf<String,Long>()
    fun view()=settings.view()
    fun status():DiscordBotStatus {
        val jda=client
        val state=when { !view().config.enabled->"OFF"; jda!=null->jda.status.name; connecting->"CONNECTING"; else->"ERROR" }
        val ready=jda?.status==JDA.Status.CONNECTED
        val invite=if(ready) "https://discord.com/oauth2/authorize?client_id=${jda!!.selfUser.id}&scope=bot%20applications.commands&permissions=3072" else null
        return DiscordBotStatus(state,message,invite,if(ready) jda!!.guilds.map { mapOf("id" to it.id,"name" to it.name) } else emptyList())
    }
    @Synchronized fun save(request:DiscordSave):DiscordSettingsView {
        val before=view()
        val next=settings.save(request) { runCatching { manager.instance(it) }.isSuccess }
        val reconnect=before.config.enabled!=next.config.enabled || !request.token.isNullOrBlank() || request.clearToken || client==null
        alerts.clear()
        if(reconnect) connect() else client?.let { registerCommands(it) }
        return next
    }
    @EventListener(ApplicationReadyEvent::class) fun ready() { connect() }
    @Synchronized fun connect() {
        val epoch=generation.incrementAndGet()
        // Invalidate old callbacks immediately. Network shutdown/login stays off the request thread.
        val old=client;client=null
        connecting=view().config.enabled
        message=if(connecting) "Discord에 연결 중입니다." else "Discord 연동이 꺼져 있습니다."
        lifecycle.submit {
            old?.shutdownNow()
            if(epoch!=generation.get() || !view().config.enabled) return@submit
            try {
                val token=settings.token() ?: error("missing token")
                val next=JDABuilder.createLight(token,emptyList()).setEnableShutdownHook(false).addEventListeners(this).build()
                synchronized(this) { if(epoch==generation.get()) client=next else next.shutdownNow() }
            } catch (_:Exception) {
                if(epoch==generation.get()) { connecting=false;message="연결하지 못했습니다. 봇 토큰과 인터넷 연결을 확인한 뒤 다시 연결하세요." }
            }
        }
    }
    override fun onReady(event:ReadyEvent) {
        if(event.jda!==client) return
        connecting=false;message="연결되었습니다. 역할과 알림 채널을 설정하세요."
        registerCommands(event.jda)
    }
    override fun onStatusChange(event:StatusChangeEvent) {
        if(event.jda!==client) return
        if(event.newStatus==JDA.Status.SHUTDOWN) { connecting=false;message="Discord 연결이 종료되었습니다. 토큰과 네트워크를 확인한 뒤 다시 연결하세요." }
        else if(event.newStatus==JDA.Status.CONNECTED) { connecting=false;message="Discord에 연결되었습니다." }
    }
    override fun onGuildJoin(event:GuildJoinEvent) { if(event.jda===client) registerCommands(event.jda) }
    private fun registerCommands(jda:JDA) {
        val config=view().config
        if(!config.enabled) return
        val guildIds=(config.grants.map { it.guildId }+config.alerts.map { it.guildId }).toSet()
        guildIds.forEach { id ->
            val guild=jda.getGuildById(id)
            if(guild==null) { message="설정한 Discord 서버에 봇을 초대하세요. 서버 ID도 확인하세요."; return@forEach }
            val command=Commands.slash("mc","Minecraft 서버 관리")
                .addSubcommands(
                    SubcommandData("status","내 역할에 허용된 서버 상태 보기").addOptions(OptionData(OptionType.STRING,"server","Minecraft 서버",true).setAutoComplete(true)),
                    SubcommandData("start","내 역할에 허용된 서버 시작").addOptions(OptionData(OptionType.STRING,"server","Minecraft 서버",true).setAutoComplete(true)))
            guild.upsertCommand(command).queue({}, { if(jda===client) message="명령 등록 실패: 봇 초대 권한과 서버 ID를 확인하세요." })
        }
    }
    fun choices(guild:String?,roles:Set<String>,start:Boolean,query:String):List<Pair<String,String>> {
        val config=view().config
        return manager.list().map { it["config"] as Instance }
            .filter { DiscordAccess.allowed(config,guild,roles,it.id,start) && (it.name.contains(query,true) || it.id.startsWith(query)) }
            .take(25).map { "${it.name} · ${it.id.take(8)}".take(100) to it.id }
    }
    override fun onCommandAutoCompleteInteraction(event:CommandAutoCompleteInteractionEvent) {
        if(event.jda!==client || event.name!="mc") return
        val choices=choices(event.guild?.id,event.member?.roles?.map { it.id }?.toSet() ?: emptySet(),event.subcommandName=="start",event.focusedOption.value)
        event.replyChoices(choices.map { Command.Choice(it.first,it.second) }).queue({}, {})
    }
    override fun onSlashCommandInteraction(event:SlashCommandInteractionEvent) {
        if(event.jda!==client || event.name!="mc") return
        val guild=event.guild
        val id=event.getOption("server")?.asString
        val action=event.subcommandName
        if(guild==null || id==null || action !in setOf("start","status")) {
            event.reply("설정된 Discord 서버에서 /mc start 또는 /mc status를 사용하세요.").setEphemeral(true).queue({}, {});return
        }
        // Acknowledge before REST membership verification. Roles are fetched fresh for each action.
        event.deferReply(true).queue({ hook ->
            guild.retrieveMemberById(event.user.id).useCache(false).queue({ member ->
                if(event.jda!==client) { hook.editOriginal("설정이 변경되었습니다. 다시 요청하세요.").queue({}, {});return@queue }
                val result=execute(guild.id,member.roles.map { it.id }.toSet(),event.user.id,event.id,id,action!!)
                hook.editOriginal(result).setAllowedMentions(emptyList()).queue({}, {})
            }, { hook.editOriginal("현재 서버 역할을 확인하지 못했습니다. 잠시 후 다시 시도하세요.").queue({}, {}) })
        }, {})
    }
    fun execute(guild:String,roles:Set<String>,user:String,requestId:String,id:String,action:String):String = desktop.gate.read {
        if(desktop.stopping) "관리 앱 업데이트 중입니다. 잠시 후 다시 시도하세요."
        else executeAllowed(guild,roles,user,requestId,id,action)
    }
    @Synchronized private fun executeAllowed(guild:String,roles:Set<String>,user:String,requestId:String,id:String,action:String):String {
        val config=view().config
        if(action !in setOf("start","status") || !DiscordAccess.allowed(config,guild,roles,id,action=="start")) {
            audit.append("discord:$guild:$user","discord $action",403,"discord");return "이 서버에 대한 ${if(action=="start") "시작" else "조회"} 권한이 없습니다. 관리자에게 역할 설정을 요청하세요."
        }
        val now=System.currentTimeMillis()
        requests.entries.removeIf { now-it.value>300000 }; lastStart.entries.removeIf { now-it.value>10000 }
        if(requests.putIfAbsent(requestId,now)!=null) return "이미 처리한 명령입니다. /mc status로 상태를 확인하세요."
        if(requests.size>10000) { requests.remove(requestId);return "요청이 많습니다. 잠시 후 다시 시도하세요." }
        return try {
            val i=manager.instance(id);val state=manager.view(id)["state"]
            if(action=="status") "${i.name}: ${stateLabel(state.toString())}"
            else if(state in setOf("RUNNING","STARTING")) "${i.name}: 이미 실행 중이거나 시작 중입니다."
            else {
                val key="$guild:$user"
                if(now-(lastStart[key] ?: 0)<10000) return "시작 요청은 10초 후 다시 보낼 수 있습니다."
                lastStart[key]=now
                manager.start(id)
                audit.append("discord:$guild:$user","discord start $id",200,"discord")
                "${i.name}: 시작을 요청했습니다. /mc status로 준비 상태를 확인하세요."
            }
        } catch (_:Exception) {
            audit.append("discord:$guild:$user","discord $action $id",409,"discord")
            "요청을 완료하지 못했습니다. 관리 웹에서 서버 설치·메모리·실행 상태를 확인하세요."
        }
    }
    @Scheduled(fixedDelay=2000) fun sendAlerts() {
        val jda=client
        if(jda==null || jda.status!=JDA.Status.CONNECTED || !view().config.enabled) { alerts.clear();return }
        alerts.drain(System.currentTimeMillis()).forEach { (signal,count) ->
            val name=runCatching { manager.instance(signal.instanceId).name }.getOrNull() ?: return@forEach
            view().config.alerts.filter { it.instanceId==signal.instanceId && (if(signal.kind=="error") it.errors else it.stops) }
                .forEach { route ->
                    // Channel lookup is scoped to the configured guild, never a globally supplied channel ID.
                    val channel=jda.getGuildById(route.guildId)?.getTextChannelById(route.channelId)
                    if(channel==null) { message="알림 채널을 찾을 수 없습니다. 서버·텍스트 채널 ID와 봇 권한을 확인하세요.";return@forEach }
                    val text="${if(signal.kind=="error") "⚠️ 오류 감지" else "⏹ 서버 종료"} · $name\n${signal.detail}${if(count>1) "\n동일 종류 알림 ${count}건을 묶었습니다." else ""}"
                    channel.sendMessage(text.take(1900)).setAllowedMentions(emptyList()).queue({}, { if(jda===client) message="알림 전송 실패: 채널 보기·메시지 보내기 권한을 확인하세요." })
                }
        }
    }
    @PreDestroy fun close() { generation.incrementAndGet();signalSubscription.close();alerts.clear();client?.shutdownNow();client=null;lifecycle.shutdownNow() }
    private fun stateLabel(value:String)=mapOf("NEW" to "설치 필요","STOPPED" to "정지됨","STARTING" to "시작 중","RUNNING" to "실행 중","STOPPING" to "종료 중","FAILED" to "오류","INSTALLING" to "설치 중","IMPORTING" to "가져오는 중","IMPORT_FAILED" to "가져오기 실패")[value] ?: value
}

@RestController
@RequestMapping("/api/admin/discord")
class DiscordApi(private val bot:DiscordBot) {
    @GetMapping fun settings()=ResponseEntity.ok().header("Cache-Control","no-store").body(bot.view())
    @GetMapping("/status") fun status()=ResponseEntity.ok().header("Cache-Control","no-store").body(bot.status())
    private fun fresh(req:HttpServletRequest) {
        val verified=req.getSession(false)?.getAttribute("sm.verified") as? Long ?: 0
        if(System.currentTimeMillis()-verified>300000) throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"패스키로 다시 인증한 뒤 진행하세요")
    }
    @PutMapping fun save(@RequestBody request:DiscordSave,req:HttpServletRequest):DiscordSettingsView { fresh(req);return bot.save(request) }
    @PostMapping("/reconnect") fun reconnect(req:HttpServletRequest):Map<String,Boolean> { fresh(req);bot.connect();return mapOf("requested" to true) }
}
