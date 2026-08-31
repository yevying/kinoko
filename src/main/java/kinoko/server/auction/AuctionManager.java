package kinoko.server.auction;

import kinoko.database.DatabaseManager;
import kinoko.world.GameConstants;
import kinoko.world.item.InventoryManager;
import kinoko.world.item.InventoryOperation;
import kinoko.world.item.Item;
import kinoko.world.user.Account;
import kinoko.world.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AuctionManager {
    private static final Logger log = LogManager.getLogger(AuctionManager.class);

    private static AuctionManager instance;
    private final ConcurrentHashMap<Integer, AuctionListing> activeListings = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private AuctionManager() {
        List<AuctionListing> active = DatabaseManager.auctionAccessor().getActiveListings();
        for (AuctionListing listing : active) {
            activeListings.put(listing.getListingId(), listing);
        }
        log.info("Loaded {} active ITC listings", activeListings.size());

        scheduler.scheduleAtFixedRate(this::checkExpired, 60, 60, TimeUnit.SECONDS);
    }

    public static synchronized AuctionManager getInstance() {
        if (instance == null) {
            instance = new AuctionManager();
        }
        return instance;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Core operations
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Register a direct sale item in ITC.
     */
    public Optional<AuctionListing> registerSaleItem(User user, Item item, int price) {
        if (GameConstants.isITCTradeLimitItem(item)) {
            return Optional.empty();
        }
        if (price < GameConstants.ITC_MIN_PRICE) {
            return Optional.empty();
        }
        InventoryManager im = user.getInventoryManager();
        if (!im.canAddMoney(-GameConstants.ITC_REGISTER_FEE_MESO)) {
            return Optional.empty();
        }
        if (!im.addMoney(-GameConstants.ITC_REGISTER_FEE_MESO)) {
            return Optional.empty();
        }
        Optional<Integer> listingIdResult = DatabaseManager.idAccessor().nextListingId();
        if (listingIdResult.isEmpty()) {
            return Optional.empty();
        }
        AuctionListing listing = new AuctionListing();
        listing.setListingId(listingIdResult.get());
        listing.setSellerId(user.getCharacterId());
        listing.setSellerName(user.getCharacterName());
        listing.setItem(item);
        listing.setItemId(item.getItemId());
        listing.setItemType(GameConstants.getITCCategory(item.getItemId()));
        listing.setPrice(price);
        listing.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        listing.setCurrentBid(0);
        listing.setBuyoutPrice(0);
        listing.setBidRange(0);

        if (!DatabaseManager.auctionAccessor().newListing(listing)) {
            return Optional.empty();
        }
        activeListings.put(listing.getListingId(), listing);
        return Optional.of(listing);
    }

    /**
     * Register an auction item in ITC.
     */
    public Optional<AuctionListing> registerAuctionItem(User user, Item item, int startPrice,
                                                         int buyoutPrice, int durationHours, int bidRange) {
        if (GameConstants.isITCTradeLimitItem(item)) {
            return Optional.empty();
        }
        if (startPrice < GameConstants.ITC_MIN_PRICE) {
            return Optional.empty();
        }
        if (durationHours < GameConstants.ITC_AUCTION_DURATION_MIN || durationHours > GameConstants.ITC_AUCTION_DURATION_MAX) {
            return Optional.empty();
        }
        if (bidRange <= 0) {
            return Optional.empty();
        }
        InventoryManager im = user.getInventoryManager();
        if (!im.canAddMoney(-GameConstants.ITC_REGISTER_FEE_MESO)) {
            return Optional.empty();
        }
        if (!im.addMoney(-GameConstants.ITC_REGISTER_FEE_MESO)) {
            return Optional.empty();
        }
        Optional<Integer> listingIdResult = DatabaseManager.idAccessor().nextListingId();
        if (listingIdResult.isEmpty()) {
            return Optional.empty();
        }
        AuctionListing listing = new AuctionListing();
        listing.setListingId(listingIdResult.get());
        listing.setSellerId(user.getCharacterId());
        listing.setSellerName(user.getCharacterName());
        listing.setItem(item);
        listing.setItemId(item.getItemId());
        listing.setItemType(GameConstants.getITCCategory(item.getItemId()));
        listing.setPrice(startPrice);
        listing.setBuyoutPrice(buyoutPrice);
        listing.setCurrentBid(0);
        listing.setBidRange(bidRange);
        listing.setExpiresAt(Instant.now().plus(durationHours, ChronoUnit.HOURS));

        if (!DatabaseManager.auctionAccessor().newListing(listing)) {
            return Optional.empty();
        }
        activeListings.put(listing.getListingId(), listing);
        return Optional.of(listing);
    }

    /**
     * Direct purchase of a sale item. Returns the inventory operations for the buyer's new item.
     */
    public Optional<List<InventoryOperation>> buyItem(User buyer, int listingId) {
        AuctionListing listing = activeListings.get(listingId);
        if (listing == null) {
            return Optional.empty();
        }
        if (listing.getProcessStatus() != AuctionState.ACTIVE || listing.isExpired()) {
            return Optional.empty();
        }
        if (listing.getSellerId() == buyer.getCharacterId()) {
            return Optional.empty();
        }
        if (listing.isAuction()) {
            return Optional.empty();
        }
        Account account = buyer.getAccount();
        int price = listing.getPrice();
        if (account.getMaplePoint() < price) {
            return Optional.empty();
        }
        InventoryManager im = buyer.getInventoryManager();
        if (!im.canAddItem(listing.getItem())) {
            return Optional.empty();
        }
        // Deduct buyer Maple Points
        account.setMaplePoint(account.getMaplePoint() - price);
        DatabaseManager.accountAccessor().saveAccount(account);

        // Add item to buyer's inventory
        Item buyItem = new Item(listing.getItem());
        buyItem.setItemSn(buyer.getNextItemSn());
        Optional<List<InventoryOperation>> addResult = im.addItem(buyItem);
        if (addResult.isEmpty()) {
            // Rollback MP
            account.setMaplePoint(account.getMaplePoint() + price);
            DatabaseManager.accountAccessor().saveAccount(account);
            return Optional.empty();
        }

        // Update listing
        listing.setProcessStatus(AuctionState.SOLD);
        listing.setClaimed(false);
        DatabaseManager.auctionAccessor().updateListing(listing);
        activeListings.remove(listingId);

        log.info("ITC buy: {} bought listing {} from {} for {} MP",
                buyer.getCharacterName(), listingId, listing.getSellerName(), price);
        return addResult;
    }

    /**
     * Place a bid on an auction item.
     */
    public boolean bidAuction(User bidder, int listingId, int bidAmount) {
        AuctionListing listing = activeListings.get(listingId);
        if (listing == null) {
            return false;
        }
        if (listing.getProcessStatus() != AuctionState.ACTIVE || listing.isExpired()) {
            return false;
        }
        if (listing.getSellerId() == bidder.getCharacterId()) {
            return false;
        }
        int minBid;
        if (listing.getCurrentBid() == 0) {
            minBid = listing.getPrice();
        } else {
            minBid = listing.getCurrentBid() + listing.getBidRange();
        }
        if (bidAmount < minBid) {
            return false;
        }
        Account account = bidder.getAccount();
        if (account.getMaplePoint() < bidAmount) {
            return false;
        }
        // Refund previous bidder
        if (listing.getBidderId() != 0) {
            refundBidder(listing.getBidderId(), listing.getCurrentBid());
        }
        // Deduct bidder Maple Points
        account.setMaplePoint(account.getMaplePoint() - bidAmount);
        DatabaseManager.accountAccessor().saveAccount(account);

        // Update listing
        listing.setCurrentBid(bidAmount);
        listing.setBidderId(bidder.getCharacterId());
        listing.setBidderName(bidder.getCharacterName());
        listing.setBidCount(listing.getBidCount() + 1);
        DatabaseManager.auctionAccessor().updateListing(listing);

        log.info("ITC bid: {} bid {} MP on listing {}",
                bidder.getCharacterName(), bidAmount, listingId);
        return true;
    }

    /**
     * Buyout an auction item at the buyout price. Returns the inventory operations for the buyer's new item.
     */
    public Optional<List<InventoryOperation>> buyAuctionImm(User buyer, int listingId) {
        AuctionListing listing = activeListings.get(listingId);
        if (listing == null) {
            return Optional.empty();
        }
        if (listing.getProcessStatus() != AuctionState.ACTIVE || listing.isExpired()) {
            return Optional.empty();
        }
        if (listing.getSellerId() == buyer.getCharacterId()) {
            return Optional.empty();
        }
        if (listing.getBuyoutPrice() <= 0) {
            return Optional.empty();
        }
        Account account = buyer.getAccount();
        int buyoutPrice = listing.getBuyoutPrice();
        if (account.getMaplePoint() < buyoutPrice) {
            return Optional.empty();
        }
        InventoryManager im = buyer.getInventoryManager();
        if (!im.canAddItem(listing.getItem())) {
            return Optional.empty();
        }
        // Refund previous bidder if any
        if (listing.getBidderId() != 0) {
            refundBidder(listing.getBidderId(), listing.getCurrentBid());
        }
        // Deduct buyer Maple Points
        account.setMaplePoint(account.getMaplePoint() - buyoutPrice);
        DatabaseManager.accountAccessor().saveAccount(account);

        // Add item to buyer's inventory
        Item buyItem = new Item(listing.getItem());
        buyItem.setItemSn(buyer.getNextItemSn());
        Optional<List<InventoryOperation>> addResult = im.addItem(buyItem);
        if (addResult.isEmpty()) {
            account.setMaplePoint(account.getMaplePoint() + buyoutPrice);
            DatabaseManager.accountAccessor().saveAccount(account);
            return Optional.empty();
        }

        // Update listing
        listing.setProcessStatus(AuctionState.SOLD);
        listing.setClaimed(false);
        listing.setCurrentBid(buyoutPrice);
        DatabaseManager.auctionAccessor().updateListing(listing);
        activeListings.remove(listingId);

        log.info("ITC buyout: {} bought listing {} from {} for {} MP",
                buyer.getCharacterName(), listingId, listing.getSellerName(), buyoutPrice);
        return addResult;
    }

    /**
     * Cancel an active listing (seller reclaims item).
     */
    public boolean cancelListing(User user, int listingId) {
        AuctionListing listing = activeListings.get(listingId);
        if (listing == null) {
            return false;
        }
        if (listing.getSellerId() != user.getCharacterId()) {
            return false;
        }
        if (listing.getProcessStatus() != AuctionState.ACTIVE) {
            return false;
        }
        if (listing.getBidCount() > 0) {
            return false;
        }
        listing.setProcessStatus(AuctionState.CANCELLED);
        DatabaseManager.auctionAccessor().updateListing(listing);
        activeListings.remove(listingId);

        log.info("ITC cancel: {} cancelled listing {}", user.getCharacterName(), listingId);
        return true;
    }

    /**
     * ITC 领取结果。{@code itemOps} 非 null 表示有物品已放回背包（用于发送 InventoryOperation，
     * 以及原版客户端 0x27 响应的 tab/slotNo）。
     */
    public record ClaimResult(boolean success, List<InventoryOperation> itemOps) {
        public static final ClaimResult FAILURE = new ClaimResult(false, null);
        /** 领取成功但无物品入包（金币收入）。 */
        public static final ClaimResult REVENUE_CLAIMED = new ClaimResult(true, null);
    }

    /**
     * Claim an item or revenue from a listing back to inventory.
     */
    public ClaimResult moveItemToInventory(User user, int listingId) {
        AuctionListing listing = DatabaseManager.auctionAccessor().getListingById(listingId).orElse(null);
        if (listing == null) {
            return ClaimResult.FAILURE;
        }
        boolean isSeller = listing.getSellerId() == user.getCharacterId();
        if (!isSeller) {
            return ClaimResult.FAILURE;
        }
        if (listing.isClaimed()) {
            return ClaimResult.FAILURE;
        }
        if (listing.getProcessStatus() == AuctionState.ACTIVE) {
            return ClaimResult.FAILURE;
        }
        InventoryManager im = user.getInventoryManager();
        if (listing.getProcessStatus() == AuctionState.SOLD) {
            // Seller claims revenue
            int revenue = listing.getRevenueAfterCommission(
                    GameConstants.ITC_COMMISSION_RATE, GameConstants.ITC_COMMISSION_BASE);
            if (!im.canAddMoney(revenue)) {
                return ClaimResult.FAILURE;
            }
            im.addMoney(revenue);
            listing.setClaimed(true);
            DatabaseManager.auctionAccessor().updateListing(listing);
            log.info("ITC claim revenue: {} claimed {} mesos from listing {}",
                    user.getCharacterName(), revenue, listingId);
            return ClaimResult.REVENUE_CLAIMED;
        } else if (listing.getProcessStatus() == AuctionState.CANCELLED ||
                listing.getProcessStatus() == AuctionState.EXPIRED) {
            // Seller claims item back
            if (!im.canAddItem(listing.getItem())) {
                return ClaimResult.FAILURE;
            }
            Item returnItem = new Item(listing.getItem());
            returnItem.setItemSn(user.getNextItemSn());
            Optional<List<InventoryOperation>> addResult = im.addItem(returnItem);
            if (addResult.isEmpty()) {
                return ClaimResult.FAILURE;
            }
            listing.setClaimed(true);
            DatabaseManager.auctionAccessor().updateListing(listing);
            log.info("ITC claim item: {} claimed item back from listing {}",
                    user.getCharacterName(), listingId);
            return new ClaimResult(true, addResult.get());
        }
        return ClaimResult.FAILURE;
    }

    /**
     * Search/browse listings with filtering and pagination.
     */
    public SearchResult searchListings(int itemType, int board, int page, int pageSize,
                                       byte sortType, byte sortColumn, int searchOption, String searchText) {
        return DatabaseManager.auctionAccessor().searchListings(
                itemType, board, page, pageSize, sortType, sortColumn, searchOption, searchText);
    }

    /**
     * Get all listings for a seller (any status).
     */
    public List<AuctionListing> getUserSaleListings(int sellerId) {
        return DatabaseManager.auctionAccessor().getListingsBySeller(sellerId);
    }

    /**
     * Get all listings where this user is a bidder.
     */
    public List<AuctionListing> getUserPurchaseListings(int bidderId) {
        return DatabaseManager.auctionAccessor().getListingsByBidder(bidderId);
    }

    /**
     * Get a single listing by ID.
     */
    public Optional<AuctionListing> getListingById(int listingId) {
        AuctionListing cached = activeListings.get(listingId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return DatabaseManager.auctionAccessor().getListingById(listingId);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------------------------------------------

    private void checkExpired() {
        Instant now = Instant.now();
        for (AuctionListing listing : activeListings.values()) {
            if (listing.getProcessStatus() == AuctionState.ACTIVE && listing.getExpiresAt().isBefore(now)) {
                if (listing.getBidCount() > 0) {
                    listing.setProcessStatus(AuctionState.SOLD);
                    log.info("ITC expiry: listing {} expired with bids, sold to {} for {} MP",
                            listing.getListingId(), listing.getBidderName(), listing.getCurrentBid());
                } else {
                    listing.setProcessStatus(AuctionState.EXPIRED);
                    log.info("ITC expiry: listing {} expired with no bids", listing.getListingId());
                }
                DatabaseManager.auctionAccessor().updateListing(listing);
                activeListings.remove(listing.getListingId());
            }
        }
    }

    private void refundBidder(int characterId, int amount) {
        DatabaseManager.characterAccessor().getAccountIdByCharacterId(characterId).ifPresent(accountId -> {
            DatabaseManager.accountAccessor().getAccountById(accountId).ifPresent(account -> {
                account.setMaplePoint(account.getMaplePoint() + amount);
                DatabaseManager.accountAccessor().saveAccount(account);
                log.info("ITC refund: refunded {} MP to character {}", amount, characterId);
            });
        });
    }
}