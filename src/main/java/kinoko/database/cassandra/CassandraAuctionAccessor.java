package kinoko.database.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.querybuilder.SchemaBuilder;
import kinoko.database.AuctionAccessor;
import kinoko.database.json.ItemSerializer;
import kinoko.provider.StringProvider;
import kinoko.server.auction.AuctionListing;
import kinoko.server.auction.AuctionState;
import kinoko.server.auction.SearchResult;
import kinoko.world.item.Item;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.*;
import static kinoko.database.schema.AuctionSchema.*;

public final class CassandraAuctionAccessor extends CassandraAccessor implements AuctionAccessor {
    private static final String tableName = "auction_table";
    private final ItemSerializer itemSerializer = new ItemSerializer();

    public CassandraAuctionAccessor(CqlSession session, String keyspace) {
        super(session, keyspace);
    }

    private AuctionListing loadListing(Row row) {
        AuctionListing listing = new AuctionListing();
        listing.setListingId(row.getInt(LISTING_ID));
        listing.setSellerId(row.getInt(SELLER_ID));
        listing.setSellerName(row.getString(SELLER_NAME));

        Item item = itemSerializer.deserialize(getJsonObject(row, ITEM_DATA));
        listing.setItem(item);
        listing.setItemId(row.getInt(ITEM_ID));
        listing.setItemType(row.getInt(ITEM_TYPE));

        listing.setPrice(row.getInt(PRICE));
        listing.setBuyoutPrice(row.getInt(BUYOUT_PRICE));
        listing.setCurrentBid(row.getInt(CURRENT_BID));
        listing.setBidderId(row.getInt(BIDDER_ID));
        listing.setBidderName(row.getString(BIDDER_NAME));
        listing.setBidCount(row.getInt(BID_COUNT));
        listing.setBidRange(row.getInt(BID_RANGE));
        listing.setProcessStatus(AuctionState.getByValue(row.getInt(PROCESS_STATUS)));
        listing.setCreatedAt(row.getInstant(CREATED_AT));
        listing.setExpiresAt(row.getInstant(EXPIRES_AT));
        listing.setClaimed(row.getBoolean(CLAIMED));
        return listing;
    }

    @Override
    public Optional<AuctionListing> getListingById(int listingId) {
        ResultSet selectResult = getSession().execute(
                selectFrom(getKeyspace(), tableName).all()
                        .whereColumn(LISTING_ID).isEqualTo(literal(listingId))
                        .build()
        );
        for (Row row : selectResult) {
            return Optional.of(loadListing(row));
        }
        return Optional.empty();
    }

    @Override
    public List<AuctionListing> getActiveListings() {
        List<AuctionListing> listings = new ArrayList<>();
        // Cassandra allows filtering on secondary index with ALLOW FILTERING for small datasets
        ResultSet selectResult = getSession().execute(
                selectFrom(getKeyspace(), tableName).all()
                        .whereColumn(PROCESS_STATUS).isEqualTo(literal(AuctionState.ACTIVE.getValue()))
                        .allowFiltering()
                        .build()
        );
        for (Row row : selectResult) {
            listings.add(loadListing(row));
        }
        return listings;
    }

    @Override
    public List<AuctionListing> getListingsBySeller(int sellerId) {
        List<AuctionListing> listings = new ArrayList<>();
        ResultSet selectResult = getSession().execute(
                selectFrom(getKeyspace(), tableName).all()
                        .whereColumn(SELLER_ID).isEqualTo(literal(sellerId))
                        .allowFiltering()
                        .build()
        );
        for (Row row : selectResult) {
            listings.add(loadListing(row));
        }
        return listings;
    }

    @Override
    public List<AuctionListing> getListingsByBidder(int bidderId) {
        List<AuctionListing> listings = new ArrayList<>();
        ResultSet selectResult = getSession().execute(
                selectFrom(getKeyspace(), tableName).all()
                        .whereColumn(BIDDER_ID).isEqualTo(literal(bidderId))
                        .allowFiltering()
                        .build()
        );
        for (Row row : selectResult) {
            listings.add(loadListing(row));
        }
        return listings;
    }

