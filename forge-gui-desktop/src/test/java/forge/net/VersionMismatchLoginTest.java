package forge.net;

import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.event.LobbyUpdateEvent;
import forge.gamemodes.net.event.LoginEvent;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.util.BuildInfo;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A client built from different sources than the host must be turned away at
 * login with a reason, not seated and left to desync mid-game. {@link forge.gamemodes.net.client.FGameClient}
 * always reports the JVM's own version, so this speaks the wire protocol
 * directly to send a version the server cannot match.
 */
public class VersionMismatchLoginTest {

    private FServerManager server;
    private ServerGameLobby lobby;
    private int port;

    @BeforeMethod
    public void startServer() {
        TestUtils.ensureFModelInitialized();
        port = PortAllocator.allocatePort();
        server = FServerManager.getInstance();
        server.startServer(port);
        lobby = new ServerGameLobby();
        server.setLobby(lobby);
    }

    @AfterMethod(alwaysRun = true)
    public void stopServer() {
        if (server != null) {
            server.stopServer();
        }
    }

    @Test(timeOut = 30_000)
    public void testRejectsMismatchedVersionWithReason() throws Exception {
        try (RawPeer peer = new RawPeer(port)) {
            peer.login("Mallory", "0.0.0-not-this-build");

            Assert.assertTrue(peer.closed.await(10, TimeUnit.SECONDS), "Server should close the channel");
            Assert.assertTrue(peer.lobbyUpdates.isEmpty(), "A rejected client must never receive lobby state");

            final MessageEvent refusal = peer.messages.stream()
                    .filter(m -> m.getType() == ChatMessage.MessageType.WARNING)
                    .findFirst().orElse(null);
            Assert.assertNotNull(refusal, "Client should be told why it was refused, got: " + peer.messages);
            Assert.assertTrue(refusal.getMessage().contains("0.0.0-not-this-build"),
                    "Reason should name the client's version: " + refusal.getMessage());
            Assert.assertTrue(refusal.getMessage().contains(BuildInfo.getVersionString()),
                    "Reason should name the host's version: " + refusal.getMessage());
        }
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            Assert.assertNotEquals(lobby.getSlot(i).getType(), LobbySlotType.REMOTE,
                    "Rejected client must not be seated, but slot " + i + " is " + lobby.getSlot(i).getName());
        }
    }

    @Test(timeOut = 30_000)
    public void testAcceptsMatchingVersion() throws Exception {
        try (RawPeer peer = new RawPeer(port)) {
            peer.login("Alice", BuildInfo.getVersionString());

            Assert.assertTrue(peer.seated.await(10, TimeUnit.SECONDS), "Matching client should get lobby state");
            Assert.assertFalse(peer.closed.await(1, TimeUnit.SECONDS), "Matching client must stay connected");
            Assert.assertTrue(peer.messages.stream().noneMatch(m -> m.getType() == ChatMessage.MessageType.WARNING),
                    "No version warning for a matching client: " + peer.messages);
        }
    }

    /** Minimal protocol peer: same codecs as FGameClient, but we choose the LoginEvent. */
    private static final class RawPeer implements AutoCloseable {
        final List<MessageEvent> messages = new CopyOnWriteArrayList<>();
        final List<LobbyUpdateEvent> lobbyUpdates = new CopyOnWriteArrayList<>();
        final CountDownLatch seated = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        private final EventLoopGroup group = new NioEventLoopGroup(1);
        private final Channel channel;

        RawPeer(final int port) throws InterruptedException {
            channel = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new CompatibleObjectEncoder(null),
                                    new CompatibleObjectDecoder(9766 * 1024, ClassResolvers.cacheDisabled(null)),
                                    new ChannelInboundHandlerAdapter() {
                                        @Override
                                        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
                                            if (msg instanceof MessageEvent e) {
                                                messages.add(e);
                                            } else if (msg instanceof LobbyUpdateEvent e) {
                                                lobbyUpdates.add(e);
                                                seated.countDown();
                                            }
                                        }
                                        @Override
                                        public void channelInactive(final ChannelHandlerContext ctx) {
                                            closed.countDown();
                                        }
                                    });
                        }
                    })
                    .connect("127.0.0.1", port).sync().channel();
        }

        void login(final String name, final String version) {
            channel.writeAndFlush(new LoginEvent(name, 0, 0, version, false));
        }

        @Override
        public void close() {
            channel.close();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }
}
