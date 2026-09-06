package kinoko.packet.world;

import kinoko.server.header.OutHeader;
import kinoko.server.packet.OutPacket;

import java.util.List;
import java.util.Map;

/**
 * 冒险学院（Family）服务端发包。
 * (matching reference: v95 CWvsContext::OnFamily* 系列回包线格式 —
 *   OnFamilyChartResult_A09700 / DecodeLocalChart_7B55A0 (out 98)、
 *   OnFamilyInfoResult_A0A190 (out 99)、OnFamilyResult_A0A230 (out 100)、
 *   OnFamilyJoinRequest_A0A6E0 (out 101)、OnFamilyJoinRequestResult_A0A870 (out 102)、
 *   OnFamilyJoinAccepted_A0A9F0 (out 103)、OnFamilyPrivilegeList_A0AAF0 (out 104)、
 *   OnFamilyFamousPointIncResult_A0ACB0 (out 105)、OnFamilyNotifyLoginOrLogout_A0AE60 (out 106)、
 *   OnFamilySetPrivilege_A0AF70 + Privilege::Decode_A09780 (out 107)、
 *   OnFamilySummonRequest_A0B0A0 (out 108))
 */
public final class FamilyPacket {

    // -----------------------------------------------------------------------
    // out 100 结果码（v95 OnFamilyResult_A0A230 String[] 索引对照）
    // -----------------------------------------------------------------------
    public static final int RESULT_UNREGISTER_DONE = 1;          // [4635] 已和%s诀别
    public static final int RESULT_REGISTER_INVALID = 0x40;      // [4614] 无法登录为同学的角色
    public static final int RESULT_TARGET_OFFLINE = 0x41;        // [4613] 角色不在线，或角色名不正确
    public static final int RESULT_SAME_FAMILY = 0x42;           // [4615] 是同一冒险学院
    public static final int RESULT_DIFFERENT_FAMILY = 0x43;      // [4616] 非同一冒险学院
    public static final int RESULT_NOT_SAME_MAP = 0x45;          // [4618] 只有在同一地图中的角色才能登录为同学
    public static final int RESULT_TARGET_HAS_PARENT = 0x46;     // [4619] 已经是其他角色的同学
    public static final int RESULT_TARGET_NOT_LOWER_LEVEL = 0x47;// [4620] 只能将比自己等级低的角色登录为同学
    public static final int RESULT_LEVEL_DIFF_TOO_BIG = 0x48;    // [4621] 等级差异超过20，无法登录为同学
    public static final int RESULT_INVITE_PENDING = 0x49;        // [4623] 正在邀请其他角色登录为同学
    public static final int RESULT_SUMMON_PENDING = 0x4A;        // [4624] 其他角色已请求召唤
    public static final int RESULT_SUMMON_FAILED = 0x4B;         // [4626] 召唤失败（chatlog）
    public static final int RESULT_FAMILY_LEVEL_RANGE = 0x4C;    // [4627] 冒险学院的规模上下不能超过1000级
    public static final int RESULT_TARGET_TOO_LOW_LEVEL = 0x4D;  // [4622] 只有10级以上的角色才能添加为同学
    public static final int RESULT_MIGRATION_APPLIED_ME = 0x4E;  // [5036] 您已申请更换世界
    public static final int RESULT_MIGRATION_APPLIED_TARGET = 0x4F; // [5037] 已申请更换世界的晚辈无法被添加
    public static final int RESULT_NOT_ENOUGH_GOLD_PARENT = 0x50; // [5252] 与前辈解除绑定需要 %d 金币 (dwParam=费用)
    public static final int RESULT_NOT_ENOUGH_GOLD_CHILD = 0x51;  // [5253] 与后辈解除绑定需要 %d 金币 (dwParam=费用)
    public static final int RESULT_LEVEL_MAP_MISMATCH = 0x52;     // [5466] 等级与对应区域不匹配

    // -----------------------------------------------------------------------
    // 特权类型（v95 WZ UI/UIWindow2.img/Family/RightIcon/type）
    //   0=移动到成员身边(SP_Jump) 1=召唤成员到身边(SP_Summon)
    //   2=掉落率Buff 3=经验值Buff 4=掉落率+经验值Buff
    // -----------------------------------------------------------------------
    public static final byte PRIV_JUMP = 0;
    public static final byte PRIV_SUMMON = 1;
    public static final byte PRIV_DROP_RATE = 2;
    public static final byte PRIV_EXP_RATE = 3;

    /**
     * 特权定义（服务端配置；v95 数值由服务端 WZ 提供，本项目按 v95 语义取整）。
     */
    public record FamilyPrivilege(byte type, int fameCost, int dayLimit, String name, String desc,
                                  int incExpRate, int incDropRate, long durationMs) {
    }

