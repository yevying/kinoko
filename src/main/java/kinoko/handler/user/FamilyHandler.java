package kinoko.handler.user;

import kinoko.database.CharacterInfo;
import kinoko.database.DatabaseManager;
import kinoko.database.FamilyAccessor;
import kinoko.handler.Handler;
import kinoko.packet.world.FamilyPacket;
import kinoko.server.header.InHeader;
import kinoko.server.packet.InPacket;
import kinoko.server.user.RemoteUser;
import kinoko.world.field.Field;
import kinoko.world.user.CharacterData;
import kinoko.world.user.User;
import kinoko.world.user.family.Family;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 冒险学院（Family）客户端包处理。
 * (matching reference: v95 CWvsContext::OnPacket_9E5830 家族 opcode 169-177 分发 —
 *   SendFamilyChartRequest_A09D20 / SendFamilyInfoRequest_A09860 / SendRegisterJunior_A09DD0 /
 *   SendUnregisterJunior_A099E0 / SendUnregisterParent_A098D0 / SendFamilyInviteResult_A09C50 /
 *   SendUseFamilyPrivilege_A09EE0 / SendSetFamilyPrecept_A0A0D0；
 *   服务端规则按 v95 客户端语义（String[46xx] 提示文本）重建)
 */
public final class FamilyHandler {
    private static final Logger log = LogManager.getLogger(FamilyHandler.class);

    /** 家族树遍历上限（防御病态数据）。 */
    private static final int MAX_TREE_SIZE = 512;
    /** 向上追溯根节点的最大深度。 */
    private static final int MAX_TREE_DEPTH = 64;

    // -----------------------------------------------------------------------
    // in 169 FamilyChartRequest — 家族树请求 (v95 SendFamilyChartRequest_A09D20.c)
    // -----------------------------------------------------------------------

    @Handler(InHeader.FamilyChartRequest)
    public static void handleFamilyChartRequest(User user, InPacket inPacket) {
        final String targetName = inPacket.decodeString(); // strCharacterName — 按名字请求（可为他人）
        final Optional<CharacterInfo> infoResult = DatabaseManager.characterAccessor().getCharacterInfoByName(targetName);
        if (infoResult.isEmpty()) {
            log.warn("Family chart request for unknown character name : {}", targetName);
            return;
        }
        final int ownerId = infoResult.get().getCharacterId();

        // 家族树：上溯根节点后 BFS 向下（v95 DecodeLocalChart_7B55A0.c — 列表首项必须是领袖）
        final List<Family> members = loadFamilyTree(ownerId);

        // 解析各成员的等级/职业/名字
        final Map<Integer, CharacterData> charDataMap = new HashMap<>();
        final List<String> names = new ArrayList<>(members.size());
        for (Family m : members) {
            final Optional<CharacterData> cdResult = DatabaseManager.characterAccessor().getCharacterById(m.getCharacterId());
            if (cdResult.isEmpty()) {
                continue;
            }
            charDataMap.put(m.getCharacterId(), cdResult.get());
            names.add(cdResult.get().getCharacterName());
        }

        // 在线状态/频道经中央服务器查询（异步回调后组包）
        user.getConnectedServer().submitUserQueryRequest(names, (remoteUsers) -> {
            final Map<Integer, RemoteUser> onlineMap = new HashMap<>();
            for (RemoteUser ru : remoteUsers) {
                onlineMap.put(ru.getCharacterId(), ru);
            }

            // m_mStatistic：key -1=总人数 / 0=树主上级数（各级）/ <charId>=该成员下级数
            final Map<Integer, Integer> statistics = new LinkedHashMap<>();
            statistics.put(-1, members.size());
            statistics.put(0, countAncestors(ownerId));
            for (Family m : members) {
                int childCount = 0;
                for (Family other : members) {
                    if (other.getParentId() == m.getCharacterId()) {
                        childCount++;
                    }
                }
                statistics.put(m.getCharacterId(), childCount);
            }

            final List<FamilyPacket.ChartMember> chartMembers = new ArrayList<>();
            for (Family m : members) {
                final CharacterData cd = charDataMap.get(m.getCharacterId());
                if (cd == null) {
                    continue;
                }
                final RemoteUser ru = onlineMap.get(m.getCharacterId());
                chartMembers.add(new FamilyPacket.ChartMember(
                        m.getCharacterId(),
                        m.getParentId(),
                        (short) cd.getCharacterStat().getJob(),
                        (byte) cd.getCharacterStat().getLevel(),
                        ru != null,
                        m.getFamousPoint(),
                        m.getTotalFamousPoint(),
                        m.getTodayParentPoint(),
                        m.getTodayGrandparentPoint(),
                        ru != null ? ru.getChannelId() : -1, // v95 nChannelID<0 仅离线/现金商店态使用
                        0, // nLoginMin — 中央查询不携带登录时长（v95 CUserLocal m_nLoginTime），本项目送 0
                        cd.getCharacterName()));
            }

            final Family ownerFamily = members.stream()
                    .filter((m) -> m.getCharacterId() == ownerId)
                    .findFirst().orElse(null);
            user.write(FamilyPacket.chartResult(ownerId, chartMembers, statistics,
                    ownerFamily != null ? ownerFamily.getPrivilegeUse() : Map.of(), FamilyPacket.CHILD_LIMIT));
        });
    }

