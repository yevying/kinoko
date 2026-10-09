package kinoko.world.field.mob;

import kinoko.provider.map.Foothold;
import kinoko.util.Rect;
import kinoko.util.Util;
import kinoko.world.field.Field;
import kinoko.world.field.life.MoveElem;
import kinoko.world.field.life.MovePath;
import kinoko.world.user.User;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 服务端权威的怪物行为驱动（方案1）。
 *
 * <p>在字段 tick（100ms）上为每个怪物做决策（巡游 / 追击 / 站立 / 坠落 / 飞行），
 * 并直接生成 MovePath 广播给所有客户端。客户端（Godot 联机模式恒为远程渲染）仅播放
 * 服务端下发的路径，不再由任何客户端本地驱动怪物移动。</p>
 *
 * <p>物理复刻自客户端 MobController / PhysicSystem（v95）：</p>
 * <ul>
 *   <li>行走：水平速度 = speed/100*125 px/s，沿平台 prev/next 链过渡（左→prev、右→next，
 *       与客户端 PhysicSystem.MobWalk 一致），Y 用 getYFromX 吸附平台线；
 *       走出平台端点且无有效连接平台 → 停在边缘内侧 20px 并掉头
 *       （matching reference: v95 CVecCtrlMob::CollisionDetectWalk moveAbility==1 —
 *       clamp 到 m_len±20、速度清零、SetInput(-inputX)、DelayedGiveUpChasing），
 *       行走的怪物永远不会走出平台坠落。</li>
 *   <li>坠落：重力 2000 px/s²，垂直速度上限 670 px/s，getFootholdBelow 检测落地。
 *       仅作为 foothold 引用失效时的恢复路径；越过地图底界则回到出生点兜底。</li>
 *   <li>飞行（moveAbility=4）：自由 2D 移动，追击目标或绕出生点盘旋，地图边界夹紧。</li>
 * </ul>
 *
 * <p>moveAction 字节（客户端 ResolveMoveActionType：actionType = moveAction>>1）：</p>
 * <ul>
 *   <li>move  = (1&lt;&lt;1) | dir</li>
 *   <li>stand = (2&lt;&lt;1) | dir</li>
 *   <li>jump  = (3&lt;&lt;1) | dir（坠落）</li>
 *   <li>fly   = (6&lt;&lt;1) | dir</li>
 *   <li>chase = (16&lt;&lt;1) | dir</li>
 * </ul>
 * 其中 dir bit：0 = 朝右，1 = 朝左。客户端 SetMoveAction 直接按该 bit 翻转朝向，
 * 因此每个广播包都携带当前真实朝向（mob.isLeft()），而非由位移推断。</p>
 */
public final class MobBehavior {
    // v95 行为 tick（与客户端 nCount 口径一致）
    private static final int TICK_MS = 100;
    // 广播批大小：累积 BATCH_TICKS 个 tick（300ms）的元素后统一广播一次。
    // 单 tick（100ms）广播时，客户端 Hermite 只在 100ms 内插值到终点，任何网络 jitter /
    // 帧率波动都会让新包到达时旧插值未播完，客户端 Clear 重置产生坐标回拉（飘移）。
    // 批量广播：客户端一次播完整批 3 个元素（300ms），包到达间隔拉长、批次内 Hermite
    // 多元素连续衔接（前元素终点速度=后元素起点速度），消除 tick 边界速度突变与回拉。
    private static final int BATCH_TICKS = 3;
    // 物理常量（matching reference: PhysicsConstants — gravityAcc=2000, fallSpeed=670）
    private static final float GRAVITY = 2000f;
    private static final float FALL_MAX_SPEED = 670f;
    private static final int FLY_HOVER_RADIUS = 150;
    // v95 CollisionDetectWalk 边缘停止内缩量（px）：怪物停在距平台端点 20px 处
    private static final int EDGE_INSET = 20;
    // 仇恨放弃区（matching reference: rcAreaGiveUp = {-1000, -600, 1000, 600}）
    private static final int GIVE_UP_RANGE_X = 1000;
    private static final int GIVE_UP_RANGE_Y = 600;

    // 内部动作
    private static final int ACT_STAND = 0;
    private static final int ACT_MOVE = 1;
    private static final int ACT_CHASE = 2;
    private static final int ACT_FALL = 3;
    private static final int ACT_FLY = 4;

