package kinoko.weather;

/**
 * Where in the world a map is, for the purpose of what falls out of its sky.
 *
 * <p>Weather is rolled PER REGION, so El Nath can be snowing while Henesys is clear.
 * The client needs no change for this: it renders whatever sky byte it is handed, so
 * "regional weather" is entirely a question of which byte each player is sent, and
 * players are told their own map's sky on entry and on every broadcast.
 *
 * <p>Keyed on the map id PREFIX (the leading three digits). Prefixes that do not exist
 * in this 0.95 world (Ereve 130, Rien 140, Temple of Time 270, Ellin Forest 300,
 * New Leaf City 600, Formosa 740) never match and simply fall through to DEFAULT.
 * Mushroom Shrine 800000000 exists and is the one EXACT-id entry, because it is a
 * single map rather than an area.
 */
public enum WeatherRegion {

    /** Permanently snowbound. Nights colder than the world default, because snow is blue. */
    EL_NATH(WeatherProfile.SNOW, 0x41508E),
    /** Coldest of all, with a little violet so it does not read the same as El Nath. */
    RIEN(WeatherProfile.SNOW, 0x485294),

    /** Permanently in blossom. ONE map, 800000000, the sakura shrine town. */
    MUSHROOM_SHRINE(WeatherProfile.BLOSSOM, 0x605492),

    /** Wet. Rain and storms roughly three times as likely as the world at large. */
    ELLINIA(new double[]{1.0, 3.0, 1.0, 1.5, 2.5, 1.0, 1.0, 0.0, 0.0}, 0x3D666C),

    /** Arid. Rain is a rarity here. */
    PERION(new double[]{1.0, 0.1, 0.5, 0.6, 0.1, 0.5, 1.0, 0.0, 0.0}, 0x635582),

    // ------------------------------------------------------------------ the regions
    //
    // Each row is a night colour and, for most, a WEATHER BIAS: nine multipliers indexed
    // by WeatherProfile ordinal, in the order
    //
    //     { clear, rain, snow, overcast, storm, blizzard, leaves, blossom, sandstorm }
    //
    // The bias MULTIPLIES the seasonal table rather than replacing it, so a region can
    // make a sky likelier or rarer but can never put one in a season it does not belong
    // to. A ZERO is the only way to say "never here, in any season".
    //
    // BLOSSOM (column 8) and SANDSTORM (column 9) are OPT IN: pick() zeroes them for any
    // region with no bias at all. A region with a bias still opts in via its row.

    /** Smoggy and overcast far more often than bright. Rain suits the place. */
    KERNING_CITY(new double[]{0.7, 1.4, 1.0, 2.0, 1.0, 1.0, 0.8, 0.0, 0.0}, 0x6E4A7E),
    /** The same red sunset as Kerning and the same kind of weather: the rainiest city. */
    SHOWA(new double[]{0.7, 1.9, 0.8, 1.7, 1.0, 0.6, 0.8, 0.0, 0.0}, 0x74497A),

    /** A city in the clouds, so it gets storms well above anywhere else. */
    ORBIS(new double[]{0.6, 1.8, 1.2, 1.6, 2.6, 1.0, 0.6, 0.0, 0.0}, 0x6A5090),
    /** A mountain temple in permanent mist: overcast far more than anything else. */
    MU_LUNG(new double[]{0.8, 1.3, 1.0, 2.0, 0.6, 0.8, 1.2, 0.0, 0.0}, 0x6B4E84),
    /** Desert. Rain close to unheard of, never snow, and the only sandstorm in the game. */
    ARIANT(new double[]{1.6, 0.05, 0.0, 0.3, 0.05, 0.0, 0.2, 0.0, 5.0}, 0x6E5078),

    /** Deep forest: a lot of grey, very little violence in the sky. Darker night. */
    SLEEPYWOOD(new double[]{0.9, 1.2, 0.8, 1.8, 0.4, 0.5, 1.2, 0.0, 0.0}, 0x2E3857),
    /** NO WEATHER. Aqua Road is under the sea: a forced CLEAR, but it still gets a night. */
    AQUA_ROAD(WeatherProfile.CLEAR, 0x354165),

    /** A jungle plateau. Wet, stormy, and thick with falling leaves. */
    LEAFRE(new double[]{0.8, 1.7, 0.6, 1.3, 1.6, 0.4, 1.5, 0.0, 0.0}, 0x3A5E58),
    /** A toy world inside a clock tower. Mild and stable. */
    LUDIBRIUM(new double[]{1.5, 0.7, 1.0, 0.8, 0.3, 0.8, 0.7, 0.0, 0.0}, 0x574C90),
    /** A tropical beach: bright most of the time, with sudden heavy showers. No winter. */
    FLORINA(new double[]{1.4, 1.3, 0.0, 0.5, 1.2, 0.0, 0.3, 0.0, 0.0}, 0x40646E),
    /** A wedding town. Pleasant here, on purpose, and the one deliberate blossom exception. */
    AMORIA(new double[]{1.6, 0.5, 0.6, 0.4, 0.2, 0.4, 0.6, 2.0, 0.0}, 0x5E5490),
    /** An exposed coastal port. Wet and blowy, and it gets the sea's storms first. */
    LITH_HARBOUR(new double[]{0.7, 1.6, 1.0, 1.6, 1.8, 1.0, 0.7, 0.0, 0.0}, 0x44607E),

    /** Desert-edge town. Dry like Ariant but NO sandstorm; smog takes the weight instead. */
    MAGATIA(new double[]{1.4, 0.1, 0.0, 1.6, 0.1, 0.0, 0.3, 0.0, 0.0}, 0x465A85),
    /** A ship. Everything the open sea throws at one. */
    NAUTILUS(new double[]{0.6, 1.8, 0.6, 1.8, 2.2, 0.5, 0.4, 0.0, 0.0}, 0x46587F),

