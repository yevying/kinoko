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
import kinoko.world.user.Account;
import kinoko.world.user.User;
import kinoko.world.user.stat.Stat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ITCHandler {
    private static final Logger log = LogManager.getLogger(ITCHandler.class);

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
     * Packet: subOp(1), nTI(4), nPOS(2), price(4), regFeeOption(1), feeOption(1)
     */
    private static void handleRegisterSaleItem(User user, InPacket inPacket, AuctionManager auctionManager) {
        final int nTI = inPacket.decodeInt();
        final short nPOS = inPacket.decodeShort();
        final int price = inPacket.decodeInt();
        inPacket.decodeByte(); // regFeeOption
        inPacket.decodeByte(); // feeOption

        final InventoryType inventoryType = InventoryType.getByValue(nTI);
        if (inventoryType == null || inventoryType == InventoryType.EQUIPPED) {
            user.write(ITCPacket.registerFailed());
            return;
        }

        // Find item in inventory
        final InventoryManager im = user.getInventoryManager();
        final Inventory inventory = im.getInventoryByType(inventoryType);
        final Item item = inventory.getItem(nPOS);
        if (item == null || GameConstants.isITCTradeLimitItem(item)) {
            user.write(ITCPacket.registerFailed());
            return;
        }

        // Remove item from inventory first
        final Optional<InventoryOperation> removeResult = im.removeItem(nPOS, item, item.getQuantity());
        if (removeResult.isEmpty()) {
            user.write(ITCPacket.registerFailed());
            return;
        }

        // Register through AuctionManager
        final Optional<AuctionListing> listingResult = auctionManager.registerSaleItem(user, item, price);
        if (listingResult.isEmpty()) {
            // Rollback: restore item to inventory
            inventory.putItem(nPOS, item);
            user.write(ITCPacket.registerFailed());
            return;
        }

        // Send inventory operation and success
        user.write(WvsContext.inventoryOperation(removeResult.get(), false));
        user.write(ITCPacket.registerDone(listingResult.get()));
        user.write(ITCPacket.queryCashResult(user.getAccount()));
        user.write(WvsContext.statChanged(Map.of(Stat.MONEY, im.getMoney()), false));
    }

    /**
     * 0x05 - RequestList: browse/search listings.
     * Packet: subOp(1), category(4), subCategory(4), page(4), sortType(1), sortColumn(1), searchOption(4), searchText(string)
     */
    private static void handleRequestList(User user, InPacket inPacket, AuctionManager auctionManager) {
        final int category = inPacket.decodeInt();
        final int subCategory = inPacket.decodeInt();
        final int page = inPacket.decodeInt();
        final byte sortType = inPacket.decodeByte();
        final byte sortColumn = inPacket.decodeByte();
        final int searchOption = inPacket.decodeInt();
        final String searchText = inPacket.decodeString();

        final boolean isSearch = searchText != null && !searchText.isEmpty();
        final int pageSize = 16;
        final SearchResult result = auctionManager.searchListings(
                category, subCategory, page, pageSize, sortType, sortColumn, searchOption, searchText);

        if (result.getListings().isEmpty()) {
            user.write(ITCPacket.listFailed(isSearch));
            return;
        }

        user.write(ITCPacket.queryCashResult(user.getAccount()));
        user.write(ITCPacket.listResult(result, isSearch));
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

        if (auctionManager.moveItemToInventory(user, listingId)) {
            user.write(ITCPacket.moveItemDone(listingId));
            user.write(ITCPacket.queryCashResult(user.getAccount()));
            user.write(WvsContext.statChanged(Map.of(Stat.MONEY, user.getInventoryManager().getMoney()), false));
        } else {
            user.write(ITCPacket.moveItemFailed());
        }
    }

    /**
     * 0x09 - MySaleList: list all listings where this user is the seller.
     * Packet: subOp(1)
     */
    private static void handleMySaleList(User user, AuctionManager auctionManager) {
        final List<AuctionListing> listings = auctionManager.getUserSaleListings(user.getCharacterId());
        if (listings.isEmpty()) {
            user.write(ITCPacket.userSaleListFailed());
            return;
        }
        user.write(ITCPacket.userSaleListResult(listings));
    }

    /**
     * 0x0B - MyPurchaseList: list all listings where this user is the bidder.
     * Packet: subOp(1)
     */
    private static void handleMyPurchaseList(User user, AuctionManager auctionManager) {
        final List<AuctionListing> listings = auctionManager.getUserPurchaseListings(user.getCharacterId());
        if (listings.isEmpty()) {
            user.write(ITCPacket.userPurchaseListFailed());
            return;
        }
        user.write(ITCPacket.userPurchaseListResult(listings));
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
                    listing -> user.write(ITCPacket.bidResult(listing)),
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
     * 0x12 - RegisterAuctionItem: register an auction item.
     * Packet: subOp(1), nTI(4), nPOS(2), startPrice(4), buyoutPrice(4), duration(1), feeOption(1), bidRange(4)
     */
    private static void handleRegisterAuctionItem(User user, InPacket inPacket, AuctionManager auctionManager) {
        final int nTI = inPacket.decodeInt();
        final short nPOS = inPacket.decodeShort();
        final int startPrice = inPacket.decodeInt();
        final int buyoutPrice = inPacket.decodeInt();
        final byte duration = inPacket.decodeByte();
        inPacket.decodeByte(); // feeOption
        final int bidRange = inPacket.decodeInt();

        final InventoryType inventoryType = InventoryType.getByValue(nTI);
        if (inventoryType == null || inventoryType == InventoryType.EQUIPPED) {
            user.write(ITCPacket.registerFailed());
            return;
        }

        // Find item in inventory
        final InventoryManager im = user.getInventoryManager();
        final Inventory inventory = im.getInventoryByType(inventoryType);
        final Item item = inventory.getItem(nPOS);
        if (item == null || GameConstants.isITCTradeLimitItem(item)) {
            user.write(ITCPacket.registerFailed());
            return;
        }

        // Remove item from inventory first
        final Optional<InventoryOperation> removeResult = im.removeItem(nPOS, item, item.getQuantity());
        if (removeResult.isEmpty()) {
            user.write(ITCPacket.registerFailed());
            return;
        }

        // Register through AuctionManager
        final Optional<AuctionListing> listingResult = auctionManager.registerAuctionItem(
                user, item, startPrice, buyoutPrice, duration, bidRange);
        if (listingResult.isEmpty()) {
            // Rollback: restore item to inventory
            inventory.putItem(nPOS, item);
            user.write(ITCPacket.registerFailed());
            return;
        }

        // Send inventory operation and success
        user.write(WvsContext.inventoryOperation(removeResult.get(), false));
        user.write(ITCPacket.registerDone(listingResult.get()));
        user.write(ITCPacket.queryCashResult(user.getAccount()));
        user.write(WvsContext.statChanged(Map.of(Stat.MONEY, im.getMoney()), false));
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
}