    private final Mob mob;
    private Field field;
    private Map<Integer, Foothold> footholdBySn;
    private Rect border;
    private boolean inited;

    private float walkSpeedPx;
    private float flySpeedPx;

    // 决策状态
    private int moveTick;
    private int inputX;
    private int action;
    private int hateId;

    // v95 DelayedGiveUpChasing：追击怪物撞边缘后，仇恨最晚在該时刻放弃（ms 时间戳；0 = 无）
    private long chaseGiveUpAfterMs;

    // 坠落状态
    private boolean falling;
    private float vspeed;

    // 上一条广播的 moveAction 字节（含 dir bit，用于去重：姿态/朝向未变且未移动时不发包）
    private byte lastSentMoveAction = -1;

    // === 批量广播累积状态（方案B） ===
    // 当前批次起点（首个元素加入时的 tick 开始前位置），批次 flush 时作为 MovePath 头
    private int batchStartX;
    private int batchStartY;
    // 当前批次已累积元素
    private final List<MoveElem> batchElems = new ArrayList<>();
    // 当前批次已累积 tick 数
    private int batchTickCount;
    // 当前批次是否包含实际位移（flush 时作为"是否有新信息"的一半判断）
    private boolean batchMoved;
    // 批次首元素速度（作为广播 MovePath 头速度，客户端首元素 Hermite 的 m0）
    private short batchHeadVx;
    private short batchHeadVy;
    // 当前批次累积元素所属的动作类型（决定姿态切换时是否立即 flush；-1 = 无活跃批次）
    private int batchAction = -1;

    public MobBehavior(Mob mob) {
        this.mob = mob;
        // field 不在此捕获：Mob 构造时 field 尚未赋给 addMob，留待 ensureInit 惰性解析
        this.moveTick = 1;
    }

    /** 怪物受击，设置仇恨目标（重新受击刷新追击，清除旧的放弃计时）。 */
    public void setHate(int characterId) {
        hateId = characterId;
        chaseGiveUpAfterMs = 0;
    }

    /**
     * 字段 tick 调用一次。每 tick 推进并把移动元素累积到当前批次，累积满 BATCH_TICKS 个
     * tick（300ms）或动作类型（move/stand/chase/fall/fly）发生变化时统一重组成一条
     * MovePath 广播返回；批次内无新信息（未移动且姿态/朝向未变）时返回 empty。
     */
    public Optional<MovePath> update() {
        ensureInit();
        final int x0 = mob.getX();
        final int y0 = mob.getY();

        // v95 逐帧检查追击计时；这里每 tick（100ms）检查一次，避免放弃延迟到下一次 decide()
        if (hateId != 0 && chaseGiveUpAfterMs != 0 && System.currentTimeMillis() >= chaseGiveUpAfterMs) {
            hateId = 0;
            chaseGiveUpAfterMs = 0;
        }

        // 方向决策（每 tick 递减，归零重选）
        moveTick--;
        if (moveTick <= 0) {
            decide();
        }

        // 推进
        if (mob.getTemplate().isFlying()) {
            flyStep();
        } else if (falling) {
            fallStep();
        } else {
            walkStep();
        }

        // === 批量累积（方案B） ===
        // 本 tick 的首个元素：记录批次起点 = tick 开始前位置（作为广播 MovePath 头）
        Optional<MovePath> toBroadcast = Optional.empty();
        if (batchElems.isEmpty()) {
            batchStartX = x0;
            batchStartY = y0;
        } else if (action != batchAction) {
            // 动作类型发生变化（move→stand、掉头、入坠、落地）→ 立即 flush 已累积元素，
            // 姿态切换不拖延到批次满；flush 后重置批次起点，再把本 tick 元素加入新批次。
            toBroadcast = flushBatch();
            batchStartX = x0;
            batchStartY = y0;
        }

        appendElement(x0, y0);
        batchAction = action;

        // 累积满 BATCH_TICKS 个 tick → 重组广播（动作切换已广播过则本帧不再重复广播）
        if (batchTickCount >= BATCH_TICKS && toBroadcast.isEmpty()) {
            toBroadcast = flushBatch();
        }
        return toBroadcast;
    }

