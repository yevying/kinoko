package kinoko.world.job.explorer;

import kinoko.packet.field.MobPacket;
import kinoko.packet.user.UserLocal;
import kinoko.packet.user.UserRemote;
import kinoko.provider.SkillProvider;
import kinoko.provider.skill.SkillInfo;
import kinoko.provider.skill.SkillStat;
import kinoko.server.header.OutHeader;
import kinoko.util.Util;
import kinoko.world.GameConstants;
import kinoko.world.field.Field;
import kinoko.world.field.mob.Mob;
import kinoko.world.field.mob.MobStatOption;
import kinoko.world.field.mob.MobTemporaryStat;
import kinoko.world.field.summoned.Summoned;
import kinoko.world.field.summoned.SummonedAssistType;
import kinoko.world.field.summoned.SummonedMoveAbility;
import kinoko.world.item.BodyPart;
import kinoko.world.item.Item;
import kinoko.world.item.WeaponType;
import kinoko.world.skill.ActionType;
import kinoko.world.skill.Attack;
import kinoko.world.skill.Skill;
import kinoko.world.skill.SkillProcessor;
import kinoko.world.user.User;
import kinoko.world.user.effect.Effect;
import kinoko.world.user.stat.CalcDamage;
import kinoko.world.user.stat.CharacterTemporaryStat;
import kinoko.world.user.stat.TemporaryStatOption;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

public final class Warrior extends SkillProcessor {
    // WARRIOR
    public static final int HP_BOOST = 1000006;
    public static final int IRON_BODY = 1001003;
    public static final int POWER_STRIKE = 1001004;
    public static final int SLASH_BLAST = 1001005;
    // FIGHTER
    public static final int WEAPON_MASTERY_HERO = 1100000;
    public static final int FINAL_ATTACK_HERO = 1100002;
    public static final int ENHANCED_BASICS_HERO = 1100009;
    public static final int WEAPON_BOOSTER_HERO = 1101004;
    public static final int RAGE = 1101006;
    public static final int POWER_GUARD_HERO = 1101007;
    public static final int GROUND_SMASH_HERO = 1101008;
    // CRUSADER
    public static final int IMPROVING_MP_RECOVERY = 1110000;
    public static final int CHANCE_ATTACK = 1110009;
    public static final int COMBO_ATTACK = 1111002;
    public static final int PANIC = 1111003;
    public static final int COMA = 1111005;
    public static final int MAGIC_CRASH_HERO = 1111007;
    public static final int SHOUT = 1111008;
    public static final int BRANDISH = 1111010;
    // HERO
    public static final int ADVANCED_COMBO_ATTACK = 1120003;
    public static final int ACHILLES_HERO = 1120004;
    public static final int COMBAT_MASTERY = 1120012;
    public static final int MAPLE_WARRIOR_HERO = 1121000;
    public static final int MONSTER_MAGNET_HERO = 1121001;
    public static final int POWER_STANCE_HERO = 1121002;
    public static final int RUSH_HERO = 1121006;
    public static final int INTREPID_SLASH = 1121008;
    public static final int ENRAGE = 1121010;
    public static final int HEROS_WILL_HERO = 1121011;
    // PAGE
    public static final int WEAPON_MASTERY_PALADIN = 1200000;
    public static final int FINAL_ATTACK_PALADIN = 1200002;
    public static final int ENHANCED_BASICS_PALADIN = 1200009;
    public static final int WEAPON_BOOSTER_PALADIN = 1201004;
    public static final int THREATEN = 1201006;
    public static final int POWER_GUARD_PALADIN = 1201007;
    public static final int GROUND_SMASH_PALADIN = 1201008;
    // WHITE_KNIGHT
    public static final int SHIELD_MASTERY = 1210001;
    public static final int CHARGED_BLOW = 1211002;
    public static final int FIRE_CHARGE = 1211004;
    public static final int ICE_CHARGE = 1211006;
    public static final int LIGHTNING_CHARGE = 1211008;
    public static final int MAGIC_CRASH_PALADIN = 1211009;
    public static final int HP_RECOVERY = 1211010;
    public static final int COMBAT_ORDERS = 1211011;
    // PALADIN
    public static final int ACHILLES_PALADIN = 1220005;
    public static final int GUARDIAN = 1220006;
    public static final int ADVANCED_CHARGE = 1220010;
    public static final int DIVINE_SHIELD = 1220013;
    public static final int MAPLE_WARRIOR_PALADIN = 1221000;
    public static final int POWER_STANCE_PALADIN = 1221002;
    public static final int DIVINE_CHARGE = 1221004;
    public static final int RUSH_PALADIN = 1221007;
    public static final int BLAST = 1221009;
    public static final int HEAVENS_HAMMER = 1221011;
    public static final int HEROS_WILL_PALADIN = 1221012;
    // SPEARMAN
    public static final int WEAPON_MASTERY_DRK = 1300000;
    public static final int FINAL_ATTACK_DRK = 1300002;
    public static final int ENHANCED_BASICS_DRK = 1300009;
    public static final int WEAPON_BOOSTER_DRK = 1301004;
    public static final int IRON_WILL = 1301006;
    public static final int HYPER_BODY = 1301007;
    public static final int GROUND_SMASH_DRK = 1301008;
    // DRAGON_KNIGHT
    public static final int ELEMENTAL_RESISTANCE = 1310000;
    public static final int DRAGON_WISDOM = 1310009;
    public static final int DRAGON_BUSTER = 1311001;
    public static final int DRAGON_FURY = 1311003;
    public static final int SACRIFICE = 1311005;
    public static final int DRAGON_ROAR = 1311006;
    public static final int MAGIC_CRASH_DRK = 1311007;
    public static final int DRAGON_BLOOD = 1311008;
    // DARK_KNIGHT
    public static final int ACHILLES_DRK = 1320005;
    public static final int BERSERK = 1320006;
    public static final int AURA_OF_THE_BEHOLDER = 1320008;
    public static final int HEX_OF_THE_BEHOLDER = 1320009;
    public static final int HEX_OF_THE_BEHOLDER_COUNTER = 1320011;
    public static final int MAPLE_WARRIOR_DRK = 1321000;
    public static final int MONSTER_MAGNET_DRK = 1321001;
    public static final int POWER_STANCE_DRK = 1321002;
    public static final int RUSH_DRK = 1321003;
    public static final int BEHOLDER = 1321007;
    public static final int HEROS_WILL_DRK = 1321010;

