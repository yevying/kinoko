package kinoko.provider.mob;

import kinoko.util.Rect;

public final class MobAttack {
    private final int skillId;
    private final int skillLevel;
    private final int conMp;
    private final int mpBurn;
    private final boolean magic;
    private final boolean deadlyAttack;
    // 攻击命中判定矩形（相对怪物位置，WZ 以朝左为基准；getRelativeRect 处理镜像）
    private final Rect range;
    // attack info 的 type（0 = 普通近战/区域，1 = 指向性，2 = 弹体等）
    private final int type;
    // attackAfter：出手到命中结算的延迟（ms），WZ 缺失时按动画帧延迟兜底
    private final int attackAfter;
    // 攻击元素属性位掩码（F=1, I=2, L=4, P=8, S=16, D=32）
    private final int elemAttr;

    public MobAttack(int skillId, int skillLevel, int conMp, int mpBurn, boolean magic, boolean deadlyAttack,
                     Rect range, int type, int attackAfter, int elemAttr) {
        this.skillId = skillId;
        this.skillLevel = skillLevel;
        this.conMp = conMp;
        this.mpBurn = mpBurn;
        this.magic = magic;
        this.deadlyAttack = deadlyAttack;
        this.range = range;
        this.type = type;
        this.attackAfter = attackAfter;
        this.elemAttr = elemAttr;
    }

    public int getSkillId() {
        return skillId;
    }

    public int getSkillLevel() {
        return skillLevel;
    }

    public int getConMp() {
        return conMp;
    }

    public int getMpBurn() {
        return mpBurn;
    }

    public boolean isMagic() {
        return magic;
    }

    public boolean isDeadlyAttack() {
        return deadlyAttack;
    }

    public Rect getRange() {
        return range;
    }

    public int getType() {
        return type;
    }

    public int getAttackAfter() {
        return attackAfter;
    }

    public int getElemAttr() {
        return elemAttr;
    }
}