    @Override
    public List<AuctionListing> getExpiredListings() {
        List<AuctionListing> listings = new ArrayList<>();
        // Expired: active status + expires_at < now
        // Cassandra does not support < on non-primary key without ALLOW FILTERING
        // This is acceptable for ITC's dataset size
        ResultSet selectResult = getSession().execute(
                selectFrom(getKeyspace(), tableName).all()
                        .whereColumn(PROCESS_STATUS).isEqualTo(literal(AuctionState.ACTIVE.getValue()))
                        .allowFiltering()
                        .build()
        );
        Instant now = Instant.now();
        for (Row row : selectResult) {
            Instant expiresAt = row.getInstant(EXPIRES_AT);
            if (expiresAt != null && expiresAt.isBefore(now)) {
                listings.add(loadListing(row));
            }
        }
        return listings;
    }

    @Override
    public SearchResult searchListings(int itemType, int board, int page, int pageSize,
                                       byte sortType, byte sortColumn, int searchOption, String searchText) {
        // For Cassandra, we fetch all active listings and filter in-memory.
        // This is acceptable for typical ITC dataset sizes.
        List<AuctionListing> allActive = getActiveListings();
        final boolean searchItem = searchText != null && !searchText.isEmpty() && searchOption == 1;
        final List<Integer> searchedIds = searchItem ? resolveItemIds(searchText) : List.of();
        final String sellerFilter = (searchText != null && !searchText.isEmpty() && searchOption != 1)
                ? searchText.toLowerCase() : null;
        List<AuctionListing> filtered = new ArrayList<>();

        for (AuctionListing listing : allActive) {
            if (itemType > 0 && listing.getItemType() != itemType) continue;
            // board: 1=贩卖(bidRange=0), 3=竞标(bidRange>0), 2=购买/wish(kinoko 未实现，恒空)
            if (board == 1) {
                if (listing.getBidRange() != 0) continue;
            } else if (board == 3) {
                if (listing.getBidRange() <= 0) continue;
            } else if (board == 2) {
                continue;
            }
            if (searchItem) {
                if (!searchedIds.contains(listing.getItemId())) continue;
            } else if (sellerFilter != null) {
                if (!listing.getSellerName().toLowerCase().contains(sellerFilter)) continue;
            }
            filtered.add(listing);
        }

        // Sort
        filtered.sort((a, b) -> {
            int cmp;
            switch (sortColumn) {
                case 1 -> cmp = Integer.compare(a.getPrice(), b.getPrice()); // price
                case 2 -> cmp = Integer.compare(a.getItemId(), b.getItemId()); // item id
                case 3 -> cmp = a.getSellerName().compareTo(b.getSellerName()); // seller name
                default -> cmp = a.getExpiresAt().compareTo(b.getExpiresAt()); // date
            }
            return sortType == 1 ? cmp : -cmp;
        });

        int totalCount = filtered.size();
        int offset = (page - 1) * pageSize;
        int end = Math.min(offset + pageSize, totalCount);
        List<AuctionListing> pageList = offset < totalCount ? filtered.subList(offset, end) : List.of();

        return new SearchResult(pageList, totalCount, page, pageSize, board, itemType, sortType, sortColumn);
    }

