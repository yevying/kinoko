package kinoko.weather;

import kinoko.provider.MobProvider;
import kinoko.provider.mob.MobTemplate;
import kinoko.server.field.InstanceFieldStorage;
import kinoko.server.node.ChannelServerNode;
import kinoko.server.node.ServerExecutor;
import kinoko.world.field.Field;
import kinoko.world.field.mob.Mob;
import kinoko.world.field.mob.MobLeaveType;
import kinoko.world.field.mob.MobSpawnPoint;
import kinoko.world.user.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Small, regional encounters that exist only after nightfall.
 *
 * <p>移植自参考 server/weather/NocturnalMobService.java。不新增 SpawnPoint：锚点借用普通 WZ
 * 出生点（mobTime==0），不 invent 坐标；夜行怪独立于容量统计（不计入 MobPool 容量，也不与
 * 白天人口互相挤占）。
 *
 * <p>线程模型：{@link #refreshField(Field)} 从 {@code Field.addUser} 调（field executor，内联）；
 * {@link #refresh(ChannelServerNode)} 从 60s WeatherTask tick 调（service 线程），field 变更一律
 * 经 {@link ServerExecutor#submit(Field, Runnable)} 投递到该 field 的 GameExecutor。
 *
 * <p>SPAWNED 用 {@link ConcurrentHashMap}（取代计划稿的 IdentityHashMap + 全局 LOCK）：它是跨
 * field executor 与 service 线程唯一共享的结构，表的 computeIfAbsent/remove 原子、keySet 弱一致
 * 遍历，天然免锁；每个 field 的 {@link NocturnalState#monsters} 只被该 field 自己的单线程
 * GameExecutor 触碰（addUser 内联 / submit 投递均在同一 executor），无跨线程竞争。因此不需要
 * 全局锁，也避免了"提交的 runnable 持锁做 field 操作"违反计划约束的问题。
 */
public final class NocturnalMobService {
    private NocturnalMobService() {
    }

    /** Identity matters: two channels can hold separate Field instances with one map id. */
    private static final ConcurrentHashMap<Field, NocturnalState> SPAWNED = new ConcurrentHashMap<>();

    /** A map rolls one target on first night entry and keeps it until dawn. */
    private static final class NocturnalState {
        private final int target;
        private final List<Mob> monsters = new ArrayList<>();

        private NocturnalState(int target) {
            this.target = target;
        }
    }

    /**
     * Initial regional roster. Each field rolls one stable target in its range for the
     * whole night, so a night feels populated without the cap visibly changing every
     * minute as the WeatherTask refreshes it.
     *
     * <ul>
     *   <li>Stirge (2300100): Henesys bats</li>
     *   <li>Fairy 1 / Jr. Wraith (3000001 / 3230101): Ellinia wisps and ghosts</li>
     *   <li>Jr. Necki (2130103): Kerning's Snekis</li>
     *   <li>Fire Boar (3210100): Perion</li>
     *   <li>Lorang (3230102): Lith Harbour's moonlit shore</li>
     * </ul>
     */
    private enum Pool {
        HENESYS(WeatherRegion.HENESYS, 3, 5, 2300100),
        ELLINIA(WeatherRegion.ELLINIA, 4, 6, 3000001, 3230101),
        KERNING(WeatherRegion.KERNING_CITY, 3, 5, 2130103),
        PERION(WeatherRegion.PERION, 3, 4, 3210100),
        LITH_HARBOUR(WeatherRegion.LITH_HARBOUR, 3, 5, 3230102);

        private final WeatherRegion region;
        private final int minCap;
        private final int maxCap;
        private final int[] mobIds;

        Pool(WeatherRegion region, int minCap, int maxCap, int... mobIds) {
            this.region = region;
            this.minCap = minCap;
            this.maxCap = maxCap;
            this.mobIds = mobIds;
        }

        private int targetForNight() {
            return minCap + ThreadLocalRandom.current().nextInt(maxCap - minCap + 1);
        }

        private int nextMobId() {
            return mobIds[ThreadLocalRandom.current().nextInt(mobIds.length)];
        }

        private static Pool forMap(int mapId) {
            final WeatherRegion region = WeatherRegion.forMap(mapId);
            for (Pool pool : values()) {
                if (pool.region == region) {
                    return pool;
                }
            }
            return null;
        }
    }

    /** Called on map entry so an eligible field is ready immediately at night. */
    public static void refreshField(Field field) {
        if (field == null) {
            return;
        }
        if (WeatherSettings.nocturnalMobs() && WeatherService.isNight()) {
            refreshMap(field);
        } else {
            despawnMap(field);
        }
    }

    /**
     * Called once per WeatherTask tick. Only occupied maps are considered at night.
     * (matching reference: WeatherTask run → NocturnalMobService.refreshWorld)
     */
    public static void refresh(ChannelServerNode channel) {
        if (channel == null) {
            return;
        }
        if (!WeatherSettings.nocturnalMobs() || !WeatherService.isNight()) {
            despawnAll();
            return;
        }

        // A map that has emptied since the previous tick is otherwise never seen
        // again until dawn, so its ambient monsters and strong references linger for
        // the rest of the night. Check the map itself rather than only this channel's
        // set: SPAWNED holds distinct channel instances by identity.
        for (Field field : new ArrayList<>(SPAWNED.keySet())) {
            if (!field.hasUser()) {
                ServerExecutor.submit(field, () -> {
                    if (field.hasUser()) {
                        return;   // someone came back; refreshField will handle it
                    }
                    despawnMap(field);
                });
            }
        }

        final Set<Field> occupied = new HashSet<>();
        for (User user : channel.getConnectedUsers()) {
            if (user.getField() != null) {
                occupied.add(user.getField());
            }
        }
        for (Field field : occupied) {
            ServerExecutor.submit(field, () -> refreshMap(field));
        }
    }

    private static void refreshMap(Field field) {
        final Pool pool = Pool.forMap(field.getFieldId());
        // Towns and instanced/event maps never receive ambient encounters. The latter
        // can share a regional map id but have tightly scripted population rules.
        if (pool == null || field.getMapInfo().isTown() || field.getFieldStorage() instanceof InstanceFieldStorage) {
            despawnMap(field);
            return;
        }

        final List<MobSpawnPoint> anchors = field.getMobPool().getNocturnalSpawnPoints();
        if (anchors.isEmpty()) {
            return;
        }

        final NocturnalState state = SPAWNED.computeIfAbsent(field,
                ignored -> new NocturnalState(pool.targetForNight()));
        state.monsters.removeIf(monster -> !isStillInField(field, monster));
        final int missing = state.target - state.monsters.size();
        if (missing <= 0) {
            return;
        }

        Collections.shuffle(anchors);
        for (int i = 0; i < missing && i < anchors.size(); i++) {
            final MobSpawnPoint sp = anchors.get(i);
            final Optional<MobTemplate> template = MobProvider.getMobTemplate(pool.nextMobId());
            if (template.isEmpty()) {
                continue;
            }
            final Mob mob = new Mob(template.get(), null, sp.getX(), sp.getY(), sp.getFh());
            mob.setNocturnal(true);
            field.getMobPool().addMob(mob);
            state.monsters.add(mob);
        }
    }

    private static boolean isStillInField(Field field, Mob mob) {
        return mob != null && mob.getHp() > 0
                && field.getMobPool().getById(mob.getId()).orElse(null) == mob;
    }

    private static void despawnAll() {
        for (Field field : new ArrayList<>(SPAWNED.keySet())) {
            ServerExecutor.submit(field, () -> despawnMap(field));
        }
    }

    /** Dawn removal awards neither EXP nor drops and leaves the normal field untouched. */
    private static void despawnMap(Field field) {
        final NocturnalState state = SPAWNED.remove(field);
        if (state == null) {
            return;
        }
        for (Mob mob : state.monsters) {
            if (isStillInField(field, mob)) {
                field.getMobPool().removeMob(mob, MobLeaveType.ETC);
            }
        }
    }
}
