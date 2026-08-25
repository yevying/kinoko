package kinoko.weather;

/**
 * What the sky quietly does to combat.
 *
 * <p><b>This is deliberately invisible.</b> Nothing here sends a packet, writes a chat
 * line, sets a buff icon or shows a number. A player can only infer these by playing.
 *
 * <p>移植自参考 server/weather/WeatherCombat.java（v83 天气系统）。kinoko 的差异：伤害标量
 * 缩放在服务端唯一 HP 写入点（Mob.damage / HitHandler），与参考一致；但 kinoko 会通过
 * UserRemote.hit 把缩放后的受击广播给远程观者，本人浮字由本地自算 —— 属移植必然差异。
 *
 * <p>Night is global: 这些标量作用于所有地图（含无可见天空的室内），与参考一致。
 */
public final class WeatherCombat {

    private WeatherCombat() {
    }

    // ---------------------------------------------------------------- tunables

    /** Night is "in force" above this on the 0..1 curve, i.e. from roughly 18:00 to 06:00. */
    private static final float NIGHT_THRESHOLD = 0.5f;

    /** Monsters hit this much harder at night. */
    private static final double NIGHT_MOB_DAMAGE = 1.10;

    /** Monsters effectively have this much more defence at night (1/1.10, "10% more"). */
    private static final double NIGHT_MOB_DEFENCE = 1.0 / 1.10;

    /** Experience from a kill at night. */
    private static final double NIGHT_EXP = 1.15;

    /** Mob density multiplier at night, folded into MobPool.getMobCapacity(). */
    private static final double NIGHT_SPAWN = 1.35;

    // ---------------------------------------------------------------- scalars

    /** 复用服务端时钟：与参考 isNight()（nightLevel() > 0.5）一致，不重复实现。 */
    public static boolean isNight() {
        return WeatherService.nightLevel() > NIGHT_THRESHOLD;
    }

    /** 夜战标量是否生效：配置开关（weather.properties）与夜晚同时成立。 */
    private static boolean inEffect() {
        return WeatherSettings.nightCombat() && isNight();
    }

    /**
     * Night defence: the monster takes less of what the player dealt.
     *
     * <p>Applied at the single HP write in {@code Mob.damage}. <b>Deliberately takes no
     * skill.</b> The element bonus is a SEPARATE method applied at a different site, and the
     * two must not be reachable from one call, or the night term would land twice.
     */
    public static int scaleDamageToMonster(int damage) {
        if (damage <= 0 || !inEffect()) {
            return damage;
        }
        return apply(damage, NIGHT_MOB_DEFENCE);
    }

    /** Scale damage a monster deals to a player. */
    public static int scaleDamageToPlayer(int damage) {
        if (damage <= 0) {
            return damage;
        }
        return inEffect() ? apply(damage, NIGHT_MOB_DAMAGE) : damage;
    }

    /** Scale the experience a kill awards. */
    public static int scaleExp(int exp) {
        if (exp <= 0) {
            return exp;
        }
        return inEffect() ? apply(exp, NIGHT_EXP) : exp;
    }

    /**
     * Mob density multiplier: 1.0 by day, more at night.
     *
     * <p>Keyed on the {@link #isNight()} BOOLEAN and not on the raw night curve: MobPool's
     * capacity ceils implicitly, so a fractional ramp would step the cap up the moment the
     * curve leaves zero, i.e. one minute after dusk begins. A boolean at the midpoint puts
     * the single step at roughly 18:00, where the sky is visibly dark.
     */
    public static double spawnMultiplier() {
        return inEffect() ? NIGHT_SPAWN : 1.0;
    }

    // ---------------------------------------------------------------- internals

    /**
     * Multiply and round, never letting a positive value become zero.
     *
     * <p>The floor matters: a 1 damage hit scaled by 0.909 truncates to 0, which the rest
     * of the server reads as a MISS.
     */
    private static int apply(int value, double factor) {
        long scaled = Math.round(value * factor);
        if (scaled < 1L) {
            scaled = 1L;
        }
        if (scaled > Integer.MAX_VALUE) {
            scaled = Integer.MAX_VALUE;
        }
        return (int) scaled;
    }
}