    // -----------------------------------------------------------------------
    // in 170 FamilyInfoRequest — 本人家族信息 (v95 SendFamilyInfoRequest_A09860.c)
    // -----------------------------------------------------------------------

    @Handler(InHeader.FamilyInfoRequest)
    public static void handleFamilyInfoRequest(User user, InPacket inPacket) {
        final int myId = user.getCharacterId();
        final FamilyAccessor fa = DatabaseManager.familyAccessor();
        final Optional<Family> myResult = fa.getFamilyByCharacterId(myId);
        final Family mine = myResult.orElseGet(() -> new Family(myId)); // 无行 = 独立角色，零值数据
        mine.resetDailyPointsIfNeeded();

        final List<Family> children = fa.getFamiliesByParentId(myId);

        // wTotalChildCount — 含下级的下级（v95 Decode_A09B10.c）
        int totalDescendants = 0;
        final Deque<Integer> queue = new ArrayDeque<>();
        for (Family child : children) {
            if (++totalDescendants >= MAX_TREE_SIZE) {
                break;
            }
            queue.add(child.getCharacterId());
        }
        while (!queue.isEmpty() && totalDescendants < MAX_TREE_SIZE) {
            for (Family descendant : fa.getFamiliesByParentId(queue.poll())) {
                if (++totalDescendants >= MAX_TREE_SIZE) {
                    break;
                }
                queue.add(descendant.getCharacterId());
            }
        }

        // nTodaySavePoint — 今日作为老师/二阶前辈累积的名声度（v95 String[4645]）
        int todaySavePoint = 0;
        for (Family child : children) {
            child.resetDailyPointsIfNeeded(); // 仅内存修正显示值，不落库
            todaySavePoint += child.getTodayParentPoint();
            for (Family grandchild : fa.getFamiliesByParentId(child.getCharacterId())) {
                grandchild.resetDailyPointsIfNeeded();
                todaySavePoint += grandchild.getTodayGrandparentPoint();
            }
        }

        // dwBossID — 学院领袖（根节点）；strFamilyName = 领袖角色名（v95 "%s冒险学院" String[4610]）
        int bossId = myId;
        Optional<Family> current = myResult;
        for (int depth = 0; depth < MAX_TREE_DEPTH && current.isPresent() && current.get().hasParent(); depth++) {
            bossId = current.get().getParentId();
            current = fa.getFamilyByCharacterId(bossId);
        }
        final String familyName = DatabaseManager.characterAccessor().getCharacterById(bossId)
                .map(CharacterData::getCharacterName).orElse("");

        user.write(FamilyPacket.infoResult(
                mine.getFamousPoint(),
                mine.getTotalFamousPoint(),
                todaySavePoint,
                (short) children.size(),
                FamilyPacket.CHILD_LIMIT,
                (short) totalDescendants,
                bossId,
                familyName,
                mine.getPrecept(),
                mine.getPrivilegeUse()));
    }

    // -----------------------------------------------------------------------
    // in 171 FamilyRegisterJunior — 登录同学邀请 (v95 SendRegisterJunior_A09DD0.c)
    // -----------------------------------------------------------------------

