package kinoko.world.user;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 聊天关键词 → 宠物回话（“关键词触发 + 宠物回话”双轨聊天互动）。
 * <p>
 * 玩家在聊天窗口输入包含宠物关键词的普通话语时，服务器在正常广播该聊天之余，
 * 额外广播一条 PetAction 让宠物播放动作并弹回话气泡（含发送者，便于本人也看到）。
 * 本类只负责关键词匹配 + 每玩家冷却；宠物动作/气泡渲染由客户端既有链路完成
 * （OnRemotePetAction → PetNode.PlayAction → ShowChatBalloon）。
 */
public final class PetChatResponder {
    private PetChatResponder() {
    }

    /**
     * 关键词 → (回话文本, 动作号 nAction)。
     * 动作号沿用客户端 PetData.GetActionName 的一次性动作表约定：
     *   sit=21, chat=22, angry=23, stunned=24, cry=25, eat=26, love=27, sleep=28, rise=29, hand=30
     * 客户端会在 WZ 缺失该一次性动作时优雅退化为仅弹气泡；若宠物无任何这些动作，
     * 仍以 rest(stand0)=1 兜底保证能切换姿态。
     */
    public static final class PetChatResponse {
        public final String reply;
        public final int action;

        private PetChatResponse(String reply, int action) {
            this.reply = reply;
            this.action = action;
        }
    }

    private static final Map<String, PetChatResponse> KEYWORD_REPLY = new LinkedHashMap<>();

    /** 每玩家回话冷却（ms），避免聊天里高频常见词触发刷屏。 */
    private static final long COOLDOWN_MS = 3000L;
    /** 每玩家上次回话时刻；AtomicLong 使“检查冷却 + 预留”原子化，杜绝并发重复回话。 */
    private static final ConcurrentHashMap<Integer, AtomicLong> LAST_REPLY_TIME = new ConcurrentHashMap<>();

    static {
        // 注意：本项目网络字符串为 UTF-8 协议（[2 字节小端长度][UTF-8]）。回话文本仍保持
        // 纯 ASCII 英文——Godot 客户端按 UTF-8 解码可显示中文，但原版 095 客户端按 EUC-KR
        // 解包，多字节 UTF-8 中文回话在其气泡中会乱码；ASCII 回话全端安全。
        // 关键词保留中英文双轨：Godot 客户端（UTF-8）输入两者均可命中；原版客户端的
        // 中文聊天经服务端 UTF-8 解码后为乱码，实际只有英文关键词保证可触发。
        KEYWORD_REPLY.put("坐下", new PetChatResponse("Hehe, I sat down~", 21));   // sit
        KEYWORD_REPLY.put("sit", new PetChatResponse("Hehe, I sat down~", 21));
        KEYWORD_REPLY.put("睡觉", new PetChatResponse("So sleepy... Zzz~", 28));   // sleep
        KEYWORD_REPLY.put("sleep", new PetChatResponse("So sleepy... Zzz~", 28));
        KEYWORD_REPLY.put("吃饭", new PetChatResponse("Yummy! Thanks master~", 26)); // eat
        KEYWORD_REPLY.put("喂食", new PetChatResponse("Yummy! Thanks master~", 26));
        KEYWORD_REPLY.put("eat", new PetChatResponse("Yummy! Thanks master~", 26));
        KEYWORD_REPLY.put("你好", new PetChatResponse("Hello master!", 22));       // chat
        KEYWORD_REPLY.put("hello", new PetChatResponse("Hello master!", 22));
        KEYWORD_REPLY.put("hi", new PetChatResponse("Hello master!", 22));
        KEYWORD_REPLY.put("爱你", new PetChatResponse("I love you most, master!", 27)); // love
        KEYWORD_REPLY.put("love", new PetChatResponse("I love you most, master!", 27));
        KEYWORD_REPLY.put("饿了", new PetChatResponse("My tummy is growling...", 25)); // cry
        KEYWORD_REPLY.put("hungry", new PetChatResponse("My tummy is growling...", 25));
    }

    /**
     * 在玩家聊天文本中匹配宠物关键词；命中且未过冷却时返回回话内容。
     * 未命中、文本为空、或处于冷却期均返回 {@code Optional.empty()}。
     * 调用方应在用户确实召唤了宠物时才调用（无宠物无需匹配）。
     *
     * @param user 发言玩家（以角色 ID 作为冷却键）。
     * @param text 聊天文本。
     * @return 命中的回话内容；否则空。
     */
    public static Optional<PetChatResponse> respond(User user, String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        final int charId = user.getCharacterId();
        final long now = System.currentTimeMillis();
        // 原子预留每玩家冷却配额：仅赢得预留的那次调用允许回话。
        // 冷却判定与预留合并在 compareAndSet 上，同字符并发调用只会有一个成功，
        // 失败方重读后落在冷却窗口内即放弃，杜绝重复回话。
        final AtomicLong slot = LAST_REPLY_TIME.computeIfAbsent(charId, k -> new AtomicLong(0L));
        while (true) {
            final long last = slot.get();
            if (now - last < COOLDOWN_MS) {
                return Optional.empty();      // 仍在冷却期，放弃
            }
            if (slot.compareAndSet(last, now)) {
                break;                        // 赢得预留，允许回话
            }
            // 被其他线程抢先预留，重试（重读后大概率落入冷却期）
        }
        final String lower = text.toLowerCase();
        for (Map.Entry<String, PetChatResponse> e : KEYWORD_REPLY.entrySet()) {
            if (lower.contains(e.getKey().toLowerCase())) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }
}
