package kinoko.database.sqlite;

import com.alibaba.fastjson2.JSON;
import kinoko.database.AuctionAccessor;
import kinoko.database.json.ItemSerializer;
import kinoko.server.auction.AuctionListing;
import kinoko.server.auction.AuctionState;
import kinoko.server.auction.SearchResult;
import kinoko.world.item.Item;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static kinoko.database.schema.AuctionSchema.*;

public final class SqliteAuctionAccessor extends SqliteAccessor implements AuctionAccessor {
    private static final String tableName = "auction_table";
    private final ItemSerializer itemSerializer = new ItemSerializer();

    public SqliteAuctionAccessor(Connection connection) {
        super(connection);
    }

    private AuctionListing loadListing(ResultSet rs) throws SQLException {
        AuctionListing listing = new AuctionListing();
        listing.setListingId(rs.getInt(LISTING_ID));
        listing.setSellerId(rs.getInt(SELLER_ID));
        listing.setSellerName(rs.getString(SELLER_NAME));

        Item item = itemSerializer.deserialize(getJsonObject(rs, ITEM_DATA));
        listing.setItem(item);
        listing.setItemId(rs.getInt(ITEM_ID));
        listing.setItemType(rs.getInt(ITEM_TYPE));

        listing.setPrice(rs.getInt(PRICE));
        listing.setBuyoutPrice(rs.getInt(BUYOUT_PRICE));
        listing.setCurrentBid(rs.getInt(CURRENT_BID));
        listing.setBidderId(rs.getInt(BIDDER_ID));
        listing.setBidderName(rs.getString(BIDDER_NAME));
        listing.setBidCount(rs.getInt(BID_COUNT));
        listing.setBidRange(rs.getInt(BID_RANGE));
        listing.setProcessStatus(AuctionState.getByValue(rs.getInt(PROCESS_STATUS)));
        listing.setCreatedAt(getInstant(rs, CREATED_AT));
        listing.setExpiresAt(getInstant(rs, EXPIRES_AT));
        listing.setClaimed(rs.getBoolean(CLAIMED));
        return listing;
    }

