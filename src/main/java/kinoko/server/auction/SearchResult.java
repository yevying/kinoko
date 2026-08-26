package kinoko.server.auction;

import java.util.List;

public final class SearchResult {
    private final List<AuctionListing> listings;
    private final int totalCount;
    private final int page;
    private final int pageSize;
    private final int totalPages;

    public SearchResult(List<AuctionListing> listings, int totalCount, int page, int pageSize) {
        this.listings = listings;
        this.totalCount = totalCount;
        this.page = page;
        this.pageSize = pageSize;
        this.totalPages = pageSize > 0 ? (int) Math.ceil((double) totalCount / pageSize) : 0;
    }

    public List<AuctionListing> getListings() {
        return listings;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getTotalPages() {
        return totalPages;
    }
}