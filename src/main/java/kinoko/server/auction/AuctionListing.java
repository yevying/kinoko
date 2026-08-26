package kinoko.server.auction;

import kinoko.world.item.Item;

import java.time.Instant;

public final class AuctionListing {
    private int listingId;
    private int sellerId;
    private String sellerName;
    private Item item;
    private int itemId;
    private int itemType; // 1=equip, 2=consume, 3=install, 4=etc, 5=cash
    private int price; // direct sale price (MP) / auction starting price
    private int buyoutPrice; // auction buyout price (MP), 0 = no buyout
    private int currentBid; // current highest bid
    private int bidderId; // current highest bidder character id
    private String bidderName; // current highest bidder name
    private int bidCount; // number of bids
    private int bidRange; // bid increment for auction
    private AuctionState processStatus; // 1=ACTIVE, 2=SOLD, 3=EXPIRED, 4=CANCELLED
    private Instant createdAt;
    private Instant expiresAt;
    private boolean claimed; // whether item/money has been claimed

    public AuctionListing() {
        this.processStatus = AuctionState.ACTIVE;
        this.createdAt = Instant.now();
        this.claimed = false;
        this.bidderName = "";
        this.bidRange = 0;
    }

    // Getters & Setters

    public int getListingId() {
        return listingId;
    }

    public void setListingId(int listingId) {
        this.listingId = listingId;
    }

    public int getSellerId() {
        return sellerId;
    }

    public void setSellerId(int sellerId) {
        this.sellerId = sellerId;
    }

    public String getSellerName() {
        return sellerName;
    }

    public void setSellerName(String sellerName) {
        this.sellerName = sellerName;
    }

    public Item getItem() {
        return item;
    }

    public void setItem(Item item) {
        this.item = item;
    }

    public int getItemId() {
        return itemId;
    }

    public void setItemId(int itemId) {
        this.itemId = itemId;
    }

    public int getItemType() {
        return itemType;
    }

    public void setItemType(int itemType) {
        this.itemType = itemType;
    }

    public int getPrice() {
        return price;
    }

    public void setPrice(int price) {
        this.price = price;
    }

    public int getBuyoutPrice() {
        return buyoutPrice;
    }

    public void setBuyoutPrice(int buyoutPrice) {
        this.buyoutPrice = buyoutPrice;
    }

    public int getCurrentBid() {
        return currentBid;
    }

    public void setCurrentBid(int currentBid) {
        this.currentBid = currentBid;
    }

    public int getBidderId() {
        return bidderId;
    }

    public void setBidderId(int bidderId) {
        this.bidderId = bidderId;
    }

    public String getBidderName() {
        return bidderName;
    }

    public void setBidderName(String bidderName) {
        this.bidderName = bidderName;
    }

    public int getBidCount() {
        return bidCount;
    }

    public void setBidCount(int bidCount) {
        this.bidCount = bidCount;
    }

    public int getBidRange() {
        return bidRange;
    }

    public void setBidRange(int bidRange) {
        this.bidRange = bidRange;
    }

    public AuctionState getProcessStatus() {
        return processStatus;
    }

    public void setProcessStatus(AuctionState processStatus) {
        this.processStatus = processStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isClaimed() {
        return claimed;
    }

    public void setClaimed(boolean claimed) {
        this.claimed = claimed;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isAuction() {
        return buyoutPrice > 0 || currentBid > 0;
    }

    public boolean isDirectSale() {
        return !isAuction();
    }

    /**
     * Calculate the net revenue for the seller after commission.
     * Commission = max(price * commissionRate / 100, commissionBase) in mesos.
     */
    public int getRevenueAfterCommission(int commissionRate, int commissionBase) {
        int gross = currentBid > 0 ? currentBid : price;
        int commission = Math.max(gross * commissionRate / 100, commissionBase);
        return Math.max(gross - commission, 0);
    }
}