package kinoko.server.auction;

import java.util.List;

public final class SearchResult {
    private final List<AuctionListing> listings;
    private final int totalCount;
    private final int page;
    private final int pageSize;
    private final int totalPages;
    private final int category;
    private final int subCategory;
    private final byte sortType;
    private final byte sortColumn;

    public SearchResult(List<AuctionListing> listings, int totalCount, int page, int pageSize,
                        int category, int subCategory, byte sortType, byte sortColumn) {
        this.listings = listings;
        this.totalCount = totalCount;
        this.page = page;
        this.pageSize = pageSize;
        this.totalPages = pageSize > 0 ? (int) Math.ceil((double) totalCount / pageSize) : 0;
        this.category = category;
        this.subCategory = subCategory;
        this.sortType = sortType;
        this.sortColumn = sortColumn;
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

    public int getCategory() {
        return category;
    }

    public int getSubCategory() {
        return subCategory;
    }

    public byte getSortType() {
        return sortType;
    }

    public byte getSortColumn() {
        return sortColumn;
    }
}