package kinoko.weather;

import kinoko.server.node.ChannelServerNode;
import kinoko.server.node.ServerExecutor;
import kinoko.server.packet.OutPacket;
import kinoko.world.user.User;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The periodic weather broadcast + re-roll, registered once per channel.
 *
 * <p>The weather state is static and server-wide, so {@link WeatherService#rollIfDue()}
 * may be called from every channel's timer concurrently: the compare-and-set inside it
 * guarantees exactly one channel per interval performs the roll and the rest observe the
 * result. Each channel then broadcasts the state to its own connected users, bucketed by
 * region so a handful of packets cover potentially hundreds of players.
 */
public final class WeatherTask {

    /** Real seconds between broadcasts to each connected player. */
    private static final long BROADCAST_INTERVAL_SEC = 60L;

    private WeatherTask() {
    }

    /** Register the periodic broadcast + re-roll for one channel. */
    public static void initialize(ChannelServerNode channel) {
        ServerExecutor.scheduleServiceAtFixedRate(() -> {
            try {
                WeatherService.rollIfDue();
                broadcast(channel);
            } catch (Exception e) {
                // A bad tick must not cancel the schedule; the executor wrapper also logs.
            }
        }, 0, BROADCAST_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /** Push the current sky to every user connected to {@code channel} who is in a field. */
    public static void broadcast(ChannelServerNode channel) {
        final Map<WeatherRegion, OutPacket> byRegion = new EnumMap<>(WeatherRegion.class);
        for (User user : channel.getConnectedUsers()) {
            try {
                if (user.getField() == null) {
                    continue;   // in the cash shop / character select: no weather there
                }
                final int mapId = user.getFieldId();
                final WeatherRegion region = WeatherRegion.forMap(mapId);
                OutPacket packet = byRegion.get(region);
                if (packet == null) {
                    packet = WeatherPackets.weatherSync(mapId, false);
                    byRegion.put(region, packet);
                }
                user.write(packet);
            } catch (Exception e) {
                // one unhappy client must not cost the rest of the channel its sky
            }
        }
    }
}
