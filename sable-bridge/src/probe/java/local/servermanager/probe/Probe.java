package local.servermanager.probe;

import java.net.*;
import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.*;

/** Isolated integration fixture only. Never embedded in the manager, installers or main bridge JAR. */
@Mod(value="servermanager_sable_probe",dist=Dist.DEDICATED_SERVER)
public final class Probe {
    public Probe(){NeoForge.EVENT_BUS.addListener(this::started);}
    private void started(ServerStartedEvent event) {
        try {
            var server=event.getServer();
            if("1".equals(System.getenv("SM_SABLE_PROBE_WAIT"))){
                Thread waiter=new Thread(()->{for(int i=0;i<1000 && server.isRunning();i++){
                    var connections=server.getConnection().getConnections();
                    var connection=connections.stream().filter(c->c.getRemoteAddress() instanceof InetSocketAddress).findFirst();
                    if(connection.isPresent()){int port=((InetSocketAddress)connection.get().getRemoteAddress()).getPort();server.execute(()->activate(server,port));return;}
                    try{Thread.sleep(20);}catch(InterruptedException e){return;}
                }},"isolated-sable-probe");waiter.setDaemon(true);waiter.start();return;
            }
            activate(server,12345);
        }catch(Exception error){throw new IllegalStateException("Sable probe failed",error);}
    }
    private void activate(MinecraftServer server,int port) {
        try {
            var player=FakePlayerFactory.getMinecraft(server.overworld());
            player.connection=new ProbeListener(server,new ProbeConnection(port),player,CommonListenerCookie.createInitial(player.getGameProfile(),false));
            Class<?> udp=Class.forName("dev.ryanhcode.sable.network.udp.SableUDPServer");
            Object instance=udp.getMethod("getServer",MinecraftServer.class).invoke(null,server);
            udp.getMethod("beginAuthentication",ServerPlayer.class).invoke(instance,player);
        }catch(Exception error){throw new IllegalStateException("Sable probe failed",error);}
    }
    private static final class ProbeConnection extends Connection {
        private final int port;
        ProbeConnection(int port){super(PacketFlow.SERVERBOUND);this.port=port;}
        @Override public boolean isConnected(){return true;}
        @Override public SocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",port);}
    }
    private static final class ProbeListener extends ServerGamePacketListenerImpl {
        ProbeListener(MinecraftServer server,Connection connection,ServerPlayer player,CommonListenerCookie cookie){super(server,connection,player,cookie);}
        @Override public void send(Packet<?> packet){System.out.println("SM_SABLE_PROBE_ACTIVATED");
            String port=System.getenv("SM_SABLE_PROBE_REPLY_PORT");
            if(port!=null)try{
                Object payload=packet.getClass().getMethod("payload").invoke(packet);Object token=payload.getClass().getMethod("uuid").invoke(payload);
                java.net.http.HttpClient.newHttpClient().sendAsync(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+Integer.parseInt(port)+"/")).POST(java.net.http.HttpRequest.BodyPublishers.ofString(token.toString())).build(),java.net.http.HttpResponse.BodyHandlers.discarding());
            }catch(Exception error){throw new IllegalStateException("Probe callback failed",error);}}
        @Override public boolean isAcceptingMessages(){return true;}
    }
}
