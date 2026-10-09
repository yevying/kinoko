package kinoko.world.field.mob;

import kinoko.handler.field.MobHandler;
import kinoko.handler.user.HitHandler;
import kinoko.packet.user.UserRemote;
import kinoko.provider.SkillProvider;
import kinoko.provider.mob.MobAttack;
import kinoko.provider.mob.MobSkill;
import kinoko.provider.mob.MobTemplate;
import kinoko.provider.skill.SkillInfo;
import kinoko.provider.skill.SkillStat;
import kinoko.world.GameConstants;
import kinoko.world.field.Field;
import kinoko.world.skill.HitInfo;
import kinoko.world.user.User;
import kinoko.util.Util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 服务端权威的怪物攻击 / 技能结算。
 *
 * <p>攻击决策由 {@link MobBehavior} 在字段 tick 中做出：怪物在 attack 的相对命中框内、
 * 冷却就绪时广播 MobMove 攻击动作包（客户端播放 attack{n} 姿态与施法特效），
 * attackAfter 毫秒后调用 {@link #applyMobHit} 服务端结算伤害，复用 HitHandler 的
 * 减伤管线（Achilles / 元素抗性 / Magic Guard / Divine Shield 等），并广播
 * UserRemote.hit 给全场做受击表现（被击者本人 HP 由 statChanged(HP) 权威同步）。</p>
 */
public final class MobCombat {

    /** 服务端权威攻击结算：计算伤害、应用减伤管线、扣血并广播受击表现。 */
    public static void applyMobHit(Mob mob, User target, MobAttack attack, int attackIndex) {
        final Field field = mob.getField();

        final HitInfo hitInfo = new HitInfo();
        hitInfo.attackIndex = attackIndex;
        hitInfo.templateId = mob.getTemplateId();
        hitInfo.mobId = mob.getId();
        hitInfo.dir = (byte) (mob.isLeft() ? 1 : 0);
        hitInfo.magicElemAttr = (byte) attack.getElemAttr();
        hitInfo.knockback = 1; // (bKnockback != 0) + 1：服务端权威攻击不做击退
        hitInfo.guard = 0;
        hitInfo.stance = 0;
        hitInfo.damage = calcAttackDamage(mob, attack, target);

        // 攻击携带的 disease 技能（stun/poison 等 CTS）与 mpBurn
        HitHandler.handleMobAttack(target, hitInfo);
        HitHandler.handleHit(target, hitInfo);

        field.broadcastPacket(UserRemote.hit(target, hitInfo));
    }

    /** 服务端权威技能释放：扣 MP、设置冷却、应用技能效果。 */
    public static void applyMobSkill(Mob mob, MobSkill mobSkill) {
        final SkillInfo si = SkillProvider.getMobSkillInfoById(mobSkill.getSkillId()).orElseThrow();
        final int slv = mobSkill.getSkillLevel();
        final Instant now = Instant.now();
        mob.setMp(Math.max(mob.getMp() - si.getValue(SkillStat.mpCon, slv), 0));
        mob.setNextSkillUse(now.plus(GameConstants.MOB_SKILL_COOLTIME, ChronoUnit.SECONDS));
        mob.setSkillOnCooltime(mobSkill, now.plus(si.getValue(SkillStat.interval, slv), ChronoUnit.SECONDS));
        MobHandler.applyMobSkill(mob, mobSkill, si, 0);
    }

    private static int calcAttackDamage(Mob mob, MobAttack attack, User target) {
        final MobTemplate template = mob.getTemplate();
        final MobStat stat = mob.getMobStat();

        // 有效 PAD/MAD：临时 stat PAD/MAD 为加法，PowerUp/MagicUp 为百分比（matching reference: BMS mob stat）
        int pad = template.getPad();
        int mad = template.getMad();
        if (stat.hasOption(MobTemporaryStat.PAD)) {
            pad += stat.getOption(MobTemporaryStat.PAD).nOption;
        }
        if (stat.hasOption(MobTemporaryStat.MAD)) {
            mad += stat.getOption(MobTemporaryStat.MAD).nOption;
        }
        if (stat.hasOption(MobTemporaryStat.PowerUp)) {
            pad = pad * (100 + stat.getOption(MobTemporaryStat.PowerUp).nOption) / 100;
        }
        if (stat.hasOption(MobTemporaryStat.MagicUp)) {
            mad = mad * (100 + stat.getOption(MobTemporaryStat.MagicUp).nOption) / 100;
        }

        // 命中判定（matching reference: 客户端 MobAi.CheckMobAttackMiss / CombatSystem.CalcEvar）
        int acc = template.getAcc();
        if (stat.hasOption(MobTemporaryStat.ACC)) {
            acc += stat.getOption(MobTemporaryStat.ACC).nOption;
        }
        final int eva = target.getSecondaryStat().getEva();
        if (acc > 0 && eva > 0) {
            int levelDiff = Math.max(0, mob.getLevel() - target.getLevel());
            int evar = (int) ((double) eva / (acc + eva) * 100.0) - levelDiff * 2;
            evar = Math.clamp(evar, 0, 100);
            if (Util.getRandom(100) < evar) {
                return 0; // miss
            }
        }

        final int base = attack.isMagic() ? mad : pad;
        if (base <= 0) {
            return 1;
        }
        // 随机伤害 85% ~ 100%（matching reference: 客户端 MobAi.CalcMobDamage）
        return Math.max(1, (int) (base * 0.85 + (base - base * 0.85) * Util.getRandom().nextDouble()));
    }
}
