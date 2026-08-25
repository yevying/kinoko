package kinoko.provider;

import kinoko.provider.wz.serialize.WzProperty;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side twin of the client's {@code WeatherSkyTable} (FieldHasSky classifier).
 *
 * <p>Combat must not grant an invisible bonus or hand out a debuff in a field where the
 * client has correctly suppressed the weather. The source of truth is the backmost WZ
 * backdrop: only the banks in {@link #SKY_BASES} render an open sky. {@link #NO_SKY_MAPS}
 * mirrors the client's exceptional interior list, for maps that reuse an outdoor backdrop.
 *
 * <p>数据镜像 maple-client/scripts/Map/WeatherSkyTable.cs（客户端已 verified），两端必须一致。
 * 与客户端一致，bS 比较**不区分大小写**（参考用 _wcsicmp；这里两侧都小写）。
 */
public final class WeatherMapVisibility {
    private WeatherMapVisibility() {
    }

    private static final ConcurrentHashMap<Integer, Boolean> visibleByMap = new ConcurrentHashMap<>();

    private static final Set<String> SKY_BASES = buildSkyBases();

    private static final int[] RAW_NO_SKY_MAPS = {
            101000001, 101000002, 101000003, 101020001, 101020002, 101020003, 101020004,
            101020005, 101020006, 101020007, 101020008, 101020009, 101020010, 101030105,
            101030106, 101030107, 101030108, 101030110, 101030111, 101030112, 101050100,
            102000001, 102000002, 102000003, 103000001, 103000002, 103000003, 103000004,
            103000005, 103000006, 103000100, 103040000, 103040100, 103040101, 103040102,
            103040103, 103040200, 103040201, 103040202, 103040203, 103040300, 103040301,
            103040302, 103040303, 103040400, 103040410, 103040420, 103040430, 103040440,
            103040450, 103040460, 103050101, 104000001, 104000002, 105040400, 105040401,
            105040402, 105050000, 105050100, 105050200, 105050300, 105050400, 105060000,
            105060100, 105070000, 105070001, 105070002, 105070100, 105070200, 105070300,
            105070400, 105080000, 105090000, 105090100, 105090200, 105090300, 105090301,
            105090310, 105090311, 105090312, 105090400, 106020000, 106020100, 106020200,
            106020300, 106020400, 106020401, 106020402, 106020403, 106020500, 106020501,
            106020600, 106020601, 106020700, 106020800, 106020900, 106021000, 106021001,
            106021100, 106021200, 106021201, 106021300, 106021400, 106021402, 106021800,
            120000101, 140000001, 140000010, 140000011, 140000012, 140030000, 140090000,
            180000000, 200000001, 200000002, 200000110, 200000150, 200000201, 200000202,
            200090001, 200090011, 211000001, 211000101, 211000102, 211041500, 211041600,
            211041700, 211041800, 211041900, 211042000, 211042100, 211042101, 211042200,
            211042300, 211042400, 220000001, 220000002, 220000003, 220000004, 220000005,
            220000100, 220000302, 220000303, 220000304, 220000307, 220020600, 221000001,
            221000200, 222020400, 230000000, 230000001, 230000002, 230000003, 230010000,
            230010001, 230010100, 230010200, 230010201, 230010300, 230010400, 230020000,
            230020100, 230020101, 230020200, 230020201, 230020300, 230030000, 230030001,
            230030100, 230030101, 230030200, 230040000, 230040001, 230040100, 230040200,
            230040300, 230040301, 230040400, 230040401, 230040410, 230040420, 240000001,
            240000002, 240000003, 240000004, 240000005, 240000006, 240040700, 240050000,
            240050100, 240050101, 240050102, 240050103, 240050104, 240050105, 240050200,
            240050300, 240050310, 240050400, 240050500, 240050600, 240060000, 240060100,
            240060200, 250000001, 250000002, 250000003, 260010301, 261000001, 261000002,
            261000010, 261000011, 261000020, 261000021, 261040000, 270010111, 270020211,
            270030411, 300000011, 300000012, 600010001, 670010750, 670010800, 680000001,
            680000002, 680000003, 680000100, 680000110, 680000200, 680000210, 680000401,
            682010100, 682010101, 682010102, 800010100, 809050001, 809050002, 809050003,
            809050004, 809050005, 809050006, 809050007, 809050008, 809050009, 809050010,
            809050011, 809050012, 809050013, 809050014, 809050015, 809050016, 809050017,
            920010500, 920010600, 920010601, 920010602, 920010603, 922000010, 924010000,
            924010100, 924010200, 926010010, 926010070, 990000420,
    };

    private static final Set<Integer> NO_SKY_MAPS = buildNoSkyMaps();

    private static Set<String> buildSkyBases() {
        // 57 个露天 (bS, no) 键，镜像客户端 WeatherSkyTable.RawSkyKeys；bS 一律小写。
        final String[] keys = {
                "sandBeach#0", "toyCastle#0", "timeTemple#0", "grassySoil#0", "minar#0",
                "dragonValley#0", "skyStation#0", "midForest#0", "mureung#0", "dryRock#0",
                "shineWood#0", "ereb#0", "snowyLightrock#0", "backcho#0", "desert#0",
                "omegaSector#0", "mushCatle#0", "rien#0", "darkMountain#0", "event#20",
                "vicportTown#0", "folkvillige#0", "dryRock2#0", "highland_my#0", "ancientForest#0",
                "folkvillige#23", "weddinggl#0", "snowydarkrock#0", "citysg#0", "sunsetcity#0",
                "christmas#2", "metroCity#0", "nautilusPort#0", "themeback1#0", "eurel#0",
                "shanghaicn#0", "globaljp#0", "shinewood3#0", "desert#29", "nightDesert#0",
                "boatquay1sg#0", "globaljp3_ghost#0", "grassySoil#8", "halloween#0", "minar#31",
                "neocity#10", "cbdfieldsg#0", "rien#18", "folkvillige#16", "boatquay2sg#0",
                "boatquay3sg#0", "changiAirSG#0", "desert#1", "eurel#27", "neocity#0",
                "nightDesert#27", "thai#0",
        };
        final Set<String> set = new HashSet<>(keys.length);
        for (String key : keys) {
            set.add(key.toLowerCase(Locale.ROOT));
        }
        return set;
    }

    private static Set<Integer> buildNoSkyMaps() {
        final Set<Integer> set = new HashSet<>(RAW_NO_SKY_MAPS.length);
        for (int mapId : RAW_NO_SKY_MAPS) {
            set.add(mapId);
        }
        return set;
    }

    /** Record a loaded primary map's renderer-equivalent visibility once, outside combat paths. */
    public static void register(int mapId, WzProperty imageProp) {
        visibleByMap.put(mapId, hasSky(mapId, imageProp));
    }

    /** A linked map reuses the link target's backdrop, so it inherits the target's visibility. */
    public static void registerLinked(int mapId, int linkMapId) {
        visibleByMap.put(mapId, hasVisibleSky(linkMapId));
    }

    /** False is the safe default: an unloaded/unclassified map receives no invisible bonus. */
    public static boolean hasVisibleSky(int mapId) {
        return visibleByMap.getOrDefault(mapId, false);
    }

    private static boolean hasSky(int mapId, WzProperty imageProp) {
        if (imageProp == null || NO_SKY_MAPS.contains(mapId)) {
            return false;
        }
        try {
            if (!(imageProp.get("back") instanceof WzProperty backGroup)) {
                return false;
            }
            if (!(backGroup.get("0") instanceof WzProperty back0)) {
                return false;
            }
            final String bank = WzProvider.getString(back0.get("bS"), "").toLowerCase(Locale.ROOT);
            final int index = WzProvider.getInteger(back0.get("no"), 0);
            return !bank.isEmpty() && SKY_BASES.contains(bank + '#' + index);
        } catch (Exception ignored) {
            return false;
        }
    }
}