    // ---------------------------------------------------------------- outdoor regions

    /** Farmland. The gentlest weather in the world; leaves and blossom lead. */
    HENESYS(new double[]{1.3, 0.9, 0.7, 0.7, 0.4, 0.5, 1.8, 0.0, 0.0}, 0x4C5C88),
    /** An island in the sky over open sea. Clear and bright by design, with sea wind. */
    EREVE(new double[]{1.5, 1.1, 0.4, 0.8, 0.6, 0.3, 0.9, 0.0, 0.0}, 0x4E5A96),
    /** Outside time. Overcast almost to the exclusion of everything else. */
    TEMPLE_OF_TIME(new double[]{0.8, 0.4, 0.3, 2.6, 0.2, 0.2, 0.4, 0.0, 0.0}, 0x554A8C),
    /** Primeval jungle, restored. The wettest region after Ellinia. */
    ELLIN_FOREST(new double[]{0.7, 2.2, 0.4, 1.6, 1.4, 0.2, 1.7, 0.0, 0.0}, 0x3C5E5C),
    /** New Leaf City. Kerning's weather with the smog turned up. */
    NEW_LEAF_CITY(new double[]{0.7, 1.6, 0.9, 1.9, 1.1, 0.7, 0.7, 0.0, 0.0}, 0x6E4A7E),
    /** Formosa. Subtropical: hot, bright, and hard brief rain. No winter to speak of. */
    FORMOSA(new double[]{1.2, 1.6, 0.1, 0.9, 1.5, 0.0, 0.8, 0.0, 0.0}, 0x445C7E),
    /** Zipangu at large -- Showa's 801 covers the town, this covers the rest. */
    ZIPANGU(new double[]{0.9, 1.4, 0.9, 1.7, 0.7, 0.6, 1.3, 0.0, 0.0}, 0x4A5490),

    /** Everywhere else: the plain seasonal roll and the reference night colour. */
    DEFAULT(null, null, 0x4A5A8C);

    /**
     * The night colour of the world at large, and the value every profile's own tint is
     * expressed relative to. Must equal the client's neutral row.
     */
    public static final int TINT_NEUTRAL = 0x4A5A8C;

    /** Non-null when this region ignores the roll entirely. */
    private final WeatherProfile forced;

    /** This region's night tint, 0xRRGGBB, as a MULTIPLY against the map's own art. */
    private final int tint;

    /** Per-profile weight multipliers on top of the seasonal table. Null means no bias. */
    private final double[] bias;

    WeatherRegion(WeatherProfile forced, int tint) {
        this.forced = forced;
        this.bias = null;
        this.tint = tint;
    }

    WeatherRegion(double[] bias, int tint) {
        this.forced = null;
        this.bias = bias;
        this.tint = tint;
    }

    WeatherRegion(WeatherProfile forced, double[] bias, int tint) {
        this.forced = forced;
        this.bias = bias;
        this.tint = tint;
    }

    public WeatherProfile forcedProfile() {
        return forced;
    }

    public double[] weightBias() {
        return bias;
    }

    /** This region's night tint, 0xRRGGBB. */
    public int tint() {
        return tint;
    }

    static {
        if (DEFAULT.tint != TINT_NEUTRAL) {
            throw new IllegalStateException("DEFAULT's tint must be TINT_NEUTRAL: it is the value "
                    + "every other region's tint was chosen against.");
        }
        // A bias row is indexed by profile ordinal, so it has to be exactly as wide as the enum.
        final int n = WeatherProfile.values().length;
        for (WeatherRegion r : values()) {
            if (r.bias != null && r.bias.length != n) {
                throw new IllegalStateException("WeatherRegion " + r + " bias has "
                        + r.bias.length + " entries, expected " + n);
            }
        }
    }

    /** Mushroom Shrine, the sakura town in Zipangu. */
    private static final int MUSHROOM_SHRINE_MAP = 800000000;

    /** Which region a map belongs to. Cheap by construction: integer division, no allocation. */
    public static WeatherRegion forMap(int mapId) {
        if (mapId < 0) {
            return DEFAULT;
        }
        final int area = mapId / 1000000;      // the leading three digits
        switch (area) {
            case 100: return HENESYS;
            case 101: return ELLINIA;
            case 102: return PERION;
            case 103: return KERNING_CITY;
            case 104: return LITH_HARBOUR;
            case 105: return SLEEPYWOOD;
            case 110: return FLORINA;
            case 120: return NAUTILUS;
            case 130: return EREVE;
            case 140: return RIEN;
            case 200: return ORBIS;
            case 211: return EL_NATH;
            case 220: case 221: case 222: return LUDIBRIUM;
            case 230: return AQUA_ROAD;
            case 240: return LEAFRE;
            case 250: case 251: return MU_LUNG;   // Mu Lung and Herb Town
            case 260: return ARIANT;
            case 261: return MAGATIA;
            case 270: return TEMPLE_OF_TIME;
            case 300: return ELLIN_FOREST;
            case 600: case 610: return NEW_LEAF_CITY;   // Masteria
            case 680: return AMORIA;
            case 740: case 741: case 742: return FORMOSA;
            // Mushroom Shrine is an EXACT id: its neighbours are ordinary places that roll
            // their own weather. It has to be tested INSIDE this case, because the tail
            // below is only reached by ids that matched no case at all.
            case 800: return (mapId == MUSHROOM_SHRINE_MAP) ? MUSHROOM_SHRINE : ZIPANGU;
            case 801: return SHOWA;
            default:
                break;
        }
        return DEFAULT;
    }
}