    @Handler(InHeader.FamilyRegisterJunior)
    public static void handleFamilyRegisterJunior(User user, InPacket inPacket) {
        final String targetName = inPacket.decodeString(); // strTargetName
        if (targetName.equals(user.getCharacterName())) {
            user.write(FamilyPacket.result(FamilyPacket.RESULT_REGISTER_INVALID, 0)); // [4614] 无法登录为同学的角色
            return;
        }
        final Optional<CharacterInfo> infoResult = DatabaseManager.characterAccessor().getCharacterInfoByName(targetName);
        if (infoResult.isEmpty()) {
            user.write(FamilyPacket.result(FamilyPacket.RESULT_TARGET_OFFLINE, 0)); // [4613] 角色不在线，或角色名不正确
            return;
        }
        final int targetId = infoResult.get().getCharacterId();

        // 在线 + 同频道 + 同地图（v95 String[4618] "必须在同一频道的同一地图中"）
        user.getConnectedServer().submitUserQueryRequest(List.of(targetName), (remoteUsers) -> {
            if (remoteUsers.isEmpty()) {
                user.write(FamilyPacket.result(FamilyPacket.RESULT_TARGET_OFFLINE, 0)); // [4613]
                return;
            }
            final RemoteUser target = remoteUsers.get(0);
            if (target.getChannelId() != user.getChannelId() || target.getFieldId() != user.getFieldId()) {
                user.write(FamilyPacket.result(FamilyPacket.RESULT_NOT_SAME_MAP, 0)); // [4618]
                return;
            }

            final FamilyAccessor fa = DatabaseManager.familyAccessor();
            if (fa.getFamilyByCharacterId(targetId).map(Family::hasParent).orElse(false)) {
                user.write(FamilyPacket.result(FamilyPacket.RESULT_TARGET_HAS_PARENT, 0)); // [4619] 已经是其他角色的同学
                return;
            }
            if (user.getFamilyPendingInvite() != 0) {
                user.write(FamilyPacket.result(FamilyPacket.RESULT_INVITE_PENDING, 0)); // [4623] 正在邀请其他角色登录为同学
                return;
            }
            if (target.getLevel() < 10) {
                user.write(FamilyPacket.result(FamilyPacket.RESULT_TARGET_TOO_LOW_LEVEL, 0)); // [4622] 只有10级以上的角色才能添加为同学
                return;
            }
            // 同学格已满 — 客户端已禁用 BtRegister（String[4628]），线上无对应结果码，静默拒绝
            if (fa.getFamiliesByParentId(user.getCharacterId()).size() >= FamilyPacket.CHILD_LIMIT) {
                log.warn("Family register rejected : child limit reached for character ID {}", user.getCharacterId());
                return;
            }
            if (target.getLevel() >= user.getLevel()) {
                user.write(FamilyPacket.result(FamilyPacket.RESULT_TARGET_NOT_LOWER_LEVEL, 0)); // [4620] 只能将比自己等级低的角色登录为同学
                return;
            }
            if (user.getLevel() - target.getLevel() > 20) {
                user.write(FamilyPacket.result(FamilyPacket.RESULT_LEVEL_DIFF_TOO_BIG, 0)); // [4621] 等级差异超过20，无法登录为同学
                return;
            }

            // 向目标发出邀请（v95 out 101 → CUIFadeYesNo type 12 CreateFamilyInvite_5292E0.c）
            user.setFamilyPendingInvite(targetId);
            user.getConnectedServer().submitUserPacketReceive(targetId, FamilyPacket.joinRequest(
                    user.getCharacterId(), user.getLevel(), user.getJob(), user.getCharacterName()));
        });
    }

    // -----------------------------------------------------------------------
    // in 172 FamilyUnregisterJunior — 与后辈诀别 (v95 SendUnregisterJunior_A099E0.c)
    // -----------------------------------------------------------------------

    @Handler(InHeader.FamilyUnregisterJunior)
    public static void handleFamilyUnregisterJunior(User user, InPacket inPacket) {
        final int childId = inPacket.decodeInt(); // dwCharacterID — 要诀别的同学
        final FamilyAccessor fa = DatabaseManager.familyAccessor();
        final Optional<Family> myResult = fa.getFamilyByCharacterId(user.getCharacterId());
        if (myResult.isEmpty()) {
            user.write(FamilyPacket.result(FamilyPacket.RESULT_DIFFERENT_FAMILY, 0)); // [4616] 非同一冒险学院
            return;
        }
        final Family mine = myResult.get();
        mine.resetDailyPointsIfNeeded();

        final Optional<Family> childResult = fa.getFamiliesByParentId(user.getCharacterId()).stream()
                .filter((f) -> f.getCharacterId() == childId)
                .findFirst();
        if (childResult.isEmpty()) {
            user.write(FamilyPacket.result(FamilyPacket.RESULT_DIFFERENT_FAMILY, 0)); // [4616]
            return;
        }
        final Family child = childResult.get();

        // 诀别费用（v95 CUIFamilyChart::_UnregisterChild__7B3A60.c）：x = L*(L/20+10)，L=同学等级
        final int childLevel = DatabaseManager.characterAccessor().getCharacterById(childId)
                .map((cd) -> cd.getCharacterStat().getLevel()).orElse((short) 0);
        final int fameCost = childLevel * (childLevel / 20 + 10);
        // 等级差金币费用：diff*(diff+2500)（v95 String[5254]）
        final int levelDiff = Math.abs(user.getLevel() - childLevel);
        final int goldCost = levelDiff > 0 ? levelDiff * (levelDiff + 2500) : 0;
        if (goldCost > 0 && user.getInventoryManager().getMoney() < goldCost) {
            user.write(FamilyPacket.result(FamilyPacket.RESULT_NOT_ENOUGH_GOLD_CHILD, goldCost)); // [5253] 与后辈解除绑定需要 %d 金币
            return;
        }

        // 名声度扣除：本人 -2x；若本人有前辈，前辈再 -x（v95 String[5255]/[5256]）
        deductFame(mine, fameCost * 2);
        Family parent = null;
        if (mine.hasParent()) {
            parent = fa.getFamilyByCharacterId(mine.getParentId()).orElse(null);
            if (parent != null) {
                deductFame(parent, fameCost);
            }
        }

        // 同学和其下级成员全部退出本学院、组成新学院（v95 String[4648]）— 子树随同学迁移
        child.setParentId(0);
        fa.saveFamily(child, true);
        fa.saveFamily(mine, true);
        if (parent != null) {
            fa.saveFamily(parent, true);
        }

        user.write(FamilyPacket.result(FamilyPacket.RESULT_UNREGISTER_DONE, 0)); // [4635] 已和%s诀别
    }

