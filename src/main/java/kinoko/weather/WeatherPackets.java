package kinoko.weather;

import kinoko.server.header.OutHeader;
import kinoko.server.packet.OutPacket;

/**
 * The world's sky state, for the client weather renderer.
 *
 * <p>The packet is a RE-SYNC, not a tick: the client advances its own clock between
 * packets using {@code msPerGameMinute}, so dropping one only costs a little drift.
 *
 * <pre>
 *   short  opcode            OutHeader.WeatherSync (401)
 *   short  minuteOfDay       0..1439, in-game minutes past midnight
 *   int    msPerGameMinute   real ms per in-game minute (how fast the client advances)
 *   byte   sky               WeatherProfile id
 *   byte   flags             bit0 = snap (do not fade; used on map entry)
 *                            bit1 = frozen (GM override: client must not advance its clock)
 *                            bit2 = baresky (hide the map's own sky)
 *   int    skyElapsedSec     how long this sky has held, for ground accumulation
 *   byte   tintR             the region's night colour, as a multiply against the art
 *   byte   tintG
 *   byte   tintB
 *   short  rainbowSecsLeft   seconds of after-the-rain rainbow still owed, 0 for none
 *   byte   palette           client-owned dusk/night palette id
 *   int    skyElapsedMs      exact sky age, for synchronized cosmetic lightning
 *   int    skyToken          seed for that individual sky occurrence (wind/lightning)
 * </pre>
 *
 * <p>Twenty-eight bytes including the opcode. Fixed-size with no strings and no counts,
 * so the client decoder needs no bounds loop.
 */
public final class WeatherPackets {

    private WeatherPackets() {
    }

    /** The state over a given map. {@code snap} suppresses the client-side fade. */
    public static OutPacket weatherSync(int mapId, boolean snap) {
        int flags = 0;
        if (snap) {
            flags |= WeatherService.FLAG_SNAP;
        }
        if (WeatherService.isTimeOverridden()) {
            flags |= WeatherService.FLAG_FROZEN;
        }
        if (WeatherService.isBareSky()) {
            flags |= WeatherService.FLAG_BARESKY;
        }
        final OutPacket p = OutPacket.of(OutHeader.WeatherSync);
        p.encodeShort(WeatherService.minuteOfDay());
        p.encodeInt(WeatherService.msPerGameMinute());
        p.encodeByte(WeatherService.skyForMap(mapId));
        p.encodeByte(flags);
        // Lets a player who walks into a map that has been snowing for ten minutes see
        // ten minutes of drifts, rather than watching them build from bare ground.
        p.encodeInt(WeatherService.skyElapsedSecForMap(mapId));
        // The region's night colour. Sent rather than derived client side so the map to
        // region rule lives in exactly one place.
        final int tint = WeatherService.tintForMap(mapId);
        p.encodeByte((tint >> 16) & 0xFF);
        p.encodeByte((tint >> 8) & 0xFF);
        p.encodeByte(tint & 0xFF);
        // A rainbow, if this region's rain has just stopped. Sent as the time REMAINING
        // so a player arriving part way through joins the one already in progress.
        p.encodeShort(WeatherService.rainbowSecsLeftForMap(mapId));
        // Palette RGB values deliberately do NOT live on the server. This byte is a stable
        // selector for the client's palette table.
        p.encodeByte(WeatherService.paletteForMap(mapId));
        // The existing seconds value is sufficient for ground accumulation. Lightning
        // needs the millisecond remainder too: otherwise players entering one storm from
        // different packets would see the flash almost a second apart.
        p.encodeInt(WeatherService.skyElapsedMillisForMap(mapId));
        p.encodeInt(WeatherService.skyTokenForMap(mapId));
        return p;
    }
}
