package kinoko.handler.field;

import kinoko.handler.Handler;
import kinoko.packet.field.NpcPacket;
import kinoko.server.header.InHeader;
import kinoko.server.packet.InPacket;
import kinoko.world.field.Field;
import kinoko.world.field.life.MovePath;
import kinoko.world.field.npc.Npc;
import kinoko.world.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public final class NpcHandler {
    private static final Logger log = LogManager.getLogger(NpcHandler.class);

    @Handler(InHeader.NpcMove)
    public static void handleNpcMove(User user, InPacket inPacket) {
        final int objectId = inPacket.decodeInt(); // dwNpcId
        final byte oneTimeAction = inPacket.decodeByte(); // nOneTimeAction
        final byte chatIndex = inPacket.decodeByte(); // nChatIdx

        final Field field = user.getField();
        final Optional<Npc> npcResult = field.getNpcPool().getById(objectId);
        if (npcResult.isEmpty()) {
            log.error("Received NpcMove for invalid object with ID : {}", objectId);
            return;
        }
        final Npc npc = npcResult.get();

        // MovePath 头部固定 9 字节（4×short + byte count）。若客户端在 bMove 状态下
        // 因故未附带 MovePath（如模板判定不一致或旧版本客户端），剩余不足时直接跳过解码，
        // 避免 BufferUnderflowException 崩溃（matching reference: 095 客户端始终 Flush MovePath）。
        final MovePath movePath = npc.isMove() && inPacket.getRemaining() >= 9 ? MovePath.decode(inPacket) : null;
        if (movePath != null) {
            movePath.applyTo(npc);
        }
        // 空移动路径（count=0 或未附带 MovePath）不广播：原版 095 客户端处理空 NpcMove 会闪退
        // （同 MobMove 空路径兜底，表现为 Error 0 | NpcMove → Connection reset）。并排除发送方——
        // 控制器已在本地应用路径（Godot NpcNode::GenerateControllerMovePath 末尾 PlayMovePath），
        // 无需回显（matching reference: handleMobMove 同样排除 user）。
        if (movePath != null && !movePath.getElems().isEmpty()) {
            field.broadcastPacket(NpcPacket.npcMove(npc, oneTimeAction, chatIndex, movePath), user);
        }
    }
}
