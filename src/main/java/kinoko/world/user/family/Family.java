package kinoko.world.user.family;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * 冒险学院（Family）单角色数据行。
 * (matching reference: v95 CUserLocal/CWvsContext 家族字段 — dwParentID / nFamousPoint /
 *   nTotalFamousPoint / nTodaySavePoint；kinoko 无对应实现，按 v95 语义重建)
 *
 * 树形结构：每个角色至多一个 parent（老师/前辈），parent_id=0 表示根节点（学院领袖 dwBossID）。
 * precept（口号）仅存储在根节点行上。
 */
public final class Family {
    private final int characterId;
    private int parentId; // 0 = 无上级（根节点）
    private int famousPoint;        // 当前可用名声度
    private int totalFamousPoint;   // 累计名声度
    private int todayParentPoint;   // 今日作为老师累积的名声度
    private int todayGrandparentPoint; // 今日作为二阶前辈累积的名声度
    private long pointResetDay;     // 日计数重置日期（epoch day，0 = 未初始化）
    private String precept;         // 学院口号（仅根节点行有意义）
    private final Map<Integer, Integer> privilegeUse = new HashMap<>(); // nPrivilegeIndex → 今日使用次数

    public Family(int characterId) {
        this.characterId = characterId;
        this.precept = "";
    }

    public int getCharacterId() {
        return characterId;
    }

    public int getParentId() {
        return parentId;
    }

    public void setParentId(int parentId) {
        this.parentId = parentId;
    }

    public boolean hasParent() {
        return parentId != 0;
    }

    public int getFamousPoint() {
        return famousPoint;
    }

    public void setFamousPoint(int famousPoint) {
        this.famousPoint = famousPoint;
    }

    public int getTotalFamousPoint() {
        return totalFamousPoint;
    }

    public void setTotalFamousPoint(int totalFamousPoint) {
        this.totalFamousPoint = totalFamousPoint;
    }

    public int getTodayParentPoint() {
        return todayParentPoint;
    }

    public void setTodayParentPoint(int todayParentPoint) {
        this.todayParentPoint = todayParentPoint;
    }

    public int getTodayGrandparentPoint() {
        return todayGrandparentPoint;
    }

    public void setTodayGrandparentPoint(int todayGrandparentPoint) {
        this.todayGrandparentPoint = todayGrandparentPoint;
    }

    public long getPointResetDay() {
        return pointResetDay;
    }

    public void setPointResetDay(long pointResetDay) {
        this.pointResetDay = pointResetDay;
    }

    public String getPrecept() {
        return precept;
    }

    public void setPrecept(String precept) {
        this.precept = precept != null ? precept : "";
    }

    /** 特权今日使用次数表（out 98/99 的 m_mPrivilege / m_FamilyInfo.mPrivilegeUse）。 */
    public Map<Integer, Integer> getPrivilegeUse() {
        return privilegeUse;
    }

    public int getPrivilegeUse(int privilegeIndex) {
        return privilegeUse.getOrDefault(privilegeIndex, 0);
    }

    /** (matching reference: v95 dwDayLimit — 特权每日使用上限，out 104 下发) */
    public void incrementPrivilegeUse(int privilegeIndex) {
        privilegeUse.merge(privilegeIndex, 1, Integer::sum);
    }

    // -----------------------------------------------------------------------
    // privilege_use 列序列化（"idx=count,idx=count"；空串 = 无记录）
    // -----------------------------------------------------------------------

    public static String encodePrivilegeUse(Map<Integer, Integer> privilegeUse) {
        if (privilegeUse.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : privilegeUse.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static void decodePrivilegeUse(String encoded, Map<Integer, Integer> target) {
        target.clear();
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        for (String pair : encoded.split(",")) {
            final int sep = pair.indexOf('=');
            if (sep <= 0) {
                continue;
            }
            try {
                target.put(Integer.parseInt(pair.substring(0, sep)), Integer.parseInt(pair.substring(sep + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /**
     * 跨天时清零日计数（今日老师/二阶前辈累积值）。
     * @return true 如果发生了重置
     */
    public boolean resetDailyPointsIfNeeded() {
        final long today = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
        if (pointResetDay == 0 || pointResetDay != today) {
            pointResetDay = today;
            todayParentPoint = 0;
            todayGrandparentPoint = 0;
            privilegeUse.clear(); // dwDayLimit 按日重置（v95 out 104 dayLimit 语义）
            return true;
        }
        return false;
    }

    /**
     * 扣除名声度（特权消耗/诀别费用），不为负。
     */
    public boolean consumeFame(int amount) {
        if (famousPoint < amount) return false;
        famousPoint -= amount;
        return true;
    }
}