    /** 特权表（out 104 内容；索引即 nPrivilegeIndex）。 */
    public static final List<FamilyPrivilege> PRIVILEGES = List.of(
            new FamilyPrivilege(PRIV_JUMP, 10, 20, "移动到成员身边", "传送到同学院在线成员的所在地。", 0, 0, 0),
            new FamilyPrivilege(PRIV_SUMMON, 10, 20, "召唤成员到身边", "请求同学院在线成员立即传送到你的所在地。", 0, 0, 0),
            new FamilyPrivilege(PRIV_DROP_RATE, 50, 3, "掉落率提升", "60分钟内怪物掉落率提高10%。", 0, 10, 3_600_000L),
            new FamilyPrivilege(PRIV_EXP_RATE, 50, 3, "经验值提升", "60分钟内获得的经验值提高10%。", 10, 0, 3_600_000L)
    );

    /** 每个角色最多可登录的同学（下级）数量（v95 wChildLimit）。 */
    public static final short CHILD_LIMIT = 2;

    // -----------------------------------------------------------------------
    // out 98 FamilyChartResult — 家族树 (DecodeLocalChart_7B55A0)
    // -----------------------------------------------------------------------

    /** 家族树成员条目（out 98 列表项）。 */
    public record ChartMember(int characterId, int parentId, short job, byte level, boolean online,
                              int famousPoint, int totalFamousPoint, int todayParentPoint,
                              int todayGrandparentPoint, int channelId, int loginMin, String name) {
    }

    /**
     * 家族树结果。
     * (matching reference: DecodeLocalChart_7B55A0 —
     *   int dwMyID, int count, [count]成员, stat表(key:-1=总人数/0=本人上级数/<charId>=该成员下级数),
     *   特权使用次数表, short wChildLimit)
     */
    public static OutPacket chartResult(int ownerId, List<ChartMember> members,
                                        Map<Integer, Integer> statistics,
                                        Map<Integer, Integer> privilegeUse, short childLimit) {
        final OutPacket out = OutPacket.of(OutHeader.FamilyChartResult);
        out.encodeInt(ownerId); // dwMyID — m_bMine = (dwMyID == 本人charId)
        out.encodeInt(members.size());
        for (ChartMember m : members) {
            out.encodeInt(m.characterId());
            out.encodeInt(m.parentId());
            out.encodeShort(m.job());
            out.encodeByte(m.level());
            out.encodeByte(m.online());
            out.encodeInt(m.famousPoint());
            out.encodeInt(m.totalFamousPoint());
            out.encodeInt(m.todayParentPoint());
            out.encodeInt(m.todayGrandparentPoint());
            out.encodeInt(m.channelId());
            out.encodeInt(m.loginMin());
            out.encodeString(m.name());
        }
        out.encodeInt(statistics.size());
        for (Map.Entry<Integer, Integer> e : statistics.entrySet()) {
            out.encodeInt(e.getKey());
            out.encodeInt(e.getValue());
        }
        out.encodeInt(privilegeUse.size());
        for (Map.Entry<Integer, Integer> e : privilegeUse.entrySet()) {
            out.encodeInt(e.getKey()); // nPrivilegeIndex
            out.encodeInt(e.getValue()); // 使用次数
        }
        out.encodeShort(childLimit);
        return out;
    }

    // -----------------------------------------------------------------------
    // out 99 FamilyInfoResult — 本人家族信息 (OnFamilyInfoResult_A0A190)
    // -----------------------------------------------------------------------

