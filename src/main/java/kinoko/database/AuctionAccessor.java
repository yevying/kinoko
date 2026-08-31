package kinoko.database;

import kinoko.server.auction.AuctionListing;
import kinoko.server.auction.SearchResult;

import java.util.List;
import java.util.Optional;

public interface AuctionAccessor {
    Optional<AuctionListing> getListingById(int listingId);

    List<AuctionListing> getActiveListings();

    List<AuctionListing> getListingsBySeller(int sellerId);

    List<AuctionListing> getListingsByBidder(int bidderId);

    List<AuctionListing> getExpiredListings();

    /**
     * Search/browse active listings.
     *
     * @param itemType 物品类型过滤（0=全部, 1=equip, 2=consume, 3=install, 4=etc）— 对应客户端 subCategory
     * @param board    拍卖行板块（1=贩卖/直接出售, 2=购买/wish, 3=竞标/拍卖）— 对应客户端 category；
     *                 按 BID_RANGE 区分：board 1 → bidRange=0，board 3 → bidRange>0，board 2 无数据
     * @param page     页码（1-based）
     */
    SearchResult searchListings(int itemType, int board, int page, int pageSize,
                                byte sortType, byte sortColumn, int searchOption, String searchText);

    boolean newListing(AuctionListing listing);

    boolean updateListing(AuctionListing listing);

    boolean deleteListing(int listingId);
}