    // -----------------------------------------------------------------------
    // in 173 FamilyUnregisterParent — 与前辈诀别 (v95 SendUnregisterParent_A098D0.c)
    // -----------------------------------------------------------------------

    @Handler(InHeader.FamilyUnregisterParent)
    public static void handleFamilyUnregisterParent(User user, InPacket inPacket) {
        final FamilyAccessor fa = DatabaseManager.familyAccessor();
        final Optional<Family> myResult = fa.getFamilyByCharacterId(user.getCharacterId());
        if (myResult.isEmpty() || !myResult.get().hasParent()) {
            user.write(FamilyPacket.result(FamilyPacket.RESULT_DIFFERENT_FAMILY, 0)); // [4616] 非同一冒险学院
            return;
        }
        final Family mine = myResult.get();
        mine.resetDailyPointsIfNeeded();

        final int parentId = mine.getParentId();
        final Optional<Family> parentResult = fa.getFamilyByCharacterId(parentId);
        if (parentResult.isEmpty()) {
            log.error("Dangling family parent reference : character {} -> parent {}", user.getCharacterId(), parentId);
            mine.setParentId(0); // 数据不一致 — 清除悬空引用
            fa.saveFamily(mine, true);
            return;
        }
        final Family parent = parentResult.get();

        // 诀别费用（v95 CUIFamilyChart::_UnregisterParent__7B3D30.c）：x = L*(L/20+10)，L=本人等级
        final int myLevel = user.getLevel();
        final int parentLevel = DatabaseManager.characterAccessor().getCharacterById(parentId)
                .map((cd) -> cd.getCharacterStat().getLevel()).orElse((short) 0);
        final int fameCost = myLevel * (myLevel / 20 + 10);
        final int levelDiff = Math.abs(myLevel - parentLevel);
        final int goldCost = levelDiff > 0 ? levelDiff * (levelDiff + 2500) : 0;
        if (goldCost > 0 && user.getInventoryManager().getMoney() < goldCost) {
            user.write(FamilyPacket.result(FamilyPacket.RESULT_NOT_ENOUGH_GOLD_PARENT, goldCost)); // [5252] 与前辈解除绑定需要 %d 金币
            return;
        }

        // 名声度扣除：前辈 -2x；若存在二阶前辈，再 -x（v95 String[5257]/[5258]）
        deductFame(parent, fameCost * 2);
        Family grandparent = null;
        if (parent.hasParent()) {
            grandparent = fa.getFamilyByCharacterId(parent.getParentId()).orElse(null);
            if (grandparent != null) {
                deductFame(grandparent, fameCost);
            }
        }

        mine.setParentId(0);
        fa.saveFamily(mine, true);
        fa.saveFamily(parent, true);
        if (grandparent != null) {
            fa.saveFamily(grandparent, true);
        }

        user.write(FamilyPacket.result(FamilyPacket.RESULT_UNREGISTER_DONE, 0)); // [4635] 已和%s诀别
    }

    // -----------------------------------------------------------------------
    // in 174 FamilyJoinResult — 同学登录邀请答复 (v95 SendFamilyInviteResult_A09C50.c)
    // -----------------------------------------------------------------------

