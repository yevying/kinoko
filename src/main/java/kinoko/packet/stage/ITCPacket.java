package kinoko.packet.stage;

import kinoko.server.auction.AuctionListing;
import kinoko.server.auction.SearchResult;
import kinoko.server.header.OutHeader;
import kinoko.server.packet.OutPacket;
import kinoko.util.FileTime;
import kinoko.world.GameConstants;
import kinoko.world.user.Account;
import kinoko.world.user.User;

import java.time.Instant;
import java.util.List;

public final class ITCPacket {

    // ---------------------------------------------------------------------------------------------------------------
    // SetITC (142) - entering ITC
    // ---------------------------------------------------------------------------------------------------------------

    public static OutPacket setITC(User user) {
        final OutPacket outPacket = OutPacket.of(OutHeader.SetITC);
        // CharacterData::Encode
        user.getCharacterData().encode(outPacket);
        // sNexonClubID
        outPacket.encodeString(user.getAccount().getUsername());
        // ITC constants
        outPacket.encodeInt(GameConstants.ITC_REGISTER_FEE_MESO);
        outPacket.encodeInt(GameConstants.ITC_COMMISSION_RATE);
        outPacket.encodeInt(GameConstants.ITC_COMMISSION_BASE);
        outPacket.encodeInt(GameConstants.ITC_AUCTION_DURATION_MIN);
        outPacket.encodeInt(GameConstants.ITC_AUCTION_DURATION_MAX);
        // ftServer
        outPacket.encodeFT(Instant.now());
        return outPacket;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ITCQueryCashResult (411)
    // ---------------------------------------------------------------------------------------------------------------

    public static OutPacket queryCashResult(Account account) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCQueryCashResult);
        outPacket.encodeInt(account.getNxCredit());
        outPacket.encodeInt(account.getMaplePoint());
        outPacket.encodeInt(account.getNxPrepaid());
        return outPacket;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ITCNormalItemResult (412) - sub-operation results
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * OnGetITCListDone (0x15) - browse/listing results
     */
    public static OutPacket listResult(SearchResult searchResult, boolean isSearch) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        if (isSearch) {
            outPacket.encodeByte(0x17); // OnGetSearchITCListDone
        } else {
            outPacket.encodeByte(0x15); // OnGetITCListDone
        }
        // v0.95 list header: total count, page count, category, subcategory, page
        outPacket.encodeInt(searchResult.getTotalCount());
        outPacket.encodeInt(searchResult.getListings().size());
        outPacket.encodeInt(searchResult.getCategory());
        outPacket.encodeInt(searchResult.getSubCategory());
        outPacket.encodeInt(searchResult.getPage());
        if (!isSearch) {
            outPacket.encodeByte(searchResult.getSortType());
            outPacket.encodeByte(searchResult.getSortColumn());
        }
        for (AuctionListing listing : searchResult.getListings()) {
            encodeITCITEM(outPacket, listing);
        }
        if (!isSearch) {
            outPacket.encodeByte(1);
        }
        return outPacket;
    }

    /**
     * OnGetITCListFailed (0x16) / OnGetSearchITCListFailed (0x18)
     */
    public static OutPacket listFailed(boolean isSearch) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(isSearch ? 0x18 : 0x16);
        outPacket.encodeByte(0);
        return outPacket;
    }

    /**
     * OnRegisterSaleEntryDone (0x1D)
     */
    public static OutPacket registerDone(AuctionListing listing) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x1D);
        encodeITCITEM(outPacket, listing);
        return outPacket;
    }

    /**
     * OnRegisterSaleEntryFailed (0x1E)
     * <p>reason 为原版客户端 NoticeFailReason 的失败原因字符（matching reference:
     * OnNormalItemResRegisterSaleEnt_576B80）：'O'=该道具无法登记、'C'=金币不足、'R'=耐用度未修复等；
     * 0 = 无提示（Godot 客户端不读取该字节，保持 0）。
     */
    public static OutPacket registerFailed(int reason) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x1E);
        outPacket.encodeByte(reason);
        return outPacket;
    }

    /**
     * OnGetUserSaleItemDone (0x23)
     */
    public static OutPacket userSaleListResult(List<AuctionListing> listings) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x23);
        outPacket.encodeInt(listings.size());
        for (AuctionListing listing : listings) {
            encodeITCITEM(outPacket, listing);
        }
        return outPacket;
    }

    /**
     * OnGetUserSaleItemFailed (0x24)
     */
    public static OutPacket userSaleListFailed() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x24);
        return outPacket;
    }

    /**
     * OnGetUserPurchaseItemDone (0x21)
     */
    public static OutPacket userPurchaseListResult(List<AuctionListing> listings) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x21);
        outPacket.encodeInt(listings.size());
        for (AuctionListing listing : listings) {
            encodeITCITEM(outPacket, listing);
        }
        return outPacket;
    }

    /**
     * OnGetUserPurchaseItemFailed (0x22)
     */
    public static OutPacket userPurchaseListFailed() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x22);
        return outPacket;
    }

    /**
     * OnCancelSaleItemDone (0x25)
     */
    public static OutPacket cancelDone(int listingId) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x25);
        outPacket.encodeInt(listingId);
        return outPacket;
    }

    /**
     * OnCancelSaleItemFailed (0x26)
     */
    public static OutPacket cancelFailed() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x26);
        return outPacket;
    }

    /**
     * OnMoveITCPurchaseItemLtoSDone (0x27) - claim item/revenue success
     */
    public static OutPacket moveItemDone(int listingId) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x27);
        outPacket.encodeInt(listingId);
        return outPacket;
    }

    /**
     * OnMoveITCPurchaseItemLtoSDone (0x27) - 原版客户端布局（matching reference:
     * OnMoveITCPurchaseItemLtoSDone_5760A0：Decode4 tab(1-based 背包页) + Decode4 slotNo，
     * 用于切换背包页并高亮槽位；不读 listingId）。
     */
    public static OutPacket moveItemDoneOriginal(int tab, int slotNo) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x27);
        outPacket.encodeInt(tab);
        outPacket.encodeInt(slotNo);
        return outPacket;
    }

    /**
     * OnMoveITCPurchaseItemLtoSFailed (0x28)
     */
    public static OutPacket moveItemFailed() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x28);
        return outPacket;
    }

    /**
     * OnBuyItemDone (0x33)
     */
    public static OutPacket buyDone(int listingId) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x33);
        outPacket.encodeInt(listingId);
        return outPacket;
    }

    /**
     * OnBuyItemFailed (0x34)
     */
    public static OutPacket buyFailed() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x34);
        return outPacket;
    }

    /**
     * OnSuccessBidInfoResult (0x3E) - bid result notification
     */
    public static OutPacket bidResult(AuctionListing listing) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x3E);
        encodeITCITEM(outPacket, listing);
        return outPacket;
    }

    /**
     * OnSuccessBidInfoResult (0x3E) - 原版客户端布局（matching reference:
     * OnSuccessBidInfoResult_577000：只读 byte(1=售出/其他=拍得) + itemId(4) + price(4) + ftTime(8)，
     * 不读完整 ITCITEM）。
     */
    public static OutPacket bidResultCompact(int soldFlag, int itemId, int price, Instant time) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x3E);
        outPacket.encodeByte(soldFlag);
        outPacket.encodeInt(itemId);
        outPacket.encodeInt(price);
        outPacket.encodeFT(time);
        return outPacket;
    }

    /**
     * OnSuccessBidInfoFailed (0x3C) - bid failure
     */
    public static OutPacket bidFailed() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x3C);
        return outPacket;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ITCITEM encoding
    // ---------------------------------------------------------------------------------------------------------------

    private static void encodeITCITEM(OutPacket outPacket, AuctionListing listing) {
        // GW_ItemSlotBase::Encode
        listing.getItem().encode(outPacket);
        // nITCSN
        outPacket.encodeInt(listing.getListingId());
        // price / starting price
        outPacket.encodeInt(listing.getPrice());
        // commission fee (contract fee)
        outPacket.encodeInt(0);
        // contract fee transaction ID
        outPacket.encodeString("");
        // rollback use ID
        outPacket.encodeString("");
        // ftExpire
        outPacket.encodeFT(listing.getExpiresAt());
        // seller ID (character name as string)
        outPacket.encodeString(listing.getSellerName());
        // seller game ID (same as seller name in v0.95)
        outPacket.encodeString(listing.getSellerName());
        // memo
        outPacket.encodeString("");
        // bid count (0 = no bids, 1 = has bids)
        outPacket.encodeInt(listing.getBidCount() > 0 ? 1 : 0);
        // bid increment
        outPacket.encodeInt(listing.getBidRange());
        // current highest bid
        outPacket.encodeInt(listing.getCurrentBid());
        // starting price
        outPacket.encodeInt(listing.getPrice());
        // buyout price
        outPacket.encodeInt(listing.getBuyoutPrice());
        // unit price
        outPacket.encodeInt(0);
        // process status
        outPacket.encodeShort(listing.getProcessStatus().getValue());
    }
}