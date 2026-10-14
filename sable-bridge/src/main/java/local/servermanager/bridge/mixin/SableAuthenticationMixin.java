package local.servermanager.bridge.mixin;

import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Registers routing before activation; never parses or rewrites Minecraft's encrypted TCP stream. */
@Mixin(targets="dev.ryanhcode.sable.network.udp.SableUDPServer", remap=false)
public abstract class SableAuthenticationMixin {
    private static final HttpClient SM_HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    @Redirect(method="beginAuthentication", at=@At(value="INVOKE", target="Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V", remap=false), remap=false)
    private void sm$activate(ServerGamePacketListenerImpl listener, Packet<?> packet) {
        String port=System.getenv("SM_SABLE_BRIDGE_PORT"), key=System.getenv("SM_SABLE_BRIDGE_KEY");
        if(port==null || key==null) return;
        try {
            // The supported Sable 2.0.6 activation payload is a record containing exactly one UUID.
            Object payload=packet.getClass().getMethod("payload").invoke(packet);
            UUID token=(UUID)payload.getClass().getMethod("uuid").invoke(payload);
            int clientPort=((InetSocketAddress)listener.getRemoteAddress()).getPort();
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+Integer.parseInt(port)+"/register"))
                .timeout(Duration.ofSeconds(2)).header("Authorization","Bearer "+key)
                .POST(HttpRequest.BodyPublishers.ofString(clientPort+" "+token)).build();
            SM_HTTP.sendAsync(request,HttpResponse.BodyHandlers.discarding()).thenAccept(response->{
                if(response.statusCode()==204) listener.player.server.execute(()->{
                    if(listener.isAcceptingMessages()) listener.send(packet);
                });
            }).exceptionally(error->null);
        } catch(ReflectiveOperationException | RuntimeException ignored) {
            // Fail closed for UDP only. Sable's unauthenticated connection uses its TCP fallback.
        }
    }
}