    @Handler(InHeader.FamilyJoinResult)
    public static void handleFamilyJoinResult(User user, InPacket inPacket) {
        final int inviterId = inPacket.decodeInt(); // dwInviterID
        inPacket.decodeString(); // strInviterName — 客户端回显，服务端以自身记录为准
        final boolean accept = inPacket.decodeBoolean(); // bAccept

        if (user.getFamilyPendingInvite() != inviterId) {
            log.warn("Stale family invite reply from {} to {}", user.getCharacterId(), inviterId);
            return;
        }
        user.setFamilyPendingInvite(0);

        final FamilyAccessor fa = DatabaseManager.familyAccessor();
        if (!accept) {
            // 拒绝（v95 String[4631] "%s拒绝登录为同学。"）
            user.getConnectedServer().submitUserPacketReceive(inviterId,
                    FamilyPacket.joinRequestResult(false, user.getCharacterName()));
            return;
        }

        // 接受时重新校验（等待期间状态可能变化）
        if (fa.getFamilyByCharacterId(user.getCharacterId()).map(Family::hasParent).orElse(false)) {
            log.warn("Family join accepted but character {} already has a parent", user.getCharacterId());
            return;
        }
        if (fa.getFamiliesByParentId(inviterId).size() >= FamilyPacket.CHILD_LIMIT) {
            user.getConnectedServer().submitUserPacketReceive(inviterId,
                    FamilyPacket.joinRequestResult(false, user.getCharacterName()));
            return;
        }

        // 建立同学关系：本人成为邀请者的同学（v95 out 102/103）
        final Family mine = fa.getFamilyByCharacterId(user.getCharacterId())
                .orElseGet(() -> new Family(user.getCharacterId()));
        mine.setParentId(inviterId);
        if (!fa.saveFamily(mine, true)) {
            log.error("Failed to save family row for character ID : {}", user.getCharacterId());
            return;
        }

        final String inviterName = DatabaseManager.characterAccessor().getCharacterById(inviterId)
                .map(CharacterData::getCharacterName).orElse("");
        user.getConnectedServer().submitUserPacketReceive(inviterId,
                FamilyPacket.joinRequestResult(true, user.getCharacterName())); // [4632] 已将%s登录为同学
        user.write(FamilyPacket.joinAccepted(inviterName)); // [4633] %s成为了你的老师
        notifyFamilyLoginOrLogout(user, true); // out 106 — 新成员上线通知（客户端按名字刷新图表在线态）
    }

    // -----------------------------------------------------------------------
    // in 175 FamilyUsePrivilege — 使用学院特权 (v95 SendUseFamilyPrivilege_A09EE0.c)
    // -----------------------------------------------------------------------

    @Handler(InHeader.FamilyUsePrivilege)
    public static void handleFamilyUsePrivilege(User user, InPacket inPacket) {
        final int privilegeIndex = inPacket.decodeInt(); // nPrivilegeIndex
        if (privilegeIndex < 0 || privilegeIndex >= FamilyPacket.PRIVILEGES.size()) {
            log.warn("Invalid family privilege index {} from character {}", privilegeIndex, user.getCharacterId());
            return;
        }
        final FamilyPacket.FamilyPrivilege privilege = FamilyPacket.PRIVILEGES.get(privilegeIndex);

        // 移动/召唤类特权携带目标名字（v95: bType <= SP_Summon）
        String targetName = null;
        if (privilege.type() <= FamilyPacket.PRIV_SUMMON) {
            targetName = inPacket.decodeString(); // strTargetName
        }

        final FamilyAccessor fa = DatabaseManager.familyAccessor();
        final Optional<Family> myResult = fa.getFamilyByCharacterId(user.getCharacterId());
        if (myResult.isEmpty()) {
            log.warn("Family privilege used without family row : character {}", user.getCharacterId());
            return; // 客户端 BtSpecial 已按名声度/次数禁用，此处为兜底
        }
        final Family mine = myResult.get();
        mine.resetDailyPointsIfNeeded();

        if (mine.getPrivilegeUse(privilegeIndex) >= privilege.dayLimit()) {
            log.warn("Family privilege day limit reached : character {} index {}", user.getCharacterId(), privilegeIndex);
            return; // 客户端已按 useCount<dwDayLimit 禁用，此处为兜底
        }
        if (!mine.consumeFame(privilege.fameCost())) {
            log.warn("Insufficient famous point for family privilege : character {} index {}", user.getCharacterId(), privilegeIndex);
            return; // 客户端已按 nFamousPoint>=cost 禁用，此处为兜底
        }

        mine.incrementPrivilegeUse(privilegeIndex);
        fa.saveFamily(mine, true);
        user.write(FamilyPacket.famousPointInc(-privilege.fameCost(), user.getCharacterName())); // [4637] 消费了%d的名声度

        switch (privilege.type()) {
            case FamilyPacket.PRIV_JUMP -> handleJump(user, targetName); // 移动到成员身边 — 本人传送到目标处
            case FamilyPacket.PRIV_SUMMON -> handleSummon(user, targetName); // 召唤成员到身边 — out 108 YesNo
            default -> user.write(FamilyPacket.setPrivilege(privilege)); // 掉落率/经验 Buff（v95 out 107 → 客户端临时属性效果 type 4）
        }
    }