    @Override
    public Optional<AuctionListing> getListingById(int listingId) {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM " + tableName + " WHERE " + LISTING_ID + " = ?"
        )) {
            ps.setInt(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(loadListing(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    @Override
    public List<AuctionListing> getActiveListings() {
        List<AuctionListing> listings = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM " + tableName + " WHERE " + PROCESS_STATUS + " = ?"
        )) {
            ps.setInt(1, AuctionState.ACTIVE.getValue());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    listings.add(loadListing(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return listings;
    }

    @Override
    public List<AuctionListing> getListingsBySeller(int sellerId) {
        List<AuctionListing> listings = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM " + tableName + " WHERE " + SELLER_ID + " = ? ORDER BY " + CREATED_AT + " DESC"
        )) {
            ps.setInt(1, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    listings.add(loadListing(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return listings;
    }

    @Override
    public List<AuctionListing> getListingsByBidder(int bidderId) {
        List<AuctionListing> listings = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM " + tableName + " WHERE " + BIDDER_ID + " = ? ORDER BY " + CREATED_AT + " DESC"
        )) {
            ps.setInt(1, bidderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    listings.add(loadListing(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return listings;
    }

    @Override
    public List<AuctionListing> getExpiredListings() {
        List<AuctionListing> listings = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM " + tableName +
                        " WHERE " + PROCESS_STATUS + " = ? AND " + EXPIRES_AT + " < ?"
        )) {
            ps.setInt(1, AuctionState.ACTIVE.getValue());
            ps.setLong(2, Instant.now().toEpochMilli());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    listings.add(loadListing(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return listings;
    }

    @Override
    public SearchResult searchListings(int category, int subCategory, int page, int pageSize,
                                       byte sortType, byte sortColumn, int searchOption, String searchText) {
        List<AuctionListing> listings = new ArrayList<>();
        int totalCount = 0;
        if (page < 1) {
            page = 1;
        }
        int offset = (page - 1) * pageSize;

        // Build WHERE clause
        StringBuilder whereClause = new StringBuilder(" WHERE " + PROCESS_STATUS + " = ?");
        List<Object> params = new ArrayList<>();
        params.add(AuctionState.ACTIVE.getValue());

        if (category > 0) {
            whereClause.append(" AND ").append(ITEM_TYPE).append(" = ?");
            params.add(category);
        }
        // subCategory for itemId prefix filtering (e.g. weapon type)
        if (subCategory > 0) {
            int subPrefix = subCategory * 10000;
            whereClause.append(" AND ").append(ITEM_ID).append(" >= ? AND ").append(ITEM_ID).append(" < ?");
            params.add(subPrefix);
            params.add(subPrefix + 10000);
        }
        // Text search on seller name
        if (searchText != null && !searchText.isEmpty()) {
            whereClause.append(" AND ").append(SELLER_NAME).append(" LIKE ?");
            params.add("%" + searchText + "%");
        }

        // Count query
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT COUNT(*) FROM " + tableName + whereClause
        )) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    totalCount = rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // Sort column
        String sortColumnStr = switch (sortColumn) {
            case 1 -> PRICE;
            case 2 -> ITEM_ID;
            case 3 -> SELLER_NAME;
            default -> EXPIRES_AT;
        };
        String sortOrder = sortType == 1 ? " ASC" : " DESC";

        // Data query
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM " + tableName + whereClause +
                        " ORDER BY " + sortColumnStr + sortOrder +
                        " LIMIT ? OFFSET ?"
        )) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.setInt(params.size() + 1, pageSize);
            ps.setInt(params.size() + 2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    listings.add(loadListing(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return new SearchResult(listings, totalCount, page, pageSize, category, subCategory, sortType, sortColumn);
    }

    @Override
    public boolean newListing(AuctionListing listing) {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "INSERT INTO " + tableName + " (" +
                        SELLER_ID + ", " +
                        SELLER_NAME + ", " +
                        ITEM_DATA + ", " +
                        ITEM_ID + ", " +
                        ITEM_TYPE + ", " +
                        PRICE + ", " +
                        BUYOUT_PRICE + ", " +
                        CURRENT_BID + ", " +
                        BIDDER_ID + ", " +
                        BIDDER_NAME + ", " +
                        BID_COUNT + ", " +
                        BID_RANGE + ", " +
                        PROCESS_STATUS + ", " +
                        CREATED_AT + ", " +
                        EXPIRES_AT + ", " +
                        CLAIMED + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            ps.setInt(1, listing.getSellerId());
            ps.setString(2, listing.getSellerName());
            setJsonObject(ps, 3, itemSerializer.serialize(listing.getItem()));
            ps.setInt(4, listing.getItemId());
            ps.setInt(5, listing.getItemType());
            ps.setInt(6, listing.getPrice());
            ps.setInt(7, listing.getBuyoutPrice());
            ps.setInt(8, listing.getCurrentBid());
            ps.setInt(9, listing.getBidderId());
            ps.setString(10, listing.getBidderName());
            ps.setInt(11, listing.getBidCount());
            ps.setInt(12, listing.getBidRange());
            ps.setInt(13, listing.getProcessStatus().getValue());
            setInstant(ps, 14, listing.getCreatedAt());
            setInstant(ps, 15, listing.getExpiresAt());
            ps.setBoolean(16, listing.isClaimed());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean updateListing(AuctionListing listing) {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE " + tableName + " SET " +
                        PRICE + " = ?, " +
                        BUYOUT_PRICE + " = ?, " +
                        CURRENT_BID + " = ?, " +
                        BIDDER_ID + " = ?, " +
                        BIDDER_NAME + " = ?, " +
                        BID_COUNT + " = ?, " +
                        BID_RANGE + " = ?, " +
                        PROCESS_STATUS + " = ?, " +
                        CLAIMED + " = ? " +
                        "WHERE " + LISTING_ID + " = ?"
        )) {
            ps.setInt(1, listing.getPrice());
            ps.setInt(2, listing.getBuyoutPrice());
            ps.setInt(3, listing.getCurrentBid());
            ps.setInt(4, listing.getBidderId());
            ps.setString(5, listing.getBidderName());
            ps.setInt(6, listing.getBidCount());
            ps.setInt(7, listing.getBidRange());
            ps.setInt(8, listing.getProcessStatus().getValue());
            ps.setBoolean(9, listing.isClaimed());
            ps.setInt(10, listing.getListingId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean deleteListing(int listingId) {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "DELETE FROM " + tableName + " WHERE " + LISTING_ID + " = ?"
        )) {
            ps.setInt(1, listingId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static void createTable(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                            LISTING_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            SELLER_ID + " INTEGER NOT NULL, " +
                            SELLER_NAME + " TEXT NOT NULL, " +
                            ITEM_DATA + " TEXT NOT NULL, " +
                            ITEM_ID + " INTEGER NOT NULL, " +
                            ITEM_TYPE + " INTEGER NOT NULL, " +
                            PRICE + " INTEGER NOT NULL, " +
                            BUYOUT_PRICE + " INTEGER NOT NULL DEFAULT 0, " +
                            CURRENT_BID + " INTEGER NOT NULL DEFAULT 0, " +
                            BIDDER_ID + " INTEGER NOT NULL DEFAULT 0, " +
                            BIDDER_NAME + " TEXT NOT NULL DEFAULT '', " +
                            BID_COUNT + " INTEGER NOT NULL DEFAULT 0, " +
                            BID_RANGE + " INTEGER NOT NULL DEFAULT 0, " +
                            PROCESS_STATUS + " INTEGER NOT NULL DEFAULT 1, " +
                            CREATED_AT + " " + INSTANT_TYPE + " NOT NULL, " +
                            EXPIRES_AT + " " + INSTANT_TYPE + " NOT NULL, " +
                            CLAIMED + " INTEGER NOT NULL DEFAULT 0)"
            );

            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_type ON " + tableName + "(" + ITEM_TYPE + ")");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_seller ON " + tableName + "(" + SELLER_ID + ")");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_bidder ON " + tableName + "(" + BIDDER_ID + ")");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_status ON " + tableName + "(" + PROCESS_STATUS + ")");
        }
    }
}