    public static void handleAttack(User user, Mob mob, Attack attack, int delay) {
        final SkillInfo si = SkillProvider.getSkillInfoById(attack.skillId).orElseThrow();
        final int skillId = attack.skillId;
        final int slv = attack.slv;

        switch (skillId) {
            case MONSTER_MAGNET_HERO:
            case MONSTER_MAGNET_DRK:
                if (!mob.isBoss()) {
                    mob.setTemporaryStat(MobTemporaryStat.Stun, MobStatOption.of(1, skillId, si.getDuration(slv)), delay);
                }
                return;
            case SHOUT:
            case CHARGED_BLOW:
                if (!mob.isBoss() && Util.succeedProp(si.getValue(SkillStat.prop, slv))) {
                    mob.setTemporaryStat(MobTemporaryStat.Stun, MobStatOption.of(1, skillId, si.getDuration(slv)), delay);
                }
                break;
            case BLAST:
                if (!mob.isBoss() && Util.succeedProp(si.getValue(SkillStat.prop, slv))) {
                    mob.damage(user, mob.getHp(), delay, false); // HP-based kill: never night-scaled
                }
                break;
        }
    }

    public static void handleSkill(User user, Skill skill) {
        final SkillInfo si = SkillProvider.getSkillInfoById(skill.skillId).orElseThrow();
        final int skillId = skill.skillId;
        final int slv = skill.slv;

        final Field field = user.getField();
        switch (skillId) {
            // MONSTER MAGNET (1121001/1321001) — CUserLocal::TryDoingMonsterMagnet
            // 专用包 opcode 103 已由 SkillHandler 解码 targetIds/left。对非 Boss 目标：
            //   1) 施加 Stun（MobStatSet 广播 → 其他客户端怪物头顶旋转星星）
            //   2) 服务端计算伤害并扣血，广播 MobDamaged 时排除攻击者（攻击者本端由本地预测
            //      显示伤害数字，与普通技能一致，避免"正常伤害 + 0/miss"双重显示；远程观察者
            //      仍由 MobDamaged 驱动原版/Godot 客户端显示，不走 UserRemote.attack；
            //      攻击者的 HP 更新由 mob.damage → updateHp 发 MobHPIndicator 298 权威下发）
            //   3) 广播 UserRemote.attack 给远程观察者，播放施法者动作+磁铁施法特效
            //      （否则远程端只看到特效、看不到施法动作；action=ActionType.NO 使包不含
            //      攻击目标数据段，远程端不重复显示伤害数字）
            case MONSTER_MAGNET_HERO:
            case MONSTER_MAGNET_DRK:
                final int magnetDamage = (int) Math.clamp(CalcDamage.calcDamageMax(user) * si.getValue(SkillStat.damage, slv) / 100, 1.0, GameConstants.DAMAGE_MAX);
                skill.forEachAffectedMob(field, (mob) -> {
                    if (!mob.isBoss()) {
                        mob.setTemporaryStat(MobTemporaryStat.Stun, MobStatOption.of(1, skill.skillId, si.getDuration(skill.slv)), 0);
                        mob.damage(user, magnetDamage, 0);
                        field.broadcastPacket(MobPacket.mobDamaged(mob, magnetDamage), user);
                    }
                });
                // 施法者动作广播（无论是否命中目标，远程观察者都应看到施法动作）。
                // 必须使用真实武器动作码（而非 ActionType.NO=273 内部哨兵）：原版 095 客户端的
                // action 表只识别 <0x111 的动作码，收到 273 时无法解析攻击包 → 远程玩家看不到施法
                // 动作（Godot 客户端有 ResolveFallbackAttackAction 兜底，所以原版施法时 Godot 能看到）。
                // 按武器类型发对应挥砍动作：1H → swingO1、2H → swingT1、枪矛 → swingP1；
                // 攻击数据段保持空（mask=0 无目标），远程端只播动作，伤害/眩晕由 MobDamaged/MobStatSet 驱动。
                final Item weapon = user.getInventoryManager().getEquipped().getItem(BodyPart.WEAPON.getValue());
                final WeaponType weaponType = WeaponType.getByItemId(weapon != null ? weapon.getItemId() : 0);
                final ActionType magnetAction = switch (weaponType) {
                    case TH_SWORD, TH_AXE, TH_MACE -> ActionType.SWINGT1;
                    case SPEAR, POLEARM -> ActionType.SWINGP1;
                    default -> ActionType.SWINGO1;
                };
                final Attack magnetAttack = new Attack(OutHeader.UserMeleeAttack);
                magnetAttack.skillId = skill.skillId;
                magnetAttack.slv = skill.slv;
                magnetAttack.mask = 0;
                magnetAttack.flag = 0;
                magnetAttack.attackSpeed = 6;
                magnetAttack.actionAndDir = (short) ((skill.left ? 0x8000 : 0) | magnetAction.getValue());
                field.broadcastPacket(UserRemote.attack(user, magnetAttack), user);
                return;
            // COMMON
            case POWER_GUARD_HERO:
            case POWER_GUARD_PALADIN:
                user.setTemporaryStat(CharacterTemporaryStat.PowerGuard, TemporaryStatOption.of(si.getValue(SkillStat.x, slv), skillId, si.getDuration(slv)));
                return;
            case MAGIC_CRASH_HERO:
            case MAGIC_CRASH_PALADIN:
            case MAGIC_CRASH_DRK:
                skill.forEachAffectedMob(field, (mob) -> {
                    if (Util.succeedProp(si.getValue(SkillStat.prop, slv))) {
                        mob.setTemporaryStat(MobTemporaryStat.MagicCrash, MobStatOption.of(1, skillId, si.getDuration(slv)), skill.delay);
                    }
                });
                return;
            case POWER_STANCE_HERO:
            case POWER_STANCE_PALADIN:
            case POWER_STANCE_DRK:
                user.setTemporaryStat(CharacterTemporaryStat.Stance, TemporaryStatOption.of(si.getValue(SkillStat.prop, slv), skillId, si.getDuration(slv)));
                return;

            // HERO
            case ENRAGE:
                final int nEnrage = si.getValue(SkillStat.x, slv) * 100 + si.getValue(SkillStat.mobCount, slv); // damR = n / 100, nCount = n % 100
                user.setTemporaryStat(CharacterTemporaryStat.Enrage, TemporaryStatOption.of(nEnrage, skillId, si.getDuration(slv)));
                resetComboCounter(user);
                return;

            // PALADIN
            case THREATEN:
                skill.forEachAffectedMob(field, (mob) -> {
                    if (!mob.isBoss()) {
                        mob.setTemporaryStat(Map.of(
                                MobTemporaryStat.PAD, MobStatOption.of(si.getValue(SkillStat.x, slv), skillId, si.getDuration(slv)),
                                MobTemporaryStat.PDR, MobStatOption.of(si.getValue(SkillStat.y, slv), skillId, si.getDuration(slv)),
                                MobTemporaryStat.Blind, MobStatOption.of(si.getValue(SkillStat.z, slv), skillId, si.getValue(SkillStat.subTime, slv) * 1000)
                        ), 0);
                    }
                });
                return;
            case HP_RECOVERY:
                final int hpRecovery = user.getMaxHp() * si.getValue(SkillStat.x, slv) / 100;
                user.addHp(hpRecovery);
                user.write(UserLocal.effect(Effect.incDecHpEffect(hpRecovery)));
                field.broadcastPacket(UserRemote.effect(user, Effect.incDecHpEffect(hpRecovery)), user);
                return;
            case COMBAT_ORDERS:
                user.setTemporaryStat(CharacterTemporaryStat.CombatOrders, TemporaryStatOption.of(si.getValue(SkillStat.x, slv), skillId, si.getDuration(slv)));
                return;
            case FIRE_CHARGE:
            case ICE_CHARGE:
            case DIVINE_CHARGE:
                user.setTemporaryStat(CharacterTemporaryStat.WeaponCharge, TemporaryStatOption.of(1, skillId, si.getDuration(slv)));
                return;
            case LIGHTNING_CHARGE:
                user.setTemporaryStat(CharacterTemporaryStat.AssistCharge, TemporaryStatOption.of(1, skillId, si.getDuration(slv)));
                return;

            // DARK KNIGHT
            case IRON_WILL:
                user.setTemporaryStat(Map.of(
                        CharacterTemporaryStat.PDD, TemporaryStatOption.of(si.getValue(SkillStat.pdd, slv), skillId, si.getDuration(slv)),
                        CharacterTemporaryStat.MDD, TemporaryStatOption.of(si.getValue(SkillStat.mdd, slv), skillId, si.getDuration(slv))
                ));
                return;
            case HYPER_BODY:
                user.setTemporaryStat(Map.of(
                        CharacterTemporaryStat.MaxHP, TemporaryStatOption.of(si.getValue(SkillStat.x, slv), skillId, si.getDuration(slv)),
                        CharacterTemporaryStat.MaxMP, TemporaryStatOption.of(si.getValue(SkillStat.x, slv), skillId, si.getDuration(slv))
                ));
                return;
            case DRAGON_BLOOD:
                user.setTemporaryStat(Map.of(
                        CharacterTemporaryStat.DragonBlood, TemporaryStatOption.of(si.getValue(SkillStat.x, slv), skillId, si.getDuration(slv)),
                        CharacterTemporaryStat.PAD, TemporaryStatOption.of(si.getValue(SkillStat.pad, slv), skillId, si.getDuration(slv))
                ));
                user.setSchedule(skillId, Instant.now().plus(1, ChronoUnit.SECONDS)); // -x HP every sec
                return;
            case AURA_OF_THE_BEHOLDER:
                final int healAmount = si.getValue(SkillStat.hp, slv);
                user.addHp(healAmount);
                user.write(UserLocal.effect(Effect.incDecHpEffect(healAmount)));
                field.broadcastPacket(UserRemote.effect(user, Effect.incDecHpEffect(healAmount)), user);
                handleBeholderEffect(user);
                return;
            case HEX_OF_THE_BEHOLDER:
                switch (skill.summonBuffType) {
                    case 0 -> {
                        user.setTemporaryStat(CharacterTemporaryStat.EPDD, TemporaryStatOption.of(si.getValue(SkillStat.epdd, slv), skillId, si.getDuration(slv))); // BUFF_PDD
                    }
                    case 1 -> {
                        user.setTemporaryStat(CharacterTemporaryStat.EMDD, TemporaryStatOption.of(si.getValue(SkillStat.emdd, slv), skillId, si.getDuration(slv))); // BUFF_MDD
                    }
                    case 2 -> {
                        user.setTemporaryStat(CharacterTemporaryStat.ACC, TemporaryStatOption.of(si.getValue(SkillStat.acc, slv), skillId, si.getDuration(slv))); // BUFF_ACC
                    }
                    case 3 -> {
                        user.setTemporaryStat(CharacterTemporaryStat.EVA, TemporaryStatOption.of(si.getValue(SkillStat.eva, slv), skillId, si.getDuration(slv))); // BUFF_EVA
                    }
                    case 4 -> {
                        user.setTemporaryStat(CharacterTemporaryStat.EPAD, TemporaryStatOption.of(si.getValue(SkillStat.epad, slv), skillId, si.getDuration(slv))); // BUFF_PAD
                    }
                    default -> {
                        log.error("Received summon buff type {} for skill ID {}", skill.summonBuffType, skillId);
                        return;
                    }
                }
                handleBeholderEffect(user);
                return;
            case BEHOLDER:
                final int beholderDuration = si.getValue(SkillStat.x, slv) * 60 * 1000; // x min
                final Summoned beholder = Summoned.from(skillId, slv, SummonedMoveAbility.WALK, SummonedAssistType.HEAL, Instant.now().plus(beholderDuration, ChronoUnit.MILLIS));
                beholder.setPosition(user.getField(), skill.positionX, skill.positionY, skill.summonLeft);
                user.addSummoned(beholder);
                user.setTemporaryStat(CharacterTemporaryStat.Beholder, TemporaryStatOption.of(si.getValue(SkillStat.mastery, slv), skillId, beholderDuration));
                return;
        }
        log.error("Unhandled skill {}", skill.skillId);
    }

