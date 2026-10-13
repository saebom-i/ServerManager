package local.servermanager

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

data class PlayerSample(val count:Int, val names:List<String>, val sampledAt:String)

/** Vanilla Query is bound to the same loopback address as the game server. A timeout means unknown, never zero. */
class MinecraftQuery {
    fun players(port:Int):PlayerSample? = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout=1200
            val address=InetAddress.getByName("127.0.0.1")
            val session=ThreadLocalRandom.current().nextInt()
            fun request(kind:Int,extra:ByteArray=byteArrayOf()):ByteArray {
                val bytes=ByteBuffer.allocate(7+extra.size).order(ByteOrder.BIG_ENDIAN)
                    .put(0xfe.toByte()).put(0xfd.toByte()).put(kind.toByte()).putInt(session).put(extra).array()
                socket.send(DatagramPacket(bytes,bytes.size,address,port))
                val response=ByteArray(65535)
                val packet=DatagramPacket(response,response.size)
                socket.receive(packet)
                require(packet.length>=5 && response[0].toInt() and 0xff==kind &&
                    ByteBuffer.wrap(response,1,4).order(ByteOrder.BIG_ENDIAN).int==session)
                return response.copyOf(packet.length)
            }
            val challengeResponse=request(9)
            val challenge=challengeResponse.copyOfRange(5,challengeResponse.size)
                .toString(Charsets.US_ASCII).substringBefore('\u0000').trim().toInt()
            val query=request(0,ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(challenge).putInt(0).array())
            parseFullStat(query)
        }
    }.getOrNull()

    companion object {
        fun parseFullStat(bytes:ByteArray):PlayerSample? {
            if(bytes.size<16 || bytes[0].toInt()!=0) return null
            val marker="\u0000\u0000\u0001player_\u0000\u0000".toByteArray(Charsets.US_ASCII)
            val markerAt=bytes.indices.firstOrNull { start -> start+marker.size<=bytes.size && marker.indices.all { bytes[start+it]==marker[it] } } ?: return null
            val metadata=bytes.copyOfRange(16,markerAt).toString(Charsets.UTF_8).split('\u0000')
            val fields=metadata.chunked(2).filter { it.size==2 }.associate { it[0] to it[1] }
            val count=fields["numplayers"]?.toIntOrNull() ?: return null
            if(count !in 0..100000) return null
            val names=bytes.copyOfRange(markerAt+marker.size,bytes.size).toString(Charsets.UTF_8)
                .split('\u0000').filter { it.isNotBlank() }.take(1000)
            return PlayerSample(count,names,Instant.now().toString())
        }
    }
}
