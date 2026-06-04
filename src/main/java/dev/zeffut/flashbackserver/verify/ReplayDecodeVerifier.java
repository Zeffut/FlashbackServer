package dev.zeffut.flashbackserver.verify;

import dev.zeffut.flashbackserver.format.ChunkReader;
import dev.zeffut.flashbackserver.format.FlashbackContainer;
import dev.zeffut.flashbackserver.format.ReplayAction;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes every {@code flashback:action/game_packet} and
 * {@code flashback:action/configuration_packet} action in a {@code .flashback} file through
 * Minecraft's real clientbound PLAY and CONFIGURATION codecs respectively, and reports any decode
 * failures or trailing bytes.
 *
 * <p>All NMS access is confined to this class.
 */
public final class ReplayDecodeVerifier {

    private static final String GAME_PACKET   = "flashback:action/game_packet";
    private static final String CONFIG_PACKET = "flashback:action/configuration_packet";
    private static final int MAX_PROBLEMS = 20;

    private ReplayDecodeVerifier() {}

    public record Result(int decoded, int errors, List<String> problems) {
        public boolean ok() {
            return errors == 0 && decoded > 0;
        }
    }

    /**
     * Opens {@code file}, iterates every chunk, and decodes each {@code game_packet} action through
     * the clientbound PLAY codec and each {@code configuration_packet} action through the clientbound
     * CONFIGURATION codec.
     *
     * @param file           path to the {@code .flashback} container
     * @param registryAccess live registry access for the PLAY codec decorator
     * @return a {@link Result} summarising how many packets decoded cleanly vs. with errors
     */
    public static Result verify(Path file, RegistryAccess registryAccess) {
        int decoded = 0;
        int errors = 0;
        List<String> problems = new ArrayList<>();

        // Build both codecs once.
        ProtocolInfo<ClientGamePacketListener> gameInfo =
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        RegistryFriendlyByteBuf.decorator(registryAccess));
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gameCodec = gameInfo.codec();

        // CONFIG codec is already bound over plain FriendlyByteBuf — no decorator needed.
        StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> configCodec =
                ConfigurationProtocols.CLIENTBOUND.codec();

        try (FlashbackContainer.Reader reader = FlashbackContainer.open(file)) {
            var meta = reader.readMetadata();

            for (String chunkName : meta.chunks.keySet()) {
                List<ReplayAction> actions;
                try {
                    ChunkReader.Result chunkResult = ChunkReader.read(reader.readChunk(chunkName));
                    actions = new ArrayList<>(chunkResult.snapshotActions());
                    actions.addAll(chunkResult.streamActions());
                } catch (Exception e) {
                    errors++;
                    if (problems.size() < MAX_PROBLEMS) {
                        problems.add("Failed to read chunk '" + chunkName + "': " + e.getMessage());
                    }
                    continue;
                }

                for (ReplayAction action : actions) {
                    final StreamCodec<ByteBuf, ?> codec;
                    if (GAME_PACKET.equals(action.identifier())) {
                        codec = gameCodec;
                    } else if (CONFIG_PACKET.equals(action.identifier())) {
                        codec = configCodec;
                    } else {
                        // Skip create_local_player, next_tick, and any other synthetic actions.
                        continue;
                    }

                    ByteBuf buf = Unpooled.wrappedBuffer(action.payload());
                    try {
                        codec.decode(buf);
                        if (buf.isReadable()) {
                            errors++;
                            if (problems.size() < MAX_PROBLEMS) {
                                problems.add("Trailing bytes after decode in chunk '" + chunkName
                                        + "' (" + action.identifier() + "): "
                                        + buf.readableBytes() + " byte(s) unconsumed");
                            }
                        } else {
                            decoded++;
                        }
                    } catch (Exception e) {
                        errors++;
                        if (problems.size() < MAX_PROBLEMS) {
                            problems.add("Decode error in chunk '" + chunkName
                                    + "' (" + action.identifier() + "): "
                                    + e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    } finally {
                        buf.release();
                    }
                }
            }
        } catch (Exception e) {
            errors++;
            if (problems.size() < MAX_PROBLEMS) {
                problems.add("Failed to open container '" + file + "': " + e.getMessage());
            }
        }

        return new Result(decoded, errors, List.copyOf(problems));
    }
}