    /** 重置状态（重生 / 换图时调用）。 */
    public void reset() {
        moveTick = 1;
        inputX = 0;
        action = ACT_STAND;
        hateId = 0;
        chaseGiveUpAfterMs = 0;
        falling = false;
        vspeed = 0;
        lastSentMoveAction = -1;
        batchStartX = 0;
        batchStartY = 0;
        batchElems.clear();
        batchTickCount = 0;
        batchMoved = false;
        batchHeadVx = 0;
        batchHeadVy = 0;
        batchAction = -1;
    }

    public boolean isFalling() {
        return falling;
    }


    // DECISION --------------------------------------------------------------------------------------------------------

    private void decide() {
        final int target = resolveHate();
        if (target != 0 && hateId != 0) {
            // 追击目标
            final Optional<User> userResult = field.getUserPool().getById(hateId);
            if (userResult.isPresent()) {
                inputX = userResult.get().getX() >= mob.getX() ? 1 : -1;
            }
            moveTick = (Util.getRandom(0, 399) + 800) / 90; // 8~13 tick
            action = mob.getTemplate().isFlying() ? ACT_FLY : ACT_CHASE;
            return;
        }
        // 无目标：随机站立 / 行走 / 悬停
        inputX = Util.getRandom(0, 2) - 1; // -1, 0, 1
        moveTick = (Util.getRandom(0, 1999) + 1000) / 90; // 11~33 tick
        action = inputX == 0 ? ACT_STAND
                : mob.getTemplate().isFlying() ? ACT_FLY : ACT_MOVE;
    }

    private int resolveHate() {
        if (hateId != 0) {
            // （放弃计时到期在 update() 每 tick 检查）目标离场 / 超出放弃区 → 清除仇恨
            final Optional<User> userResult = field.getUserPool().getById(hateId);
            if (userResult.isEmpty() || !inGiveUpArea(userResult.get())) {
                hateId = 0;
                chaseGiveUpAfterMs = 0;
            }
        }
        // firstAttack / pickUpDrop 的怪物：无仇恨时锁定最近的玩家
        if (hateId == 0 && (mob.getTemplate().isFirstAttack() || mob.getTemplate().isPickUpDrop())) {
            final Optional<User> nearest = field.getUserPool().getNearestUser(mob);
            nearest.ifPresent(user -> {
                if (inGiveUpArea(user)) {
                    hateId = user.getCharacterId();
                }
            });
        }
        return hateId;
    }

    private boolean inGiveUpArea(User user) {
        return Math.abs(user.getX() - mob.getX()) <= GIVE_UP_RANGE_X
                && Math.abs(user.getY() - mob.getY()) <= GIVE_UP_RANGE_Y;
    }


    // STEPPERS --------------------------------------------------------------------------------------------------------

    /** 地面行走 / 站立。走出平台端点且无有效连接平台时停在边缘并掉头，绝不坠落。 */
    private void walkStep() {
        if (action == ACT_STAND || inputX == 0) {
            action = ACT_STAND;
            return;
        }
        final Foothold target = footholdBySn.get(mob.getFoothold());
        if (target == null || target.isWall()) {
            // foothold 引用失效（异常数据兜底）：坠落直到落到下方平台，由 fallStep 恢复
            startFall(mob.getX());
            return;
        }

        final float dt = TICK_MS / 1000f;
        final int dir = inputX;
        mob.setLeft(dir < 0);
        float newX = mob.getX() + dir * walkSpeedPx * dt;
        newX = clampX(newX);

        // 平台可能从右向左绘制，统一按 min/max 归一化跨度（matching reference: 客户端 MapData L/R）
        final int lo = Math.min(target.getX1(), target.getX2());
        final int hi = Math.max(target.getX1(), target.getX2());
        if (newX >= lo && newX <= hi) {
            mob.setX((int) newX);
            mob.setY(target.getYFromX((int) newX));
            action = ACT_MOVE;
            return;
        }

        // 走出当前平台：解析该侧连接平台（左→prev、右→next，matching reference: PhysicSystem.MobWalk）
        final boolean goingLeft = newX < lo;
        final int linkSn = goingLeft ? target.getPrev() : target.getNext();
        final Foothold linked = linkSn == 0 ? null : footholdBySn.get(linkSn);
        if (linked == null || linked.isWall()) {
            // v95：无有效连接平台 → 边缘停止 + 掉头，行走怪物不掉落
            stopAtEdge(target, goingLeft);
            return;
        }

        // 有连接平台 → 过渡过去；跨度不连续（间隙）时夹紧到对端近侧端点内，避免悬空外推
        final int nLo = Math.min(linked.getX1(), linked.getX2());
        final int nHi = Math.max(linked.getX1(), linked.getX2());
        final int clampedX = (int) Math.max(nLo, Math.min(nHi, newX));
        mob.setX(clampedX);
        mob.setY(linked.getYFromX(clampedX));
        mob.setFoothold(linked.getSn());
        action = ACT_MOVE;
    }

