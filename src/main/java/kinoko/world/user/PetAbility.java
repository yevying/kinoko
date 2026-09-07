package kinoko.world.user;

import kinoko.packet.world.WvsContext;
import kinoko.util.Tuple;
import kinoko.world.item.BodyPart;
import kinoko.world.item.Inventory;
import kinoko.world.item.InventoryOperation;
import kinoko.world.item.InventoryType;
import kinoko.world.item.Item;

import java.util.Map;
import java.util.Optional;

/**
 * 宠物能力位（matching reference: v95 UpdatePetAbility_6A0A40 — dwPetAbilityFlag）：
 * bit0=1812000 拾取金币, bit1=1812001 拾取物品, bit2=1812006 拾取他人掉落,
 * bit3=1812005 长距离, bit4=1812004 扫荡, bit5=1812002 自动喝血（仅宠物 0）,
 * bit6=1812003 自动喝蓝（仅宠物 0）, bit7=1812007 忽略列表生效。
 * <p>
 * petSkill 由服务端按已装备的能力物品权威重算（换装/进图/激活时），
 * 客户端本地也会做同样的重算（v95 OnValidateStat → UpdatePetAbility）。
 */
public final class PetAbility {
    public static final int PICKUP_MESO = 0x01; // 1812000
    public static final int PICKUP_ITEM = 0x02; // 1812001
    public static final int PICKUP_OTHERS = 0x04; // 1812006
    public static final int LONG_RANGE = 0x08; // 1812005
    public static final int SWEEP_FOR_DROP = 0x10; // 1812004
    public static final int CONSUME_HP = 0x20; // 1812002（仅宠物 0）
    public static final int CONSUME_MP = 0x40; // 1812003（仅宠物 0）
    public static final int IGNORE_ITEMS = 0x80; // 1812007

    private static final Map<Integer, Integer> ITEM_FLAGS = Map.of(
            1812000, PICKUP_MESO,
            1812001, PICKUP_ITEM,
            1812006, PICKUP_OTHERS,
            1812005, LONG_RANGE,
            1812004, SWEEP_FOR_DROP,
            1812002, CONSUME_HP,
            1812003, CONSUME_MP,
            1812007, IGNORE_ITEMS
    );

    // 能力槽 base part（经 getByPetIndex 展开到各宠物）；HPCONSUME/MPCONSUME 仅宠物 0 有槽位（BodyPart 24/25）
    private static final BodyPart[] ABILITY_PARTS = {
            BodyPart.PETABIL_MESO,
            BodyPart.PETABIL_ITEM,
            BodyPart.PETABIL_HPCONSUME,
            BodyPart.PETABIL_MPCONSUME,
            BodyPart.PETABIL_SWEEPFORDROP,
            BodyPart.PETABIL_LONGRANGE,
            BodyPart.PETABIL_PICKUPOTHERS,
            BodyPart.PETABIL_IGNOREITEMS1
    };

    private PetAbility() {
    }

    public static int getFlag(int itemId) {
        return ITEM_FLAGS.getOrDefault(itemId, 0);
    }

    /** bodyPart 是否为某只宠物的能力槽（含 IGNOREITEMS1/2/3）。 */
    public static boolean isPetAbilityPart(BodyPart bodyPart) {
        return getPetIndex(bodyPart) >= 0;
    }

    /** bodyPart 归属的宠物索引；非能力槽返回 -1。 */
    public static int getPetIndex(BodyPart bodyPart) {
        for (int i = 0; i < 3; i++) {
            for (BodyPart base : ABILITY_PARTS) {
                // HPCONSUME/MPCONSUME 不在 petBodyParts 映射中，getByPetIndex 恒返回自身 → 仅宠物 0 认领
                if (bodyPart == BodyPart.getByPetIndex(base, i)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 按该宠物已装备的能力物品重算能力位掩码（matching reference: v95 UpdatePetAbility_6A0A40）。 */
    public static int computeSkill(User user, int petIndex) {
        final Inventory equipped = user.getInventoryManager().getEquipped();
        int skill = 0;
        for (BodyPart base : ABILITY_PARTS) {
            if ((base == BodyPart.PETABIL_HPCONSUME || base == BodyPart.PETABIL_MPCONSUME) && petIndex != 0) {
                continue; // 仅宠物 0 有喝药槽位
            }
            final Item item = equipped.getItem(BodyPart.getByPetIndex(base, petIndex).getValue() + BodyPart.CASH_BASE.getValue());
            if (item != null) {
                skill |= ITEM_FLAGS.getOrDefault(item.getItemId(), 0);
            }
        }
        return skill;
    }

    /**
     * 重算指定宠物的 petSkill，变化时写入 PetData 并回推宠物物品（含 usPetSkill）。
     * 宠物未召唤时无可更新对象，静默跳过（下次激活/进图会再重算）。
     */
    public static void refresh(User user, int petIndex) {
        final Pet pet = user.getPet(petIndex);
        if (pet == null) {
            return;
        }
        final int skill = computeSkill(user, petIndex);
        final Item item = pet.getItem();
        if (item.getPetData().getPetSkill() == skill) {
            return;
        }
        item.getPetData().setPetSkill((short) skill);
        // 回推宠物物品刷新客户端现金背包显示（matching reference: Pet.updateFullness 的 newItem 推送模式）
        final Optional<Tuple<Integer, Item>> entry = user.getInventoryManager().getItemBySn(InventoryType.CASH, pet.getItemSn());
        if (entry.isPresent()) {
            user.write(WvsContext.inventoryOperation(InventoryOperation.newItem(InventoryType.CASH, entry.get().getLeft(), item), false));
        }
    }
}