    /** SP_Jump — 本人传送到目标成员所在地。 (v95 RightIcon type 0 "Move to the location of another character") */
    private static void handleJump(User user, String targetName) {
        final Optional<User> targetResult = resolveFamilyMember(user, targetName);
        if (targetResult.isEmpty()) {
            user.write(FamilyPacket.result(FamilyPacket.RESULT_SUMMON_FAILED, 0)); // [4626] 召唤失败。该地区无法召唤...
            return;
        }
        final User target = targetResult.get();
        final Field field = target.getField();
        if (field == null) {
            user.write(FamilyPacket.result(FamilyPacket.RESULT_SUMMON_FAILED, 0)); // [4626]
            return;
        }
        user.warp(field, target.getX(), target.getY(), 0, false, false);
    }

    /** SP_Summon — 请求目标成员传送到本人所在地。 (v95 RightIcon type 1 + OnFamilySummonRequest_A0B0A0.c) */
    private static void handleSummon(User user, String targetName) {
        final Optional<User> targetResult = resolveFamilyMember(user, targetName);
        if (targetResult.isEmpty()) {
            user.write(FamilyPacket.result(FamilyPacket.RESULT_SUMMON_FAILED, 0)); // [4626]
            return;
        }
        final User target = targetResult.get();
        if (target.getFamilySummonRequestedBy() != 0) {
            user.write(FamilyPacket.result(FamilyPacket.RESULT_SUMMON_PENDING, 0)); // [4624] 其他角色已请求召唤
            return;
        }

        target.setFamilySummonRequestedBy(user.getCharacterId());
        final String familyName = getFamilyName(user); // strFieldName — 学院名（领袖角色名）
        target.write(FamilyPacket.summonRequest(user.getCharacterName(), familyName)); // [4641] %s的%s请求召唤你
    }

    /** 解析同学院的在线成员（本项目仅支持同频道目标）。 */
    private static Optional<User> resolveFamilyMember(User user, String targetName) {
        if (targetName == null || targetName.isEmpty()) {
            return Optional.empty();
        }
        final Optional<CharacterInfo> infoResult = DatabaseManager.characterAccessor().getCharacterInfoByName(targetName);
        if (infoResult.isEmpty()) {
            return Optional.empty();
        }
        final int targetId = infoResult.get().getCharacterId();
        // 必须同学院（v95 String[4616] "非同一冒险学院"）
        if (!isSameFamily(user.getCharacterId(), targetId)) {
            return Optional.empty();
        }
        return user.getConnectedServer().getUserByCharacterId(targetId);
    }

    // -----------------------------------------------------------------------
    // in 176 FamilySetPrecept — 设置学院口号 (v95 SendSetFamilyPrecept_A0A0D0.c)
    // -----------------------------------------------------------------------

    @Handler(InHeader.FamilySetPrecept)
    public static void handleFamilySetPrecept(User user, InPacket inPacket) {
        final String precept = inPacket.decodeString(); // strPrecept — 客户端 maxlen 200
        final FamilyAccessor fa = DatabaseManager.familyAccessor();
        final Optional<Family> myResult = fa.getFamilyByCharacterId(user.getCharacterId());
        if (myResult.isPresent() && myResult.get().hasParent()) {
            log.warn("Non-leader tried to set family precept : character {}", user.getCharacterId());
            return; // 仅学院领袖可设置（客户端 BtFamilyPrecept enabled iff dwBossID==本人）
        }
        final Family mine = myResult.orElseGet(() -> new Family(user.getCharacterId()));
        mine.setPrecept(precept.length() > 200 ? precept.substring(0, 200) : precept);
        fa.saveFamily(mine, true);
    }

    // -----------------------------------------------------------------------
    // in 177 FamilySummonResult — 召唤请求答复 (v95 OnFamilySummonRequest_A0B0A0.c 自动回包)
    // -----------------------------------------------------------------------