    public static OutPacket infoResult(int famousPoint, int totalFamousPoint, int todaySavePoint,
                                       short childCount, short childLimit, short totalChildCount,
                                       int bossId, String familyName, String precept,
                                       Map<Integer, Integer> privilegeUse) {
        final OutPacket out = OutPacket.of(OutHeader.FamilyInfoResult);
        out.encodeInt(famousPoint); // nFamousPoint
        out.encodeInt(totalFamousPoint); // nTotalFamousPoint
        out.encodeInt(todaySavePoint); // nTodaySavePoint
        out.encodeShort(childCount); // wChildCount
        out.encodeShort(childLimit); // wChildLimit
        out.encodeShort(totalChildCount); // wTotalChildCount（含下级的下级）
        out.encodeInt(bossId); // dwBossID — 学院领袖（根节点）charId，0 = 无学院
        out.encodeString(familyName != null ? familyName : ""); // strFamilyName — 领袖角色名
        out.encodeString(precept != null ? precept : ""); // strPrecept
        out.encodeInt(privilegeUse.size());
        for (Map.Entry<Integer, Integer> e : privilegeUse.entrySet()) {
            out.encodeInt(e.getKey());
            out.encodeInt(e.getValue());
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // out 100 FamilyResult — 通用结果 (OnFamilyResult_A0A230)
    // -----------------------------------------------------------------------

    public static OutPacket result(int code, int param) {
        final OutPacket out = OutPacket.of(OutHeader.FamilyResult);
        out.encodeInt(code); // nResult
        out.encodeInt(param); // dwParam — 仅 0x50/0x51（金币费用）使用
        return out;
    }

    // -----------------------------------------------------------------------
    // out 101 FamilyJoinRequest — 同学登录邀请 (OnFamilyJoinRequest_A0A6E0)
    // -----------------------------------------------------------------------

    public static OutPacket joinRequest(int inviterId, int level, int jobCode, String inviterName) {
        final OutPacket out = OutPacket.of(OutHeader.FamilyJoinRequest);
        out.encodeInt(inviterId); // dwInviterID
        out.encodeInt(level); // nLevel
        out.encodeInt(jobCode); // nJobCode
        out.encodeString(inviterName); // strName
        return out;
    }

    // -----------------------------------------------------------------------
    // out 102 FamilyJoinRequestResult — 邀请结果 (OnFamilyJoinRequestResult_A0A870)
    // -----------------------------------------------------------------------

    public static OutPacket joinRequestResult(boolean success, String characterName) {
        final OutPacket out = OutPacket.of(OutHeader.FamilyJoinRequestResult);
        out.encodeByte(success); // bSuccess
        out.encodeString(characterName); // strCharacterName — 被邀请者名字
        return out;
    }

    // -----------------------------------------------------------------------
    // out 103 FamilyJoinAccepted — 加入成功 (OnFamilyJoinAccepted_A0A9F0)
    // -----------------------------------------------------------------------

    public static OutPacket joinAccepted(String characterName) {
        final OutPacket out = OutPacket.of(OutHeader.FamilyJoinAccepted);
        out.encodeString(characterName); // strCharacterName — 新老师名字
        return out;
    }

    // -----------------------------------------------------------------------
    // out 104 FamilyPrivilegeList — 特权列表 (OnFamilyPrivilegeList_A0AAF0)
    // -----------------------------------------------------------------------

    public static OutPacket privilegeList() {
        final OutPacket out = OutPacket.of(OutHeader.FamilyPrivilegeList);
        out.encodeInt(PRIVILEGES.size());
        for (FamilyPrivilege p : PRIVILEGES) {
            out.encodeByte(p.type()); // bType
            out.encodeInt(p.fameCost()); // nFame — 消耗名声度
            out.encodeInt(p.dayLimit()); // dwDayLimit — 每日使用上限
            out.encodeString(p.name()); // strName
            out.encodeString(p.desc()); // strDesc
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // out 105 FamilyFamousPointIncResult — 名声度变化 (OnFamilyFamousPointIncResult_A0ACB0)
    // -----------------------------------------------------------------------

    public static OutPacket famousPointInc(int delta, String characterName) {
        final OutPacket out = OutPacket.of(OutHeader.FamilyFamousPointIncResult);
        out.encodeInt(delta); // nDelta — 负=消费，正=累积
        out.encodeString(characterName); // strCharacterName
        return out;
    }

    // -----------------------------------------------------------------------
    // out 106 FamilyNotifyLoginOrLogout — 成员上下线 (OnFamilyNotifyLoginOrLogout_A0AE60)
    // -----------------------------------------------------------------------

    public static OutPacket notifyLoginOrLogout(boolean login, String characterName) {
        final OutPacket out = OutPacket.of(OutHeader.FamilyNotifyLoginOrLogout);
        out.encodeByte(login); // bLogin
        out.encodeString(characterName); // strCharacterName
        return out;
    }

    // -----------------------------------------------------------------------
    // out 107 FamilySetPrivilege — 特权生效 (OnFamilySetPrivilege_A0AF70 + Privilege::Decode_A09780)
    // -----------------------------------------------------------------------

    /**
     * 通知客户端家族 Buff 已生效（仅掉落率/经验值类特权）。
     * (matching reference: Privilege::Decode_A09780 — byte bType, int nIndex,
     *   int dwIncExpRate, int dwIncDropRate, time tEnd(byte bPast + int dwMs))
     */
    public static OutPacket setPrivilege(FamilyPrivilege privilege) {
        final OutPacket out = OutPacket.of(OutHeader.FamilySetPrivilege);
        out.encodeByte(privilege.type()); // bType
        if (privilege.type() != PRIV_JUMP) {
            out.encodeInt(PRIVILEGES.indexOf(privilege)); // nIndex
            out.encodeInt(privilege.incExpRate()); // dwIncExpRate — 百分比
            out.encodeInt(privilege.incDropRate()); // dwIncDropRate — 百分比
            out.encodeByte(false); // bPast = false → tEnd = now + dwMs
            out.encodeInt((int) privilege.durationMs()); // dwMs
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // out 108 FamilySummonRequest — 召唤请求 (OnFamilySummonRequest_A0B0A0)
    // -----------------------------------------------------------------------

    public static OutPacket summonRequest(String summonerName, String familyName) {
        final OutPacket out = OutPacket.of(OutHeader.FamilySummonRequest);
        out.encodeString(summonerName); // strCharacterName — 召唤者名字
        out.encodeString(familyName); // strFieldName — 学院名（领袖角色名）
        return out;
    }
}