    /** 搜索文本 → 物品 ID 集合：纯数字按 ID 精确匹配，否则按名称包含匹配（大小写不敏感）。 */
    private static List<Integer> resolveItemIds(String text) {
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return List.of(Integer.parseInt(text));
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        final String lower = text.toLowerCase();
        List<Integer> ids = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : StringProvider.getItemNames().entrySet()) {
            if (entry.getValue() != null && entry.getValue().toLowerCase().contains(lower)) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    @Override
    public boolean newListing(AuctionListing listing) {
        ResultSet insertResult = getSession().execute(
                insertInto(getKeyspace(), tableName)
                        .value(LISTING_ID, literal(listing.getListingId()))
                        .value(SELLER_ID, literal(listing.getSellerId()))
                        .value(SELLER_NAME, literal(listing.getSellerName()))
                        .value(ITEM_DATA, literalJsonObject(itemSerializer.serialize(listing.getItem())))
                        .value(ITEM_ID, literal(listing.getItemId()))
                        .value(ITEM_TYPE, literal(listing.getItemType()))
                        .value(PRICE, literal(listing.getPrice()))
                        .value(BUYOUT_PRICE, literal(listing.getBuyoutPrice()))
                        .value(CURRENT_BID, literal(listing.getCurrentBid()))
                        .value(BIDDER_ID, literal(listing.getBidderId()))
                        .value(BIDDER_NAME, literal(listing.getBidderName()))
                        .value(BID_COUNT, literal(listing.getBidCount()))
                        .value(BID_RANGE, literal(listing.getBidRange()))
                        .value(PROCESS_STATUS, literal(listing.getProcessStatus().getValue()))
                        .value(CREATED_AT, literal(listing.getCreatedAt().toEpochMilli()))
                        .value(EXPIRES_AT, literal(listing.getExpiresAt().toEpochMilli()))
                        .value(CLAIMED, literal(listing.isClaimed()))
                        .ifNotExists()
                        .build()
        );
        return insertResult.wasApplied();
    }

    @Override
    public boolean updateListing(AuctionListing listing) {
        ResultSet updateResult = getSession().execute(
                update(getKeyspace(), tableName)
                        .setColumn(PRICE, literal(listing.getPrice()))
                        .setColumn(BUYOUT_PRICE, literal(listing.getBuyoutPrice()))
                        .setColumn(CURRENT_BID, literal(listing.getCurrentBid()))
                        .setColumn(BIDDER_ID, literal(listing.getBidderId()))
                        .setColumn(BIDDER_NAME, literal(listing.getBidderName()))
                        .setColumn(BID_COUNT, literal(listing.getBidCount()))
                        .setColumn(BID_RANGE, literal(listing.getBidRange()))
                        .setColumn(PROCESS_STATUS, literal(listing.getProcessStatus().getValue()))
                        .setColumn(CLAIMED, literal(listing.isClaimed()))
                        .whereColumn(LISTING_ID).isEqualTo(literal(listing.getListingId()))
                        .build()
        );
        return updateResult.wasApplied();
    }

    @Override
    public boolean deleteListing(int listingId) {
        ResultSet deleteResult = getSession().execute(
                deleteFrom(getKeyspace(), tableName)
                        .whereColumn(LISTING_ID).isEqualTo(literal(listingId))
                        .build()
        );
        return deleteResult.wasApplied();
    }

    public static void createTable(CqlSession session, String keyspace) {
        session.execute(
                SchemaBuilder.createTable(keyspace, tableName)
                        .ifNotExists()
                        .withPartitionKey(LISTING_ID, DataTypes.INT)
                        .withColumn(SELLER_ID, DataTypes.INT)
                        .withColumn(SELLER_NAME, DataTypes.TEXT)
                        .withColumn(ITEM_DATA, JSON_TYPE)
                        .withColumn(ITEM_ID, DataTypes.INT)
                        .withColumn(ITEM_TYPE, DataTypes.INT)
                        .withColumn(PRICE, DataTypes.INT)
                        .withColumn(BUYOUT_PRICE, DataTypes.INT)
                        .withColumn(CURRENT_BID, DataTypes.INT)
                        .withColumn(BIDDER_ID, DataTypes.INT)
                        .withColumn(BIDDER_NAME, DataTypes.TEXT)
                        .withColumn(BID_COUNT, DataTypes.INT)
                        .withColumn(BID_RANGE, DataTypes.INT)
                        .withColumn(PROCESS_STATUS, DataTypes.INT)
                        .withColumn(CREATED_AT, DataTypes.BIGINT)
                        .withColumn(EXPIRES_AT, DataTypes.BIGINT)
                        .withColumn(CLAIMED, DataTypes.BOOLEAN)
                        .build()
        );
        // Secondary indexes for common queries
        session.execute(
                SchemaBuilder.createIndex()
                        .ifNotExists()
                        .onTable(keyspace, tableName)
                        .andColumn(SELLER_ID)
                        .build()
        );
        session.execute(
                SchemaBuilder.createIndex()
                        .ifNotExists()
                        .onTable(keyspace, tableName)
                        .andColumn(BIDDER_ID)
                        .build()
        );
        session.execute(
                SchemaBuilder.createIndex()
                        .ifNotExists()
                        .onTable(keyspace, tableName)
                        .andColumn(PROCESS_STATUS)
                        .build()
        );
    }
}