    @Handler(InHeader.FamilySummonResult)
    public static void handleFamilySummonResult(User user, InPacket inPacket) {
        final String summonerName = inPacket.decodeString(); // strCharacterName — 召唤者名字
        final boolean accept = inPacket.decodeBoolean(); // bAccept

        final int pendingFrom = user.getFamilySummonRequestedBy();
        if (pendingFrom == 0) {
            log.warn("Stale family summon reply from character {}", user.getCharacterId());
            return;
        }
        user.setFamilySummonRequestedBy(0);

        // 校验召唤者身份
        final Optional<CharacterInfo> infoResult = DatabaseManager.characterAccessor().getCharacterInfoByName(summonerName);
        if (infoResult.isEmpty() || infoResult.get().getCharacterId() != pendingFrom) {
            log.warn("Family summon reply name mismatch : character {} expected {}", user.getCharacterId(), pendingFrom);
            return;
        }

        if (!accept) {
            return; // 拒绝 — v95 客户端仅关闭对话框，无回包
        }

        final Optional<User> summonerResult = user.getConnectedServer().getUserByCharacterId(pendingFrom);
        if (summonerResult.isEmpty()) {
            return; // 召唤者已离线
        }
        final User summoner = summonerResult.get();
        final Field field = summoner.getField();
        if (field == null) {
            return;
        }
        user.warp(field, summoner.getX(), summoner.getY(), 0, false, false);
    }

    // -----------------------------------------------------------------------
    // 登录/升级钩子（由 User / MigrationHandler 调用）
    // -----------------------------------------------------------------------

    /**
     * 登录时初始化家族数据。
     * (matching reference: v95 out 104 OnFamilyPrivilegeList_A0AAF0.c — 特权列表替换 m_apPrivilege；
     *   out 106 OnFamilyNotifyLoginOrLogout_A0AE60.c — 成员上线通知)
     */
    public static void onUserLogin(User user) {
        // 特权列表为静态配置，对所有角色下发（主窗口特权区依赖）
        user.write(FamilyPacket.privilegeList());
        notifyFamilyLoginOrLogout(user, true);
    }

    /** 登出时通知家族成员下线。 (v95 out 106 bLogin=0) */
    public static void onUserLogout(User user) {
        notifyFamilyLoginOrLogout(user, false);
    }

    /**
     * 同学升级时的名声度累积。
     * (matching reference: v95 String[4645] "自己登录的同学获得经验值或升级时，自己的名声度也会随之上升" —
     *   本项目仅在升级这一离散事件上累积（gain=新等级）；
     *   减半规则见 String[4652]/[4653] "超越老师的等级后，名声度的累积值会减半")
     */
    public static void onCharacterLevelUp(User user) {
        final FamilyAccessor fa = DatabaseManager.familyAccessor();
        final Optional<Family> myResult = fa.getFamilyByCharacterId(user.getCharacterId());
        if (myResult.isEmpty()) {
            return; // 无前辈 — 不累积名声度
        }
        final Family mine = myResult.get();
        mine.resetDailyPointsIfNeeded();

        final int parentId = mine.getParentId();
        final Optional<Family> parentResult = fa.getFamilyByCharacterId(parentId);
        if (parentResult.isEmpty()) {
            log.error("Dangling family parent reference : character {} -> parent {}", user.getCharacterId(), parentId);
            mine.setParentId(0); // 数据不一致 — 清除悬空引用
            fa.saveFamily(mine, true);
            return;
        }
        final Family parent = parentResult.get();

        int gain = user.getLevel(); // 升级名声度 = 新等级（v95 "提供的帮助越多，上升的就越快" — 本项目按等级近似）
        final int parentLevel = DatabaseManager.characterAccessor().getCharacterById(parentId)
                .map((cd) -> cd.getCharacterStat().getLevel()).orElse((short) 0);
        if (user.getLevel() > parentLevel) {
            gain /= 2; // 超越老师等级后累积减半（v95 String[4652]）
        }

        mine.setTodayParentPoint(mine.getTodayParentPoint() + gain);
        fa.saveFamily(mine, true);

        parent.setFamousPoint(parent.getFamousPoint() + gain);
        parent.setTotalFamousPoint(parent.getTotalFamousPoint() + gain);
        fa.saveFamily(parent, true);
        user.getConnectedServer().submitUserPacketReceive(parentId,
                FamilyPacket.famousPointInc(gain, user.getCharacterName())); // [4636] 名声度提高了(%s, +%d)

        // 二阶前辈份额（v95 nTodayGrandParentPoint）
        final int grandparentGain = gain / 2;
        if (grandparentGain > 0 && parent.hasParent()) {
            mine.setTodayGrandparentPoint(mine.getTodayGrandparentPoint() + grandparentGain);
            fa.saveFamily(mine, true);
            final Optional<Family> gpResult = fa.getFamilyByCharacterId(parent.getParentId());
            if (gpResult.isPresent()) {
                final Family grandparent = gpResult.get();
                grandparent.setFamousPoint(grandparent.getFamousPoint() + grandparentGain);
                grandparent.setTotalFamousPoint(grandparent.getTotalFamousPoint() + grandparentGain);
                fa.saveFamily(grandparent, true);
                user.getConnectedServer().submitUserPacketReceive(parent.getParentId(),
                        FamilyPacket.famousPointInc(grandparentGain, user.getCharacterName())); // [4636]
            }
        }
    }