    public static void resetComboCounter(User user) {
        final TemporaryStatOption option = user.getSecondaryStat().getOption(CharacterTemporaryStat.ComboCounter);
        if (option.nOption > 1) {
            user.setTemporaryStat(CharacterTemporaryStat.ComboCounter, option.update(1));
        }
    }

    public static void handleBerserkEffect(User user) {
        final int skillId = Warrior.BERSERK;
        final int slv = user.getSkillLevel(skillId);
        if (slv == 0) {
            return;
        }
        final Effect berserkEffect = Effect.skillUseEnable(skillId, slv, user.getLevel(), isBerserkEffect(user));
        user.write(UserLocal.effect(berserkEffect));
        user.getField().broadcastPacket(UserRemote.effect(user, berserkEffect), user);
    }

    public static boolean isBerserkEffect(User user) {
        final int skillId = Warrior.BERSERK;
        final int slv = user.getSkillLevel(skillId);
        if (slv == 0) {
            return false;
        }
        final Optional<SkillInfo> skillInfoResult = SkillProvider.getSkillInfoById(skillId);
        if (skillInfoResult.isEmpty()) {
            log.error("Could not resolve skill info for berserk skill ID : {}", skillId);
            return false;
        }
        final int threshold = skillInfoResult.get().getValue(SkillStat.x, slv);
        final int percentage = (int) ((double) user.getHp() / user.getMaxHp() * 100);
        return percentage > threshold;
    }

    public static void handleBeholderEffect(User user) {
        final int skillId = Warrior.BEHOLDER;
        final Effect beholderEffect = Effect.skillAffected(skillId, user.getSkillLevel(skillId));
        user.write(UserLocal.effect(beholderEffect));
        user.getField().broadcastPacket(UserRemote.effect(user, beholderEffect), user);
    }
}
