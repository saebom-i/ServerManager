package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.data.ByteArray as WebBytes
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Optional
import java.util.UUID
import kotlin.io.path.*

data class Account(val id:String, val displayName:String, val handle:String, val owner:Boolean=false, var enabled:Boolean=true, var epoch:Int=0, var permissions:Map<String,String> = emptyMap())
data class Passkey(val id:String,val userId:String,val publicKey:String,var count:Long,val label:String,val createdAt:String)
data class Invitation(val hash:String,val userId:String,val expiresAt:String,val recovery:Boolean=false)
data class AuthData(val accounts:MutableList<Account> = mutableListOf(),val credentials:MutableList<Passkey> = mutableListOf(),val invitations:MutableList<Invitation> = mutableListOf())

class AuthStore(val directory:Path):CredentialRepository,AutoCloseable {
    private val mapper=jacksonObjectMapper()
    private val random=SecureRandom()
    private val channel:FileChannel
    private val lock:java.nio.channels.FileLock
    private val file=directory.resolve("accounts.json")
    private var data:AuthData
    init {
        directory.createDirectories();Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"))
        channel=FileChannel.open(directory.resolve("store.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
        lock=channel.tryLock() ?: error("인증 저장소를 사용 중입니다. 다른 관리 앱을 먼저 종료하세요")
        data=if(file.exists()) mapper.readValue(file.readText()) else AuthData()
        if(data.accounts.none { it.owner }) {
            val owner=Account(UUID.randomUUID().toString(),"소유자",token(),owner=true)
            data.accounts.add(owner);issueSetup(owner)
        }
    }
    private fun token():String=ByteArray(32).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun persist() {
        val tmp=Files.createTempFile(directory,"auth-",".tmp")
        try { Files.setPosixFilePermissions(tmp,PosixFilePermissions.fromString("rw-------"));tmp.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data));Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) } finally { tmp.deleteIfExists() }
    }
    private fun issueSetup(owner:Account) {
        val value=token()
        data.invitations.removeIf { it.userId==owner.id }
        data.invitations.add(Invitation(hash(value),owner.id,Instant.now().plusSeconds(1800).toString(),true))
        persist()
        val path=directory.resolve("setup-code.txt")
        Files.writeString(path,value+"\n",StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"))
    }
    @Synchronized fun recoverOwner() {
        val owner=data.accounts.single { it.owner };owner.enabled=true;owner.epoch++
        data.credentials.removeIf { it.userId==owner.id };issueSetup(owner)
    }
    @Synchronized fun account(id:String):Account?=data.accounts.find { it.id==id }?.let { it.copy(permissions=it.permissions.toMap()) }
    @Synchronized fun accounts():List<Account> = data.accounts.map { it.copy(permissions=it.permissions.toMap()) }
    @Synchronized fun credentialList(userId:String)=data.credentials.filter { it.userId==userId }.map { mapOf("id" to it.id,"label" to it.label,"createdAt" to it.createdAt) }
    @Synchronized fun hasOwnerCredential()=data.accounts.filter { it.owner }.any { a->data.credentials.any { it.userId==a.id } }
    @Synchronized fun invitation(value:String):Invitation? = data.invitations.find { it.hash==hash(value) && Instant.now().isBefore(Instant.parse(it.expiresAt)) && account(it.userId)?.enabled==true }
    @Synchronized fun invite(name:String,permissions:Map<String,String>):String {
        require(name.isNotBlank() && name.length<=80)
        require(permissions.values.all { it in setOf("ADMIN","VIEWER") }) { "잘못된 권한" }
        val user=Account(UUID.randomUUID().toString(),name,token(),permissions=permissions)
        val value=token();data.accounts.add(user)
        data.invitations.add(Invitation(hash(value),user.id,Instant.now().plusSeconds(86400).toString()))
        persist();return value
    }
    @Synchronized fun register(userId:String,inviteHash:String?,key:Passkey) {
        val user=data.accounts.find { it.id==userId && it.enabled } ?: error("계정이 비활성화되었습니다")
        require(key.userId==user.id && data.credentials.none { it.id==key.id }) { "이미 등록된 패스키입니다" }
        if(inviteHash!=null) {
            val invitation=data.invitations.find { it.hash==inviteHash && it.userId==userId && Instant.now().isBefore(Instant.parse(it.expiresAt)) } ?: error("초대가 만료되었거나 이미 사용되었습니다")
            data.invitations.remove(invitation)
        }
        data.credentials.add(key);persist()
        if(user.owner)directory.resolve("setup-code.txt").deleteIfExists()
    }
    @Synchronized fun updateCounter(id:String,count:Long) { val key=data.credentials.find { it.id==id } ?: error("삭제된 패스키");key.count=count;persist() }
    @Synchronized fun updateUser(id:String,enabled:Boolean,permissions:Map<String,String>) {
        require(permissions.values.all { it in setOf("ADMIN","VIEWER") })
        val account=data.accounts.find { it.id==id } ?: error("없는 계정")
        require(!account.owner) { "소유자 계정은 여기에서 비활성화할 수 없습니다" }
        account.enabled=enabled;account.permissions=permissions;account.epoch++;persist()
    }
    @Synchronized fun revokeSessions(id:String) { data.accounts.find { it.id==id }?.let { it.epoch++;persist() } }
    @Synchronized fun removeInstancePermissions(id:String) {
        data.accounts.filter { id in it.permissions }.forEach { it.permissions=it.permissions-id;it.epoch++ }
        persist()
    }
    @Synchronized fun removeKey(userId:String,id:String) {
        require(data.credentials.count { it.userId==userId }>1) { "마지막 패스키는 삭제할 수 없습니다. 다른 패스키를 먼저 등록하세요" }
        require(data.credentials.removeIf { it.userId==userId && it.id==id }) { "패스키를 찾을 수 없습니다" }
        data.accounts.find { it.id==userId }?.let { it.epoch++ };persist()
    }
    fun allowed(userId:String,id:String,write:Boolean):Boolean {
        val user=account(userId) ?: return false
        return user.enabled && (user.owner || user.permissions[id]=="ADMIN" || (!write && user.permissions[id]=="VIEWER"))
    }
    @Synchronized override fun getCredentialIdsForUsername(username:String):Set<PublicKeyCredentialDescriptor> = data.credentials.filter { it.userId==username }.map { PublicKeyCredentialDescriptor.builder().id(WebBytes.fromBase64Url(it.id)).build() }.toSet()
    @Synchronized override fun getUserHandleForUsername(username:String):Optional<WebBytes> = Optional.ofNullable(account(username)?.takeIf { it.enabled }?.let { WebBytes.fromBase64Url(it.handle) })
    @Synchronized override fun getUsernameForUserHandle(userHandle:WebBytes):Optional<String> = Optional.ofNullable(data.accounts.find { it.handle==userHandle.base64Url && it.enabled }?.id)
    private fun registered(key:Passkey):RegisteredCredential {
        val user=data.accounts.first { it.id==key.userId }
        return RegisteredCredential.builder().credentialId(WebBytes.fromBase64Url(key.id)).userHandle(WebBytes.fromBase64Url(user.handle)).publicKeyCose(WebBytes.fromBase64Url(key.publicKey)).signatureCount(key.count).build()
    }
    @Synchronized override fun lookup(credentialId:WebBytes,userHandle:WebBytes):Optional<RegisteredCredential> = Optional.ofNullable(data.credentials.find { it.id==credentialId.base64Url && data.accounts.any { a->a.id==it.userId && a.handle==userHandle.base64Url && a.enabled } }?.let { registered(it) })
    @Synchronized override fun lookupAll(credentialId:WebBytes):Set<RegisteredCredential> = data.credentials.filter { it.id==credentialId.base64Url && account(it.userId)?.enabled==true }.map { registered(it) }.toSet()
    override fun close() { lock.release();channel.close() }
}