    /**
     * v95 CVecCtrlMob::CollisionDetectWalk 边缘停止：夹紧到端点内侧 20px、掉头，
     * 追击中的怪物同时启动延迟放弃计时（DelayedGiveUpChasing）。
     */
    private void stopAtEdge(Foothold fh, boolean goingLeft) {
        final int lo = Math.min(fh.getX1(), fh.getX2());
        final int hi = Math.max(fh.getX1(), fh.getX2());
        float x = goingLeft ? lo + EDGE_INSET : hi - EDGE_INSET;
        if (x < lo || x > hi) {
            // 平台过短（宽度不足两倍内缩量）：退到中点，保证仍站在平台上
            x = (lo + hi) / 2f;
        }
        mob.setX((int) x);
        mob.setY(fh.getYFromX((int) x));
        inputX = -inputX;
        mob.setLeft(inputX < 0);
        if (hateId != 0) {
            // v95: m_tChaseDuration = min(current, rand%3000+3000) — 只提前、不延后
            final long candidate = System.currentTimeMillis() + 3000L + Util.getRandom(3000);
            chaseGiveUpAfterMs = Math.min(chaseGiveUpAfterMs == 0 ? Long.MAX_VALUE : chaseGiveUpAfterMs, candidate);
        }
    }

    /** 坠落：重力累积 + getFootholdBelow 落地检测；越过地图底界则回到出生点兜底。 */
    private void fallStep() {
        final float dt = TICK_MS / 1000f;
        final float y0 = mob.getY();
        vspeed = Math.min(vspeed + GRAVITY * dt, FALL_MAX_SPEED);
        final float newY = mob.getY() + vspeed * dt;

        // 用移动前的 Y 查询：getFootholdBelow 只返回查询点下方（my >= y）的表面，
        // 若用移动后的 newY 会漏掉本 tick 刚穿过的平台面，导致永远落不到平台上
        final Optional<Foothold> landingResult = field.getMapInfo().getFootholdBelow(mob.getX(), (int) y0);
        if (landingResult.isPresent() && !landingResult.get().isWall()) {
            final Foothold landing = landingResult.get();
            final int landY = landing.getYFromX(mob.getX());
            // 本 tick 从平台面上方穿过其表面（y0 <= landY 且 newY 已到/越过 landY）即落地
            if (y0 <= landY && newY >= landY) {
                mob.setY(landY);
                mob.setFoothold(landing.getSn());
                falling = false;
                vspeed = 0;
                action = ACT_STAND;
                return;
            }
        }
        // 兜底：越过地图底界（下方无任何平台）→ 回到出生点，避免无限坠落
        ensureBorder();
        if (newY > border.getBottom() + 100) {
            respawnAtSpawn();
            return;
        }
        mob.setY((int) newY);
        action = ACT_FALL;
    }

    /** 飞行：自由 2D 移动，追击目标或绕出生点盘旋。 */
    private void flyStep() {
        final float dt = TICK_MS / 1000f;
        float angle;
        if (hateId != 0) {
            final Optional<User> userResult = field.getUserPool().getById(hateId);
            if (userResult.isPresent()) {
                final User user = userResult.get();
                angle = (float) Math.atan2(user.getY() - mob.getY(), user.getX() - mob.getX())
                        + (float) Math.toRadians(Util.getRandom(-15, 15));
                action = ACT_FLY;
            } else {
                angle = hoverAngle();
                action = ACT_FLY;
            }
        } else {
            angle = hoverAngle();
            action = ACT_FLY;
        }
        final float vx = (float) Math.cos(angle) * flySpeedPx;
        final float vy = (float) Math.sin(angle) * flySpeedPx;
        mob.setLeft(vx < 0);
        float newX = mob.getX() + vx * dt;
        float newY = mob.getY() + vy * dt;
        newX = clampX(newX);
        newY = clampY(newY);
        mob.setX((int) newX);
        mob.setY((int) newY);
    }

