package kinoko.weather;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The world's sky: what time of day it is, and what is falling out of it.
 *
 * <p><b>The clock is not a counter.</b> {@link #minuteOfDay()} is a pure function of
 * {@code System.currentTimeMillis()}: it survives a reboot without persistence, every
 * channel agrees without synchronisation, and there is no drift to correct. The recurring
 * broadcast task exists only to re-send the state and to re-roll the weather.
 *
 * <p><b>The sky is SERVER-WIDE, not per world.</b> All kinoko channels share one JVM, so
 * this static state is shared too: every player experiences the same weather. The periodic
 * task of every channel calls {@link #rollIfDue()}; the compare-and-set on
 * {@code nextRollAt} guarantees exactly one caller per interval performs the roll and the
 * rest observe its result.
 */
public final class WeatherService {

    private WeatherService() {
    }

    // ---------------------------------------------------------------- the clock

    /** Real milliseconds in one in-game day. Four real hours means an in-game minute is ten real seconds. */
    public static final long DAY_LENGTH_MS = 4L * 60L * 60L * 1000L;

    public static final int MINUTES_PER_DAY = 1440;

    /** Real milliseconds per in-game minute. The client advances its own clock by this. */
    public static int msPerGameMinute() {
        return (int) (DAY_LENGTH_MS / MINUTES_PER_DAY);
    }

    /**
     * In-game minutes past midnight, 0..1439. While a GM time override is held the clock
     * is FROZEN at the overridden minute instead.
     */
    public static int minuteOfDay() {
        final int forced = timeOverrideMinute.get();
        if (forced >= 0 && timeOverrideUntil.get() > System.currentTimeMillis()) {
            return forced;
        }
        return wallClockMinuteOfDay();
    }

    /** The unforced clock, for showing a GM what the time would be without the override. */
    public static int wallClockMinuteOfDay() {
        final long intoDay = Math.floorMod(System.currentTimeMillis(), DAY_LENGTH_MS);
        return (int) ((intoDay * MINUTES_PER_DAY) / DAY_LENGTH_MS);
    }

    // ------------------------------------------------------------ time override
    //
    // Freezing the clock is a second, independent axis from the weather: "rainy night"
    // is a time AND a sky. It is a FREEZE, not an offset: a frozen minute is broadcast
    // with FLAG_FROZEN so the client stops advancing its own copy too, otherwise it would
    // creep forward between packets and get snapped back every broadcast as a stutter.
    // The hold is finite on purpose, so a GM who freezes the world and logs off does not
    // leave it frozen forever.

    private static final AtomicInteger timeOverrideMinute = new AtomicInteger(-1);
    private static final AtomicLong timeOverrideUntil = new AtomicLong(0L);

    /** How long a forced time or sky holds before the world takes itself back. */
    public static final long OVERRIDE_HOLD_MS = 60L * 60L * 1000L;

    public static boolean isTimeOverridden() {
        return timeOverrideMinute.get() >= 0 && timeOverrideUntil.get() > System.currentTimeMillis();
    }

    /** Freeze the clock at {@code minute} for {@link #OVERRIDE_HOLD_MS}. */
    public static void setTime(int minute) {
        final int m = Math.floorMod(minute, MINUTES_PER_DAY);
        synchronized (skyLock) {
            timeOverrideUntil.set(System.currentTimeMillis() + OVERRIDE_HOLD_MS);
            timeOverrideMinute.set(m);
        }
    }

    /** Hand the clock back to wall time. */
    public static void clearTimeOverride() {
        synchronized (skyLock) {
            bareSky = false;
            timeOverrideMinute.set(-1);
            timeOverrideUntil.set(0L);
        }
    }

    // Named times, so a GM can say "night" instead of computing a minute.
    public static final int TIME_DAWN = 6 * 60;    // 06:00, mid-ramp, half lit
    public static final int TIME_DAY = 12 * 60;    // 12:00, fully lit
    public static final int TIME_DUSK = 18 * 60;   // 18:00, mid-ramp, half dark
    public static final int TIME_NIGHT = 0;        // 00:00, fully dark

    /** 05:00 to 07:00 is dawn, 17:00 to 19:00 is dusk. Mirrored in the client. */
    public static final int DAWN_START = 5 * 60;
    public static final int DAWN_END = 7 * 60;
    public static final int DUSK_START = 17 * 60;
    public static final int DUSK_END = 19 * 60;

    /**
     * 0.0 at full day, 1.0 at full night, ramped across dawn and dusk.
     *
     * <p>Server-side twin of the client's {@code Weather::NightLevel()}. The client owns
     * the curve that is actually rendered; this copy exists so server logic can ask
     * "is it night" without guessing. <b>Keep the two in step.</b>
     */
    public static float nightLevel() {
        final int m = minuteOfDay();
        if (m < DAWN_START || m >= DUSK_END) {
            return 1.0f;
        }
        if (m < DAWN_END) {
            return 1.0f - (float) (m - DAWN_START) / (DAWN_END - DAWN_START);
        }
        if (m < DUSK_START) {
            return 0.0f;
        }
        return (float) (m - DUSK_START) / (DUSK_END - DUSK_START);
    }

    public static boolean isNight() {
        return nightLevel() > 0.5f;
    }

    // ---------------------------------------------------------------- the sky

    /** Wire values. Kept as aliases of the first three profile ids for compatibility. */
    public static final byte SKY_CLEAR = 0;
    public static final byte SKY_RAIN = 1;
    public static final byte SKY_SNOW = 2;

    /** Set by the client on map entry so the sky does not visibly fade in on arrival. */
    public static final byte FLAG_SNAP = 0x01;

    /** The clock is frozen by a GM override: the client must NOT advance its own copy. */
    public static final byte FLAG_FROZEN = 0x02;

    /** Testing: hide the map's own sky and show only the moon and the starfields. */
    public static final byte FLAG_BARESKY = 0x04;

    private static volatile boolean bareSky = false;

    public static boolean isBareSky() {
        return bareSky && isTimeOverridden();
    }

    public static void setBareSky(boolean on) {
        bareSky = on;
    }

    // One rolled sky PER REGION. Regions with a forced profile never consult this. A stored
    // roll per region is stable between rolls, which is what the client needs.
    private static final ConcurrentHashMap<WeatherRegion, Byte> skyByRegion = new ConcurrentHashMap<>();

    /** Wall time each region's CURRENT sky began, so the client can seed its ground accumulation. */
    private static final ConcurrentHashMap<WeatherRegion, Long> skySinceByRegion = new ConcurrentHashMap<>();

    /** Process start, the fallback age for a sky that has never been re-rolled. */
    private static final long startedAt = System.currentTimeMillis();

    /** Wall time each region's rainbow expires, from a wet sky clearing. */
    private static final ConcurrentHashMap<WeatherRegion, Long> rainbowUntilByRegion = new ConcurrentHashMap<>();

    /** How long a rainbow lasts once the rain stops. Real time, not game time. */
    public static final long RAINBOW_MS = 180L * 1000L;

    /** Which skies leave a rainbow behind them when they clear. */
    private static boolean isWet(byte s) {
        return s == WeatherProfile.RAIN.id() || s == WeatherProfile.STORM.id();
    }

    /**
     * Record a sky change for {@code region} and start a rainbow if it just stopped raining.
     */
    private static void onSkyChanged(WeatherRegion region, byte previous, byte next, long now) {
        skySinceByRegion.put(region, now);
        if (isWet(previous) && next == WeatherProfile.CLEAR.id()) {
            rainbowUntilByRegion.put(region, now + RAINBOW_MS);
        } else {
            // Any other change ends one early.
            rainbowUntilByRegion.remove(region);
        }
    }

    /** Seconds of rainbow left over {@code mapId}, 0 for none. */
    public static int rainbowSecsLeftForMap(int mapId) {
        final WeatherRegion region = WeatherRegion.forMap(mapId);
        final Long until = rainbowUntilByRegion.get(region);
        if (until == null) {
            return 0;
        }
        final long left = (until - System.currentTimeMillis()) / 1000L;
        if (left <= 0L) {
            rainbowUntilByRegion.remove(region);
            return 0;
        }
        return left > Short.MAX_VALUE ? Short.MAX_VALUE : (int) left;
    }

    /** The GM override, which forces every region at once. Null when not held. */
    private static final AtomicInteger sky = new AtomicInteger(SKY_CLEAR);

    /** When a GM override expires, as wall time. Zero means no override. */
    private static final AtomicLong overrideUntil = new AtomicLong(0L);

    /** Real milliseconds between automatic weather re-rolls. */
    public static final long ROLL_INTERVAL_MS = 15L * 60L * 1000L;

    private static final AtomicLong nextRollAt = new AtomicLong(0L);

    /** The world's baseline sky: the GM override if held, else the DEFAULT region. */
    public static byte currentSky() {
        if (isOverridden()) {
            return (byte) sky.get();
        }
        return skyByRegion.getOrDefault(WeatherRegion.DEFAULT, (byte) sky.get());
    }

    /**
     * The sky over a particular map. A GM override wins everywhere; otherwise a region
     * with a forced profile always reports it, and everything else its own rolled value.
     */
    public static byte skyForMap(int mapId) {
        if (isOverridden()) {
            return (byte) sky.get();
        }
        final WeatherRegion region = WeatherRegion.forMap(mapId);
        final WeatherProfile forced = region.forcedProfile();
        if (forced != null) {
            return forced.id();
        }
        return skyByRegion.getOrDefault(region, WeatherProfile.CLEAR.id());
    }

    /** How long the sky over {@code mapId} has held, in seconds, for ground accumulation. */
    public static int skyElapsedSecForMap(int mapId) {
        final long now = System.currentTimeMillis();
        final long since = skySinceByRegion.getOrDefault(WeatherRegion.forMap(mapId), startedAt);
        final long secs = (now - since) / 1000L;
        if (secs < 0L) {
            return 0;
        }
        return secs > ELAPSED_CAP_SEC ? ELAPSED_CAP_SEC : (int) secs;
    }

    /** Beyond this the client is saturated anyway. */
    public static final int ELAPSED_CAP_SEC = 3600;

    /** The night tint over {@code mapId}, 0xRRGGBB. A GM override does NOT change this. */
    public static int tintForMap(int mapId) {
        return WeatherRegion.forMap(mapId).tint();
    }

    /** The client-owned dusk/night palette over {@code mapId}. The byte has no server colour meaning. */
    public static byte paletteForMap(int mapId) {
        return WeatherPalette.forRegion(WeatherRegion.forMap(mapId)).id();
    }

    /**
     * Exact age of this region's sky, in milliseconds, for synchronized cosmetic events
     * such as lightning. Intentionally uncapped (but int-clamped).
     */
    public static int skyElapsedMillisForMap(int mapId) {
        final long now = System.currentTimeMillis();
        final long since = skySinceForMap(mapId);
        final long elapsed = now - since;
        if (elapsed <= 0L) {
            return 0;
        }
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

    /** A stable, per-sky token. It seeds cosmetic events but is not gameplay state. */
    public static int skyTokenForMap(int mapId) {
        final long since = skySinceForMap(mapId);
        return (int) (since ^ (since >>> 32));
    }

    private static long skySinceForMap(int mapId) {
        return skySinceByRegion.getOrDefault(WeatherRegion.forMap(mapId), startedAt);
    }

    /**
     * Guards the compound read-decide-write on {@code sky} + {@code overrideUntil}.
     * The two mutators both make a DECISION from one field and then write the other, so an
     * interleaving with a timer thread could overwrite a GM's override. These run once per
     * 60 s and once per GM command, so a lock is affordable.
     */
    private static final Object skyLock = new Object();

    /** Force the sky and suspend the automatic roll for {@code holdMs}. */
    public static void setSky(byte value, long holdMs) {
        synchronized (skyLock) {
            final long now = System.currentTimeMillis();
            if (holdMs <= 0L) {
                clearSkyOverrideLocked(now);
                return;
            }

            final byte next = clampSky(value);
            final boolean wasOverridden = overrideUntil.get() > now;
            final byte forcedPrevious = (byte) sky.get();
            overrideUntil.set(now + holdMs);
            sky.set(next);

            for (WeatherRegion region : WeatherRegion.values()) {
                final byte previous = wasOverridden ? forcedPrevious : automaticSkyForRegion(region);
                if (next != previous) {
                    onSkyChanged(region, previous, next, now);
                }
            }
        }
    }

    /** Hand the sky back to each region's automatic (or forced regional) profile. */
    public static void clearSkyOverride() {
        synchronized (skyLock) {
            clearSkyOverrideLocked(System.currentTimeMillis());
        }
    }

    /** Caller holds {@link #skyLock}. Returns whether any region visibly changed. */
    private static boolean clearSkyOverrideLocked(long now) {
        final long heldUntil = overrideUntil.get();
        if (heldUntil == 0L) {
            return false;
        }

        final byte previous = (byte) sky.get();
        boolean changed = false;
        for (WeatherRegion region : WeatherRegion.values()) {
            final byte next = automaticSkyForRegion(region);
            if (next != previous) {
                onSkyChanged(region, previous, next, now);
                changed = true;
            }
        }
        overrideUntil.set(0L);
        sky.set(automaticSkyForRegion(WeatherRegion.DEFAULT));
        return changed;
    }

    private static byte automaticSkyForRegion(WeatherRegion region) {
        final WeatherProfile forced = region.forcedProfile();
        return forced != null ? forced.id()
                : skyByRegion.getOrDefault(region, WeatherProfile.CLEAR.id());
    }

    /**
     * Whether a GM sky override is still in force. Runs the lapse bookkeeping the instant
     * the deadline passes rather than at the next tick.
     */
    public static boolean isOverridden() {
        if (overrideUntil.get() == 0L) {
            return false;
        }
        lapseSkyOverrideIfDue();
        return overrideUntil.get() != 0L;
    }

    /** {@link #isOverridden()}, additionally reporting whether THIS call observed the lapse. */
    private static boolean lapseAndReportChange() {
        if (overrideUntil.get() == 0L) {
            return false;
        }
        return lapseSkyOverrideIfDue();
    }

    private static boolean lapseSkyOverrideIfDue() {
        final long heldUntil = overrideUntil.get();
        if (heldUntil == 0L || heldUntil > System.currentTimeMillis()) {
            return false;
        }
        synchronized (skyLock) {
            // Re-tested under the lock: another thread may have cleared it already.
            final long stillHeld = overrideUntil.get();
            if (stillHeld != 0L && stillHeld <= System.currentTimeMillis()) {
                return clearSkyOverrideLocked(System.currentTimeMillis());
            }
        }
        return false;
    }

    /**
     * Advance the weather if it is due. Returns true when the sky actually changed.
     * Called from EVERY channel's timer thread concurrently, and interleaved with
     * {@link #setSky} from a GM's command thread. The whole decision is taken under
     * {@link #skyLock} and the deadline CAS guarantees exactly one channel rolls.
     */
    public static boolean rollIfDue() {
        synchronized (skyLock) {
            final long now = System.currentTimeMillis();
            boolean changed = lapseAndReportChange();
            if (overrideUntil.get() != 0L) {
                return false;   // still held
            }
            final long due = nextRollAt.get();
            if (now < due || !nextRollAt.compareAndSet(due, now + ROLL_INTERVAL_MS)) {
                return changed;   // not due, or another channel already claimed this interval
            }

            for (WeatherRegion region : WeatherRegion.values()) {
                if (region.forcedProfile() != null) {
                    continue;   // never rolls
                }
                final byte previous = skyByRegion.getOrDefault(region, WeatherProfile.CLEAR.id());
                final byte next = clampSky(pick(region));
                skyByRegion.put(region, next);
                if (next != previous) {
                    changed = true;
                    // Only on a CHANGE. Re-rolling the same sky must not reset a drift.
                    onSkyChanged(region, previous, next, now);
                }
            }
            sky.set(skyByRegion.getOrDefault(WeatherRegion.DEFAULT, WeatherProfile.CLEAR.id()));
            return changed;
        }
    }

    // ------------------------------------------------------------------ seasons
    //
    // The season is derived from the real-world date, for the same reason the day clock is
    // derived from wall time: a pure function, no state, survives a reboot, all channels agree.

    public enum Season { SPRING, SUMMER, AUTUMN, WINTER }

    public static Season currentSeason() {
        switch (java.time.LocalDate.now().getMonth()) {
            case DECEMBER:
            case JANUARY:
            case FEBRUARY:
                return Season.WINTER;
            case MARCH:
            case APRIL:
            case MAY:
                return Season.SPRING;
            case JUNE:
            case JULY:
            case AUGUST:
                return Season.SUMMER;
            default:
                return Season.AUTUMN;
        }
    }

    /**
     * Per-season roll weights, in {@link WeatherProfile} order:
     * clear, rain, snow, overcast, storm, blizzard, leaves, blossom, sandstorm.
     * Rows sum to 100 by convention, but {@link #pick(WeatherRegion)} does not require it.
     */
    private static final int[][] SEASON_WEIGHTS = {
            //         clear rain snow over storm bliz leaf blos sand
            /*SPRING*/ {  48,  22,   0,  12,    6,   0,   0,  12,   6 },
            /*SUMMER*/ {  62,  14,   0,   8,   16,   0,   0,   0,  10 },
            /*AUTUMN*/ {  40,  20,   0,  18,    4,   0,  18,   0,   6 },
            /*WINTER*/ {  36,   4,  30,  14,    2,  14,   0,   0,   3 },
    };

    // SEASON_WEIGHTS is indexed by WeatherProfile ORDINAL and pick() hands the winning
    // column back as a profile id, so ordinal must equal id and every row must be exactly
    // as wide as the enum. Neither is enforced by Java, so fail at class init.
    static {
        final WeatherProfile[] all = WeatherProfile.values();
        for (int i = 0; i < all.length; i++) {
            if (all[i].id() != i) {
                throw new IllegalStateException("WeatherProfile " + all[i] + " has id "
                        + all[i].id() + " but ordinal " + i + "; SEASON_WEIGHTS is indexed by ordinal");
            }
        }
        for (int s = 0; s < SEASON_WEIGHTS.length; s++) {
            if (SEASON_WEIGHTS[s].length != all.length) {
                throw new IllegalStateException("SEASON_WEIGHTS row " + s + " has "
                        + SEASON_WEIGHTS[s].length + " columns, expected " + all.length);
            }
        }
    }

    /** Roll a sky for the current season. Returns a {@link WeatherProfile} id. */
    private static byte pick(WeatherRegion region) {
        final int[] season = SEASON_WEIGHTS[currentSeason().ordinal()];
        final double[] bias = region.weightBias();

        // The region multiplies the season, it does not replace it. SANDSTORM and BLOSSOM
        // are OPT IN: a region with no bias must never get one, and a null bias means
        // "no opinion" rather than "yes to everything".
        final int sandId = WeatherProfile.SANDSTORM.id();
        final int blossomId = WeatherProfile.BLOSSOM.id();

        final int[] w = new int[season.length];
        int total = 0;
        for (int i = 0; i < season.length; i++) {
            double v = season[i];
            if (bias != null) {
                v *= bias[i];
            } else if (i == sandId || i == blossomId) {
                v = 0.0;
            }
            w[i] = (int) Math.round(v * 100.0);   // scaled so a 0.1 bias does not floor to 0
            total += w[i];
        }
        if (total <= 0) {
            return WeatherProfile.CLEAR.id();
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < w.length; i++) {
            roll -= w[i];
            if (roll < 0) {
                return WeatherProfile.values()[i].id();
            }
        }
        return WeatherProfile.CLEAR.id();
    }

    private static byte clampSky(byte value) {
        // byId falls back to CLEAR for anything unrecognised, so the sky is never undefined.
        return WeatherProfile.byId(value).id();
    }

    /** Human-readable, for GM commands and logging. */
    public static String skyName(byte value) {
        return WeatherProfile.byId(value).profileName();
    }

    /** "13:45", for GM commands. */
    public static String clockString() {
        final int m = minuteOfDay();
        return String.format("%02d:%02d", m / 60, m % 60);
    }
}
