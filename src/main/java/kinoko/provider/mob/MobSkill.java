package kinoko.provider.mob;

import java.util.Objects;

public final class MobSkill {
    private final MobSkillType skillType;
    private final int skillId;
    private final int skillLevel;
    // info/skill 条目的 action 字段：技能动画索引（1..15 → skill1..skill15）
    private final int action;

    public MobSkill(MobSkillType skillType, int skillId, int skillLevel, int action) {
        this.skillType = skillType;
        this.skillId = skillId;
        this.skillLevel = skillLevel;
        this.action = action;
    }

    public int getAction() {
        return action;
    }

    public MobSkillType getSkillType() {
        return skillType;
    }

    public int getSkillId() {
        return skillId;
    }

    public int getSkillLevel() {
        return skillLevel;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MobSkill mobSkill = (MobSkill) o;
        return skillId == mobSkill.skillId && skillLevel == mobSkill.skillLevel && skillType == mobSkill.skillType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(skillType, skillId, skillLevel);
    }

    @Override
    public String toString() {
        return "MobSkill{" +
                "skillType=" + skillType +
                ", skillId=" + skillId +
                ", skillLevel=" + skillLevel +
                '}';
    }
}