    private float hoverAngle() {
        final float homeX = mob.getSpawnX();
        final float homeY = mob.getSpawnY();
        final float dx = homeX - mob.getX();
        final float dy = homeY - mob.getY();
        final float distSq = dx * dx + dy * dy;
        if (distSq > FLY_HOVER_RADIUS * FLY_HOVER_RADIUS) {
            // 远离出生点：飞回 home + 抖动
            return (float) Math.atan2(dy, dx) + (float) Math.toRadians(Util.getRandom(-12, 12));
        }
        // 接近 home：随机小角度漂移
        final float sign = Util.getRandom(0, 1) == 0 ? -1f : 1f;
        return (float) Math.toRadians(Util.getRandom(-35, 35)) * sign;
    }

    private void startFall(float edgeX) {
        mob.setX((int) edgeX);
        falling = true;
        vspeed = 0;
        action = ACT_FALL;
    }

    /** 坠落兜底恢复：回到出生点并重新吸附平台。 */
    private void respawnAtSpawn() {
        final int sx = mob.getSpawnX();
        final int sy = mob.getSpawnY();
        mob.setX(sx);
        mob.setY(sy);
        falling = false;
        vspeed = 0;
        inputX = 0;
        moveTick = 1; // 下一 tick 重新决策
        action = ACT_STAND;
        final Optional<Foothold> fhResult = field.getMapInfo().getFootholdBelow(sx, sy);
        if (fhResult.isPresent() && !fhResult.get().isWall()) {
            mob.setFoothold(fhResult.get().getSn());
        }
    }

    private float clampX(float x) {
        ensureBorder();
        return Math.max(border.getLeft(), Math.min(border.getRight(), x));
    }

    private float clampY(float y) {
        ensureBorder();
        return Math.max(border.getTop(), Math.min(border.getBottom(), y));
    }


    // PATH BUILDING ---------------------------------------------------------------------------------------------------

    /**
     * 把本 tick 的移动追加为一个 MovePath 元素（NORMAL，含速度/朝向）。
     * 速度取本 tick 实际位移/dt（带符号）：与客户端 Hermite 弦斜率一致，
     * 批次内相邻元素 m1/m0 相等 → Hermite 在元素间自然衔接，消除 tick 边界"停→加速"脉动。
     */
    private void appendElement(int x0, int y0) {
        final float dtSec = TICK_MS / 1000f;
        final short hspeed = (short) Math.round((mob.getX() - x0) / dtSec);
        final short vspeed0 = (short) Math.round((mob.getY() - y0) / dtSec);
        final byte moveAction = moveActionByte(mob.isLeft() ? 1 : 0);

        final MoveElem elem = new MoveElem((byte) 0); // NORMAL
        elem.setX((short) mob.getX());
        elem.setY((short) mob.getY());
        elem.setVx(hspeed);
        elem.setVy(vspeed0);
        elem.setFh((short) mob.getFoothold());
        elem.setXOffset((short) 0);
        elem.setYOffset((short) 0);
        elem.setMoveAction(moveAction);
        elem.setElapse((short) TICK_MS);

        if (batchElems.isEmpty()) {
            batchHeadVx = hspeed;
            batchHeadVy = vspeed0;
        }
        batchElems.add(elem);
        batchTickCount++;
        batchMoved |= (hspeed != 0 || vspeed0 != 0);
    }

