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

    SearchResult searchListings(int category, int subCategory, int page, int pageSize,
                                byte sortType, byte sortColumn, int searchOption, String searchText);

    boolean newListing(AuctionListing listing);

    boolean updateListing(AuctionListing listing);

    boolean deleteListing(int listingId);
}