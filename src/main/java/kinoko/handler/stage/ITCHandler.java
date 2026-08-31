package kinoko.handler.stage;

import kinoko.handler.Handler;
import kinoko.packet.stage.ITCPacket;
import kinoko.packet.world.WvsContext;
import kinoko.server.auction.AuctionListing;
import kinoko.server.auction.AuctionManager;
import kinoko.server.auction.SearchResult;
import kinoko.server.header.InHeader;
import kinoko.server.packet.InPacket;
import kinoko.world.GameConstants;
import kinoko.world.item.*;
import kinoko.world.user.User;
import kinoko.world.user.stat.Stat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class ITCHandler {
    private static final Logger log = LogManager.getLogger(ITCHandler.class);
    private static final int PAGE_SIZE = 16;

    @Handler(InHeader.ITCQueryCashRequest)
    public static void handleQueryCashRequest(User user, InPacket inPacket) {
        user.write(ITCPacket.queryCashResult(user.getAccount()));
    }

    @Handler(InHeader.ITCItemRequest)
    public static void handleItemRequest(User user, InPacket inPacket) {
        final int subOp = inPacket.decodeByte();
        final AuctionManager auctionManager = AuctionManager.getInstance();

        switch (subOp) {
            case 0x02 -> handleRegisterSaleItem(user, inPacket, auctionManager);
            case 0x05 -> handleRequestList(user, inPacket, auctionManager);
            case 0x06 -> handleSearchList(user, inPacket, auctionManager);
            case 0x07 -> handleCancelSaleItem(user, inPacket, auctionManager);
            case 0x08 -> handleMoveItemToInventory(user, inPacket, auctionManager);
            case 0x09 -> handleMySaleList(user, auctionManager);
            case 0x0B -> handleMyPurchaseList(user, auctionManager);
            case 0x10 -> handleBuyItem(user, inPacket, auctionManager);
            case 0x12 -> handleRegisterAuctionItem(user, inPacket, auctionManager);
            case 0x13 -> handleBid(user, inPacket, auctionManager);
            case 0x14 -> handleBuyAuctionImm(user, inPacket, auctionManager);
            default -> log.error("Unhandled ITCItemRequest sub-opcode : {}", subOp);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sub-op handlers
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * 0x02 - RegisterSaleItem: register a direct sale item.
     * <p>两种客户端协议（matching reference: OnRegisterSaleEntry_572E90）：
     * <ul>
     *   <li>Godot 槽位协议：nTI(4) + nPOS(2) + price(4) + regFeeOption(1) + feeOption(1)</li>
     *   <li>原版 095 客户端：GW_ItemSlotBase::Encode(item) + slotNo(4, 1-based) + count(4)
     *       + price(4) + durationByte(1) + feeOption(1)</li>
     * </ul>
     * subOp 后前 4 字节用于区分：Godot 的 nTI ∈ [1,5]；原版格式该处是 type(1)+itemID 低 3 字节，
     * 真实物品 ID ≥2000000（低 3 字节不可能全 0），故恒 >5。
     */
    private static void handleRegisterSaleItem(User user, InPacket inPacket, AuctionManager auctionManager) {
        final int head = inPacket.decodeInt();

        Item item;
        int slotNo;
        int count;
        if (head >= 1 && head <= 5) {
            // Godot 槽位协议
            final InventoryType inventoryType = InventoryType.getByValue(head);
            slotNo = inPacket.decodeShort();
            if (inventoryType == null || inventoryType == InventoryType.EQUIPPED) {
                log.error("[ITC] RegisterSale rejected: invalid inventory type {} for {}", head, user.getCharacterName());
                sendRegisterFailed(user, 0);
                return;
            }
            item = user.getInventoryManager().getInventoryByType(inventoryType).getItem(slotNo);
            count = item != null ? item.getQuantity() : 0;
        } else {
            // 原版客户端：完整物品序列化
            final int[] parsed = decodeOriginalRegisterHead(inPacket, head);
            if (parsed == null) {
                log.error("[ITC] RegisterSale rejected: malformed original register packet for {}", user.getCharacterName());
                sendRegisterFailed(user, 'O');
                return;
            }
            user.setOriginalITCClient(true);
            final int itemId = parsed[1];
            slotNo = parsed[2];
            count = parsed[3];

            final InventoryType inventoryType = InventoryType.getByItemId(itemId);
            if (inventoryType == null || inventoryType == InventoryType.EQUIPPED) {
                log.error("[ITC] RegisterSale rejected: invalid item type for itemId {} from {}", itemId, user.getCharacterName());
                sendRegisterFailed(user, 'O');
                return;
            }
            item = user.getInventoryManager().getInventoryByType(inventoryType).getItem(slotNo);
            // 防伪造：槽位必须确实持有声明的物品
            if (item == null || item.getItemId() != itemId) {
                log.error("[ITC] RegisterSale rejected: slot {} does not hold item {} for {}", slotNo, itemId, user.getCharacterName());
                sendRegisterFailed(user, 'O');
                return;
            }
        }

        if (item == null || count < 1 || count > item.getQuantity() || GameConstants.isITCTradeLimitItem(item)) {
            log.error("[ITC] RegisterSale rejected: item null={} tradeLimit={} itemId={} pos={} count={} for {}",
                    item == null, item != null && GameConstants.isITCTradeLimitItem(item),
                    item != null ? item.getItemId() : 0, slotNo, count, user.getCharacterName());
            sendRegisterFailed(user, 'O');
            return;
        }

        final int price = inPacket.decodeInt();
        inPacket.decodeByte(); // duration / regFeeOption（服务端不校验）
        inPacket.decodeByte(); // feeOption

        registerAndRespond(user, auctionManager, item, slotNo, count,
                listing -> auctionManager.registerSaleItem(user, listing, price),
                "price=" + price);
    }

    /**
     * 0x12 - RegisterAuctionItem: register an auction item.
     * <p>两种客户端协议（matching reference: OnRegisterSaleEntry_572E90 nRegType=1 分支）：
     * <ul>
     *   <li>Godot 槽位协议：nTI(4) + nPOS(2) + startPrice(4) + buyoutPrice(4) + duration(1) + feeOption(1) + bidRange(4)</li>
     *   <li>原版 095 客户端：GW_ItemSlotBase::Encode(item) + slotNo(4) + count(4) + beginPrice(4) + endPrice(4)
     *       + duration(1, 24~168 小时) + feeOption(1) + bidRange(4)</li>
     * </ul>
     */
    private static void handleRegisterAuctionItem(User user, InPacket inPacket, AuctionManager auctionManager) {
        final int head = inPacket.decodeInt();

        Item item;
        int slotNo;
        int count;
        if (head >= 1 && head <= 5) {
            // Godot 槽位协议
            final InventoryType inventoryType = InventoryType.getByValue(head);
            slotNo = inPacket.decodeShort();
            if (inventoryType == null || inventoryType == InventoryType.EQUIPPED) {
                log.error("[ITC] RegisterAuction rejected: invalid inventory type {} for {}", head, user.getCharacterName());
                sendRegisterFailed(user, 0);
                return;
            }
            item = user.getInventoryManager().getInventoryByType(inventoryType).getItem(slotNo);
            count = item != null ? item.getQuantity() : 0;
        } else {
            // 原版客户端：完整物品序列化
            final int[] parsed = decodeOriginalRegisterHead(inPacket, head);
            if (parsed == null) {
                log.error("[ITC] RegisterAuction rejected: malformed original register packet for {}", user.getCharacterName());
                sendRegisterFailed(user, 'O');
                return;
            }
            user.setOriginalITCClient(true);
            final int itemId = parsed[1];
            slotNo = parsed[2];
            count = parsed[3];

            final InventoryType inventoryType = InventoryType.getByItemId(itemId);
            if (inventoryType == null || inventoryType == InventoryType.EQUIPPED) {
                log.error("[ITC] RegisterAuction rejected: invalid item type for itemId {} from {}", itemId, user.getCharacterName());
                sendRegisterFailed(user, 'O');
                return;
            }
            item = user.getInventoryManager().getInventoryByType(inventoryType).getItem(slotNo);
            // 防伪造：槽位必须确实持有声明的物品
            if (item == null || item.getItemId() != itemId) {
                log.error("[ITC] RegisterAuction rejected: slot {} does not hold item {} for {}", slotNo, itemId, user.getCharacterName());
                sendRegisterFailed(user, 'O');
                return;
            }
        }

        if (item == null || count < 1 || count > item.getQuantity() || GameConstants.isITCTradeLimitItem(item)) {
            log.error("[ITC] RegisterAuction rejected: item null={} tradeLimit={} itemId={} pos={} count={} for {}",
                    item == null, item != null && GameConstants.isITCTradeLimitItem(item),
                    item != null ? item.getItemId() : 0, slotNo, count, user.getCharacterName());
            sendRegisterFailed(user, 'O');
            return;
        }

        final int startPrice = inPacket.decodeInt();
        final int buyoutPrice = inPacket.decodeInt();
        // duration 为 1 字节（095 协议 BYTE 无符号，0~255）。decodeByte() 返回带符号 byte，
        // 128~168 小时（ITC_AUCTION_DURATION_MAX=168）会被解释为负数（168=0xA8 → -88），
        // 导致 registerAuctionItem 时长校验失败。按无符号字节读取以支持完整 24~168 小时范围。
        final int duration = inPacket.decodeByte() & 0xFF;
        inPacket.decodeByte(); // feeOption
        final int bidRange = inPacket.decodeInt();

        registerAndRespond(user, auctionManager, item, slotNo, count,
                listing -> auctionManager.registerAuctionItem(user, listing, startPrice, buyoutPrice, duration, bidRange),
                "start=" + startPrice + ", buyout=" + buyoutPrice + ", duration=" + duration + ", bidRange=" + bidRange);
    }

    /** 登记公共尾部：移出背包 → AuctionManager 登记（失败回滚）→ 发包 + 推送"我的出售"列表。 */
    private static void registerAndRespond(User user, AuctionManager auctionManager, Item item, int slotNo, int count,
                                           Function<Item, Optional<AuctionListing>> registrar,
                                           String detail) {
        final InventoryManager im = user.getInventoryManager();
        final Inventory inventory = im.getInventoryByType(InventoryType.getByItemId(item.getItemId()));

        // 上架物品：部分数量出售时复制并设置数量，否则直接用原对象
        final Item listingItem;
        if (count == item.getQuantity()) {
            listingItem = item;
        } else {
            listingItem = new Item(item);
            listingItem.setQuantity((short) count);
        }

        // Remove item from inventory first
        final int originalQuantity = item.getQuantity();
        final Optional<InventoryOperation> removeResult = im.removeItem(slotNo, item, count);
        if (removeResult.isEmpty()) {
            log.error("[ITC] Register rejected: failed to remove item {} pos {} for {}",
                    item.getItemId(), slotNo, user.getCharacterName());
            sendRegisterFailed(user, 'O');
            return;
        }

        // Register through AuctionManager
        final Optional<AuctionListing> listingResult = registrar.apply(listingItem);
        if (listingResult.isEmpty()) {
            // Rollback: restore item to inventory（部分移除时先恢复原数量）
            if (item.getQuantity() != originalQuantity) {
                item.setQuantity((short) originalQuantity);
            }
            inventory.putItem(slotNo, item);
            log.error("[ITC] Register rejected: auctionManager failed ({}, meso={}) for {}", detail, im.getMoney(), user.getCharacterName());
            // 金币不足手续费 → 'C'（你的金币不足）；其余原因客户端已预检，静默失败
            sendRegisterFailed(user, im.canAddMoney(-GameConstants.ITC_REGISTER_FEE_MESO) ? 0 : 'C');
            return;
        }

        // Send inventory operation and success
        user.write(WvsContext.inventoryOperation(removeResult.get(), false));
        user.write(ITCPacket.registerDone(listingResult.get()));
        user.write(ITCPacket.queryCashResult(user.getAccount()));
        user.write(WvsContext.statChanged(Map.of(Stat.MONEY, im.getMoney()), false));
        // 原版客户端 0x1D handler 不更新 m_aSaleItem，必须主动推送"我的出售"列表（Godot 端重复推送无害）
        user.write(ITCPacket.userSaleListResult(auctionManager.getUserSaleListings(user.getCharacterId())));
    }

    /**
     * 0x05 - RequestList: browse/search listings.
     * <p>两种客户端字段语义一致（matching reference: OnChangedCategorySub_5739A0）：
     * category(4)=板块(1=贩卖/2=购买/3=竞标) + subCategory(4)=物品类型过滤(0=全部,1~4) + page(4)
     * + sortType(1) + sortColumn(1) + searchOption(4: 0=角色名/1=物品) + searchText(str)。
     * 唯一差异是页码基准：Godot 从 1 开始，原版从 0 开始（首次浏览恒为 0，可据此识别客户端）。
     */
    private static void handleRequestList(User user, InPacket inPacket, AuctionManager auctionManager) {
        final int board = inPacket.decodeInt();
        final int itemType = inPacket.decodeInt();
        final int page = inPacket.decodeInt();
        final byte sortType = inPacket.decodeByte();
        final byte sortColumn = inPacket.decodeByte();
        final int searchOption = inPacket.decodeInt();
        final String searchText = inPacket.decodeString();

        if (page == 0) {
            user.setOriginalITCClient(true); // Godot 端 CurrentPage 恒 ≥1
        }
        final boolean original = user.isOriginalITCClient();
        final int serverPage = Math.max(1, original ? page + 1 : page);

        final SearchResult result = auctionManager.searchListings(
                itemType, board, serverPage, PAGE_SIZE, sortType, sortColumn, searchOption, searchText);
        // 回显客户端自己的元数据（原版为 0-based 页码），供其恢复 m_nCurPage 等状态
        final SearchResult echoed = new SearchResult(result.getListings(), result.getTotalCount(),
                page, PAGE_SIZE, board, itemType, sortType, sortColumn);

        user.write(ITCPacket.queryCashResult(user.getAccount()));
        // 空列表是合法状态（原版显示"无登记内容。"），统一回 Done(count=0)；Failed 仅用于真实异常
        user.write(ITCPacket.listResult(echoed, searchText != null && !searchText.isEmpty()));
    }

    /**
     * 0x06 - SearchList: 搜索按钮（matching reference: CITCWnd_Tab::OnButtonClicked_584B10，仅原版客户端发送）。
     * Packet: subOp(1), category(4)=板块, subCategory(4)=类型过滤, page(4)=0, searchOption(4), searchText(str) — 无排序字节。
     */
    private static void handleSearchList(User user, InPacket inPacket, AuctionManager auctionManager) {
        final int board = inPacket.decodeInt();
        final int itemType = inPacket.decodeInt();
        final int page = inPacket.decodeInt();
        final int searchOption = inPacket.decodeInt();
        final String searchText = inPacket.decodeString();

        user.setOriginalITCClient(true);
        final SearchResult result = auctionManager.searchListings(
                itemType, board, Math.max(1, page + 1), PAGE_SIZE, (byte) 1, (byte) 1, searchOption, searchText);
        final SearchResult echoed = new SearchResult(result.getListings(), result.getTotalCount(),
                page, PAGE_SIZE, board, itemType, (byte) 1, (byte) 1);

        user.write(ITCPacket.queryCashResult(user.getAccount()));
        // 0x17 OnGetSearchITCListDone：totalCount + count + category + subCategory + page + ITCITEM×count（无排序/尾部字节）
        user.write(ITCPacket.listResult(echoed, true));
    }

    /**
     * 0x07 - CancelSaleItem: cancel an active listing.
     * Packet: subOp(1), listingId(4)
     */
    private static void handleCancelSaleItem(User user, InPacket inPacket, AuctionManager auctionManager) {
        final int listingId = inPacket.decodeInt();

        if (auctionManager.cancelListing(user, listingId)) {
            user.write(ITCPacket.cancelDone(listingId));
        } else {
            user.write(ITCPacket.cancelFailed());
        }
    }

    /**
     * 0x08 - MoveItemToInventory: claim item or revenue from a completed listing.
     * Packet: subOp(1), listingId(4)
     */
    private static void handleMoveItemToInventory(User user, InPacket inPacket, AuctionManager auctionManager) {
        final int listingId = inPacket.decodeInt();

        final AuctionManager.ClaimResult result = auctionManager.moveItemToInventory(user, listingId);
        if (!result.success()) {
            user.write(ITCPacket.moveItemFailed());
            return;
        }
        if (user.isOriginalITCClient()) {
            // 原版客户端读 tab(4)+slotNo(4)：切换背包页并高亮槽位（matching reference: OnMoveITCPurchaseItemLtoSDone_5760A0）
            final List<InventoryOperation> itemOps = result.itemOps();
            if (itemOps != null && !itemOps.isEmpty()) {
                final InventoryOperation op = itemOps.get(0);
                user.write(WvsContext.inventoryOperation(itemOps, false));
                user.write(ITCPacket.moveItemDoneOriginal(op.getInventoryType().getValue(), op.getPosition()));
            } else {
                // 金币收入无物品入包（原版客户端此路径无 UI 入口），发默认页/槽位避免游标错位
                user.write(ITCPacket.moveItemDoneOriginal(1, 0));
            }
        } else {
            final List<InventoryOperation> itemOps = result.itemOps();
            if (itemOps != null && !itemOps.isEmpty()) {
                user.write(WvsContext.inventoryOperation(itemOps, false));
            }
            user.write(ITCPacket.moveItemDone(listingId));
        }
        user.write(ITCPacket.queryCashResult(user.getAccount()));
        user.write(WvsContext.statChanged(Map.of(Stat.MONEY, user.getInventoryManager().getMoney()), false));
    }

    /**
     * 0x09 - MySaleList: list all listings where this user is the seller.
     * Packet: subOp(1)
     * <p>空列表是合法状态（matching reference: OnGetUserSaleItemDone_576870 — count=0 时清空 m_aSaleItem 并重绘，无提示），
     * 必须回 Done(count=0)；Failed(0x24) 仅用于真实异常。
     */
    private static void handleMySaleList(User user, AuctionManager auctionManager) {
        try {
            final List<AuctionListing> listings = auctionManager.getUserSaleListings(user.getCharacterId());
            user.write(ITCPacket.userSaleListResult(listings));
        } catch (Exception e) {
            log.error("Failed to get user sale listings for character {}", user.getCharacterId(), e);
            user.write(ITCPacket.userSaleListFailed());
        }
    }

    /**
     * 0x0B - MyPurchaseList: list all listings where this user is the bidder.
     * Packet: subOp(1)
     * <p>空列表是合法状态（matching reference: OnGetUserPurchaseItemDone_576CF0 — count=0 时清空并重绘，无提示），
     * 必须回 Done(count=0)；Failed(0x22) 仅用于真实异常。
     */
    private static void handleMyPurchaseList(User user, AuctionManager auctionManager) {
        try {
            final List<AuctionListing> listings = auctionManager.getUserPurchaseListings(user.getCharacterId());
            user.write(ITCPacket.userPurchaseListResult(listings));
        } catch (Exception e) {
            log.error("Failed to get user purchase listings for character {}", user.getCharacterId(), e);
            user.write(ITCPacket.userPurchaseListFailed());
        }
    }

    /**
     * 0x13 - Bid: place a bid on an auction item.
     * Packet: subOp(1), listingId(4), bidPrice(4), bidRange(4)
     */
    private static void handleBid(User user, InPacket inPacket, AuctionManager auctionManager) {
        final int listingId = inPacket.decodeInt();
        final int bidPrice = inPacket.decodeInt();
        inPacket.decodeInt(); // bidRange

        if (auctionManager.bidAuction(user, listingId, bidPrice)) {
            auctionManager.getListingById(listingId).ifPresentOrElse(
                    listing -> user.write(user.isOriginalITCClient()
                            // 原版客户端只读 byte+itemId+price+ftTime（matching reference: OnSuccessBidInfoResult_577000）
                            ? ITCPacket.bidResultCompact(0, listing.getItemId(), listing.getCurrentBid(), Instant.now())
                            : ITCPacket.bidResult(listing)),
                    () -> user.write(ITCPacket.bidFailed()));
        } else {
            user.write(ITCPacket.bidFailed());
        }
    }

    /**
     * 0x10 - BuyItem: direct purchase of a sale item.
     * Packet: subOp(1), listingId(4)
     */
    private static void handleBuyItem(User user, InPacket inPacket, AuctionManager auctionManager) {
        final int listingId = inPacket.decodeInt();

        Optional<List<InventoryOperation>> buyResult = auctionManager.buyItem(user, listingId);
        if (buyResult.isPresent()) {
            user.write(WvsContext.inventoryOperation(buyResult.get(), false));
            user.write(ITCPacket.buyDone(listingId));
            user.write(ITCPacket.queryCashResult(user.getAccount()));
        } else {
            user.write(ITCPacket.buyFailed());
        }
    }

    /**
     * 0x14 - BuyAuctionImm: buyout an auction item.
     * Packet: subOp(1), listingId(4)
     */
    private static void handleBuyAuctionImm(User user, InPacket inPacket, AuctionManager auctionManager) {
        final int listingId = inPacket.decodeInt();

        Optional<List<InventoryOperation>> buyoutResult = auctionManager.buyAuctionImm(user, listingId);
        if (buyoutResult.isPresent()) {
            user.write(WvsContext.inventoryOperation(buyoutResult.get(), false));
            user.write(ITCPacket.buyDone(listingId));
            user.write(ITCPacket.queryCashResult(user.getAccount()));
        } else {
            user.write(ITCPacket.buyFailed());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Original client register packet decoding
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * 解析原版客户端登记请求头部（head = type(1)+itemID 低 3 字节，已消费）：
     * 依次消费 itemID 高字节与 GW_ItemSlotBase::Encode 剩余字段、slotNo/count。
     *
     * @return {type, itemId, slotNo, count}；解析失败返回 null
     */
    private static int[] decodeOriginalRegisterHead(InPacket inPacket, int head) {
        final int type = head & 0xFF;
        if (type < 1 || type > 3) {
            return null;
        }
        final int itemId;
        try {
            itemId = ((head >>> 8) & 0xFFFFFF) | (inPacket.decodeByte() << 24);
            skipOriginalItemBody(inPacket, type, itemId);
        } catch (RuntimeException e) {
            log.error("[ITC] Failed to decode original item body: {}", e.toString());
            return null;
        }
        final int slotNo = inPacket.decodeInt();
        final int count = inPacket.decodeInt();
        if (slotNo <= 0 || count < 1) {
            return null;
        }
        return new int[]{type, itemId, slotNo, count};
    }

    /**
     * 消费 GW_ItemSlotBase::Encode 中 itemID(4) 之后的全部字段（matching reference:
     * RawDecode_4F5310 base / 4F87A0 bundle / 4F8360 equip / 4F5750 pet）。
     */
    private static void skipOriginalItemBody(InPacket p, int type, int itemId) {
        final boolean cash = p.decodeByte() != 0;
        if (cash) {
            p.decodeLong(); // liCashItemSN
        }
        p.decodeLong(); // dateExpire
        switch (type) {
            case 1 -> { // equip
                p.decodeByte(); // RUC
                p.decodeByte(); // CUC
                for (int i = 0; i < 15; i++) {
                    p.decodeShort(); // STR, DEX, INT, LUK, MaxHP, MaxMP, PAD, MAD, PDD, MDD, ACC, EVA, Craft, Speed, Jump
                }
                p.decodeString(); // sTitle
                p.decodeShort(); // nAttribute
                p.decodeByte(); // levelUpType
                p.decodeByte(); // level
                p.decodeInt(); // exp
                p.decodeInt(); // durability
                p.decodeInt(); // IUC
                p.decodeByte(); // grade
                p.decodeByte(); // CHUC
                for (int i = 0; i < 5; i++) {
                    p.decodeShort(); // option1-3, socket1-2
                }
                if (!cash) {
                    p.decodeLong(); // liSN
                }
                p.decodeLong(); // ftEquipped
                p.decodeInt(); // nPrevBonusExpRate
            }
            case 2 -> { // bundle
                p.decodeShort(); // quantity
                p.decodeString(); // sTitle
                p.decodeShort(); // nAttribute
                if (itemId / 10000 == 207 || itemId / 10000 == 233) {
                    p.decodeLong(); // liSN
                }
            }
            case 3 -> { // pet
                p.decodeArray(13); // sPetName（定长）
                p.decodeByte(); // level
                p.decodeShort(); // tameness
                p.decodeByte(); // repleteness
                p.decodeLong(); // dateDead
                p.decodeShort(); // petAttribute
                p.decodeShort(); // usPetSkill
                p.decodeInt(); // remainLife
                p.decodeShort(); // nAttribute
            }
        }
    }

    /** 登记失败响应：Godot 客户端不读原因字节（保持 0）；原版客户端按 NoticeFailReason 显示对应提示。 */
    private static void sendRegisterFailed(User user, int reason) {
        user.write(ITCPacket.registerFailed(user.isOriginalITCClient() ? reason : 0));
    }
}