    /**
     * 重组当前批次为一条 MovePath 广播并清空累积状态。
     * 路径头 = 批次起点（tick 开始前位置）+ 首元素速度；批次内多元素（300ms）由客户端
     * 一次 Hermite 播完，消除单 tick 广播因网络 jitter 导致的插值重置回拉（飘移）。
     * 无新信息（未移动且姿态/朝向与上一广播相同）时返回 empty 且不更新 lastSentMoveAction。
     */
    private Optional<MovePath> flushBatch() {
        if (batchElems.isEmpty()) {
            return Optional.empty();
        }
        // 批次尾元素真实 MoveAction 字节（动作变化触发 flush 时，当前 action 已切到新姿态，
        // 若用 moveActionByte 会编错批次内元素的姿态）；去重用 batchMoved + 尾元素动作是否变化。
        final int lastAction = batchAction;
        final boolean hasChange = batchMoved || moveActionByte(mob.isLeft() ? 1 : 0) != lastSentMoveAction
                || lastAction != action;
        // 重置累积状态（无论是否广播都要重置，避免下一个批次从残留状态起步）
        final List<MoveElem> batch = new ArrayList<>(batchElems);
        batchElems.clear();
        batchTickCount = 0;
        batchMoved = false;
        batchAction = -1;
        if (!hasChange) {
            return Optional.empty();
        }
        // lastSentMoveAction 更新为批次尾元素真实动作
        final MoveElem tail = batch.get(batch.size() - 1);
        final byte tailMoveAction = tail.getMoveAction();
        lastSentMoveAction = tailMoveAction;
        return Optional.of(new MovePath(
                (short) batchStartX,
                (short) batchStartY,
                batchHeadVx,
                batchHeadVy,
                batch));
    }

    private byte moveActionByte(int dir) {
        switch (action) {
            case ACT_MOVE -> {
                return (byte) ((1 << 1) | dir);
            }
            case ACT_CHASE -> {
                return (byte) ((16 << 1) | dir);
            }
            case ACT_FALL -> {
                return (byte) ((3 << 1) | dir);
            }
            case ACT_FLY -> {
                return (byte) ((6 << 1) | dir);
            }
            default -> {
                return (byte) ((2 << 1) | dir); // stand
            }
        }
    }

    // INIT -----------------------------------------------------------------------------------------------------------

    private void ensureInit() {
        if (inited) {
            return;
        }
        inited = true;
        if (field == null) {
            field = mob.getField();
        }
        final Map<Integer, Foothold> map = new HashMap<>();
        for (Foothold fh : field.getMapInfo().getFootholds()) {
            map.put(fh.getSn(), fh);
        }
        this.footholdBySn = map;
        this.border = computeBorder();
        // v95 CMob::SetShoeAttr_641E50 — walkSpeed = clamp(nSpeed + nSpeed_, 10, 140)/100，
        // WZ speed 带 -100 偏移（多为负值），还原为 (speed+100)；基准 125 px/s
        // （matching reference: 客户端 MobDataLoader — WalkSpeed = (rawSpeed+100)/100*125）。
        final int speed = mob.getTemplate().getSpeed();
        this.walkSpeedPx = Math.clamp(speed + 100, 10, 140) / 100f * 125f;
        // 飞行怪（moveAbility=4）v95 同样按 speed 统计量计算；WZ flySpeed 存在时优先，口径一致。
        final int flySpeed = mob.getTemplate().getFlySpeed();
        this.flySpeedPx = flySpeed > 0 ? Math.clamp(flySpeed + 100, 10, 140) / 100f * 125f : this.walkSpeedPx;
    }

    /** 确保 border 非空后再使用（防御：任何状态异常都不会让 clampX/clampY 空指针）。 */
    private void ensureBorder() {
        if (border != null) {
            return;
        }
        border = computeBorder();
    }

    private Rect computeBorder() {
        // 部分地图（水下/纯飞行图）getRootBounds 为 null，需兜底到平台外接矩形，否则用出生点周边安全范围
        final Rect rootBounds = field.getMapInfo().getRootBounds();
        if (rootBounds != null) {
            return rootBounds;
        }
        Rect union = null;
        for (Foothold fh : field.getMapInfo().getFootholds()) {
            final Rect fhRect = Rect.of(
                    Math.min(fh.getX1(), fh.getX2()),
                    Math.min(fh.getY1(), fh.getY2()),
                    Math.max(fh.getX1(), fh.getX2()),
                    Math.max(fh.getY1(), fh.getY2())
            );
            union = union == null ? fhRect : union.union(fhRect);
        }
        return union != null ? union
                : Rect.of(mob.getSpawnX() - 800, mob.getSpawnY() - 800, mob.getSpawnX() + 800, mob.getSpawnY() + 800);
    }
}
