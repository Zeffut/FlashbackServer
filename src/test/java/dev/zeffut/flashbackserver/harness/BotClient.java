package dev.zeffut.flashbackserver.harness;

import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BotClient implements AutoCloseable {
    private final ClientSession session;
    private final CountDownLatch joined = new CountDownLatch(1);

    private BotClient(ClientSession session) {
        this.session = session;
    }

    public static BotClient connect(String host, int port, String username) throws Exception {
        MinecraftProtocol protocol = new MinecraftProtocol(username);
        ClientSession session = ClientNetworkSessionFactory.factory()
                .setRemoteSocketAddress(new InetSocketAddress(host, port))
                .setProtocol(protocol)
                .create();
        BotClient bot = new BotClient(session);
        session.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Packet packet) {
                if (packet instanceof ClientboundLoginPacket) {
                    bot.joined.countDown();
                }
            }
        });
        session.connect();
        return bot;
    }

    public boolean awaitJoin(long timeoutSeconds) throws InterruptedException {
        return joined.await(timeoutSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        session.disconnect("bye");
    }
}
