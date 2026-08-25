package kinoko.weather;

import kinoko.provider.WeatherMapVisibility;
import kinoko.server.node.ChannelServerNode;
import kinoko.server.node.ServerExecutor;
import kinoko.world.user.User;
import kinoko.world.user.stat.CharacterTemporaryStat;
import kinoko.world.user.stat.TemporaryStatOption;

import java.util.Map;

/**
 * Walking into a blizzard slows you down, visibly.
 *
 * <p>移植自参考 server/weather/WeatherDebuff.java。参考用 BuffStat.SPEED=-10 经 StatEffect 下发；
 * kinoko 改用独立的 {@link CharacterTemporaryStat#Slow}（bit 32）—— 不与 Speed/Haste 叠加冲突，
 * 且客户端 SecondaryStatProtocol 已解析该位（rOption 守卫 + 移速折叠 + buff 图标）。
 *
 * <p><b>不显式取消</b>（参考原注释）：暴风雪结束只是 60s tick 停止续期，tOption（75s）到期后
 * 客户端自动 lapse，绝不误清玩家自己的 Haste。
 *
 * <p>线程模型：tick 跑在 service 线程，field 状态变更（User.setTemporaryStat）经
 * {@link ServerExecutor#submit(User, Runnable)} 投递到该 user 所在 field 的 GameExecutor。
 */
public final class WeatherDebuff {

    /** 减速百分比（客户端按 * (100 - slow) / 100 应用）。 */
    private static final int BLIZZARD_SLOW = 10;

    /**
     * 减速 buff 的 rOption：真实技能 I/L 大魔导士暴风雪(2211006)，客户端据此查真实技能图标
     * （Skill.wz/2.img/skill/2211006/icon）。参考用私有物品 5120994 控 tooltip —— 记为简化点。
     */
    private static final int SOURCE_SKILL_ID = 2211006;

    /**
     * 每次应用持续时长。大于 60s 广播 tick，刷新搭该 tick 走，避免图标每分钟闪断。
     */
    private static final int DURATION_MS = 75 * 1000;

    private WeatherDebuff() {
    }

    /** Is the sky over this map one that should slow the player? */
    private static boolean slows(int mapId) {
        return WeatherMapVisibility.hasVisibleSky(mapId)
                && WeatherService.skyForMap(mapId) == WeatherProfile.BLIZZARD.id();
    }

    /** Apply or refresh the slow for every eligible user on a channel, or let it lapse. */
    public static void refresh(ChannelServerNode channel) {
        if (channel == null) {
            return;
        }
        if (!WeatherSettings.blizzardSlow()) {
            return;
        }
        for (User user : channel.getConnectedUsers()) {
            try {
                if (user.getField() == null) {
                    continue;   // in the cash shop / character select: no sky to slow under
                }
                final int mapId = user.getFieldId();
                if (!slows(mapId)) {
                    continue;
                }
                // Dispatch to the user's field executor; re-check so a mid-tick warp is safe.
                ServerExecutor.submit(user, () -> {
                    if (user.getField() == null || !slows(user.getFieldId())) {
                        return;
                    }
                    user.setTemporaryStat(Map.of(
                            CharacterTemporaryStat.Slow,
                            TemporaryStatOption.of(BLIZZARD_SLOW, SOURCE_SKILL_ID, DURATION_MS)), 0);
                });
            } catch (Exception e) {
                // one unhappy user must not cost the rest of the channel its slow
            }
        }
    }
}
