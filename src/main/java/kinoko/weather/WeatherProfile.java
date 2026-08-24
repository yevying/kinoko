package kinoko.weather;

/**
 * A named sky. Only the ID and the name live on the server; the render parameters
 * (tint, cloud/rain alpha, particle effect, fade rate, sound) are the client's, in the
 * client-side profile table. What the two ends must agree on is this numbering.
 *
 * <p>0, 1 and 2 are deliberately the old {@code SKY_CLEAR}/{@code SKY_RAIN}/{@code SKY_SNOW}
 * wire values, so the id doubles as the profile index and the SEASON_WEIGHTS column index.
 * Appended only: inserting a profile anywhere but the end would renumber every sky above it.
 */
public enum WeatherProfile {
    CLEAR(0, "clear"),
    RAIN(1, "rain"),
    SNOW(2, "snow"),
    OVERCAST(3, "overcast"),
    STORM(4, "storm"),
    BLIZZARD(5, "blizzard"),
    LEAVES(6, "leaves"),
    BLOSSOM(7, "blossom"),
    SANDSTORM(8, "sandstorm");

    private final byte id;
    private final String name;

    WeatherProfile(int id, String name) {
        this.id = (byte) id;
        this.name = name;
    }

    public byte id() {
        return id;
    }

    public String profileName() {
        return name;
    }

    private static final WeatherProfile[] BY_ID = buildById();

    private static WeatherProfile[] buildById() {
        int max = 0;
        for (WeatherProfile p : values()) {
            max = Math.max(max, p.id);
        }
        final WeatherProfile[] out = new WeatherProfile[max + 1];
        for (WeatherProfile p : values()) {
            out[p.id] = p;
        }
        return out;
    }

    /** CLEAR for anything unrecognised, so a bad id can never leave the sky undefined. */
    public static WeatherProfile byId(byte id) {
        final int i = id & 0xFF;
        if (i >= BY_ID.length || BY_ID[i] == null) {
            return CLEAR;
        }
        return BY_ID[i];
    }

    /** Match a GM's word, or null. */
    public static WeatherProfile byName(String s) {
        for (WeatherProfile p : values()) {
            if (p.name.equalsIgnoreCase(s)) {
                return p;
            }
        }
        return null;
    }

    /** Every profile name joined by |, for the usage line. */
    public static String namesForUsage() {
        final StringBuilder sb = new StringBuilder();
        for (WeatherProfile p : values()) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(p.name);
        }
        return sb.toString();
    }
}
