package kinoko.weather;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.util.Properties;

/**
 * Server-side switches for the weather features ported from the v83 reference.
 *
 * <p>Backed by {@code weather.properties} on the classpath (src/main/resources). A missing
 * file or key falls back to {@code true}, so the weather system is on by default. Values
 * are read once at class load; restart the server to change them.
 */
public final class WeatherSettings {
    private static final Logger log = LogManager.getLogger(WeatherSettings.class);

    private static final boolean NIGHT_COMBAT;
    private static final boolean NOCTURNAL_MOBS;
    private static final boolean BLIZZARD_SLOW;

    static {
        final Properties properties = new Properties();
        try (InputStream in = WeatherSettings.class.getResourceAsStream("/weather.properties")) {
            if (in != null) {
                properties.load(in);
            } else {
                log.info("weather.properties not found on classpath, using defaults");
            }
        } catch (Exception e) {
            log.warn("Failed to load weather.properties, using defaults", e);
        }
        NIGHT_COMBAT = getBoolean(properties, "weather.nightCombat.enabled", true);
        NOCTURNAL_MOBS = getBoolean(properties, "weather.nocturnalMobs.enabled", true);
        BLIZZARD_SLOW = getBoolean(properties, "weather.blizzardSlow.enabled", true);
        log.info("Weather settings: nightCombat={}, nocturnalMobs={}, blizzardSlow={}",
                NIGHT_COMBAT, NOCTURNAL_MOBS, BLIZZARD_SLOW);
    }

    private WeatherSettings() {
    }

    /** Night combat scalars (damage / exp / spawn density). */
    public static boolean nightCombat() {
        return NIGHT_COMBAT;
    }

    /** Regional mobs that appear only after nightfall. */
    public static boolean nocturnalMobs() {
        return NOCTURNAL_MOBS;
    }

    /** Blizzard walk-speed slow (visible client buff + movement fold). */
    public static boolean blizzardSlow() {
        return BLIZZARD_SLOW;
    }

    private static boolean getBoolean(Properties properties, String key, boolean defaultValue) {
        final String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }
}