    // -----------------------------------------------------------------------
    // 内部工具
    // -----------------------------------------------------------------------

    /** 通知直接前辈与同学上下线。 (v95 out 106 — 客户端按名字匹配刷新图表槽位在线态) */
    private static void notifyFamilyLoginOrLogout(User user, boolean login) {
        final FamilyAccessor fa = DatabaseManager.familyAccessor();
        final Optional<Family> myResult = fa.getFamilyByCharacterId(user.getCharacterId());
        if (myResult.isEmpty()) {
            return;
        }
        final Family mine = myResult.get();
        final List<Integer> ids = new ArrayList<>();
        if (mine.hasParent()) {
            ids.add(mine.getParentId());
        }
        for (Family child : fa.getFamiliesByParentId(user.getCharacterId())) {
            ids.add(child.getCharacterId());
        }
        if (!ids.isEmpty()) {
            user.getConnectedServer().submitUserPacketBroadcast(ids,
                    FamilyPacket.notifyLoginOrLogout(login, user.getCharacterName()));
        }
    }

    /** 加载以 ownerId 所在树根为领袖的完整家族树（BFS，首项=领袖）。无数据时返回仅含 stub 的单成员列表。 */
    private static List<Family> loadFamilyTree(int ownerId) {
        final FamilyAccessor fa = DatabaseManager.familyAccessor();

        // 上溯根节点（v95 dwBossID — 学院领袖/树根）
        int rootId = findRoot(ownerId);

        final List<Family> members = new ArrayList<>();
        fa.getFamilyByCharacterId(rootId).ifPresent(members::add);
        final Deque<Integer> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty() && members.size() < MAX_TREE_SIZE) {
            for (Family child : fa.getFamiliesByParentId(queue.poll())) {
                if (members.size() >= MAX_TREE_SIZE) {
                    break;
                }
                members.add(child);
                queue.add(child.getCharacterId());
            }
        }

        // 无行且无下级 — 独立角色，构造零值 stub（v95 客户端对缺失 pMine 亦有本地 stub 逻辑）
        if (members.isEmpty()) {
            members.add(new Family(rootId));
        }
        return members;
    }

    /** 上溯家族树根节点 charId。无行 = 自身即根。 */
    private static int findRoot(int characterId) {
        final FamilyAccessor fa = DatabaseManager.familyAccessor();
        int current = characterId;
        Optional<Family> row = fa.getFamilyByCharacterId(current);
        for (int depth = 0; depth < MAX_TREE_DEPTH && row.isPresent() && row.get().hasParent(); depth++) {
            current = row.get().getParentId();
            row = fa.getFamilyByCharacterId(current);
        }
        return current;
    }

    /** 统计角色的上级数量（各级）。 */
    private static int countAncestors(int characterId) {
        final FamilyAccessor fa = DatabaseManager.familyAccessor();
        int count = 0;
        Optional<Family> row = fa.getFamilyByCharacterId(characterId);
        for (int depth = 0; depth < MAX_TREE_DEPTH && row.isPresent() && row.get().hasParent(); depth++) {
            count++;
            row = fa.getFamilyByCharacterId(row.get().getParentId());
        }
        return count;
    }

    /** 两角色是否同属一个冒险学院（同一树根）。 */
    private static boolean isSameFamily(int idA, int idB) {
        if (idA == idB) {
            return true;
        }
        return findRoot(idA) == findRoot(idB);
    }

    /** 学院名 = 领袖角色名（v95 strFamilyName / "%s冒险学院" String[4610]）。 */
    private static String getFamilyName(User user) {
        final int rootId = findRoot(user.getCharacterId());
        if (rootId == user.getCharacterId()) {
            return user.getCharacterName();
        }
        return DatabaseManager.characterAccessor().getCharacterById(rootId)
                .map(CharacterData::getCharacterName).orElse("");
    }

    /** 扣除名声度（不为负 — v95 诀别提示无条件声明扣减值，无不足结果码）。 */
    private static void deductFame(Family family, int amount) {
        if (amount <= 0) {
            return;
        }
        family.setFamousPoint(Math.max(0, family.getFamousPoint() - amount));
    }
}
