package kinoko.packet.stage;

import kinoko.server.auction.AuctionListing;
import kinoko.server.auction.AuctionManager;
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
     * <p>原版客户端尾部必读两项（matching reference: OnGetUserPurchaseItemDone_576CF0）：
     * Decode4 nLimitedCount（受限/交易结束待结算道具数）+ Decode1 复位 m_bITCRequestSent。
     * 缺任一项都会让 CInPacket 越界读取，触发客户端闪退（dump error 0x26）。
     */
    public static OutPacket userPurchaseListResult(List<AuctionListing> listings) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x21);
        outPacket.encodeInt(listings.size());
        for (AuctionListing listing : listings) {
            encodeITCITEM(outPacket, listing);
        }
        outPacket.encodeInt(0);   // nLimitedCount：进入 ITC 时无受限道具，填 0
        outPacket.encodeByte(1);  // 复位 m_bITCRequestSent，允许后续继续发起请求
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
     * OnSetZzimDone (0x29) - add to cart (wishlist) success.
     * <p>matching reference: CITC::OnSetZzimDone_576140 — 仅读取子操作码，
     * 弹出"放入购物车成功！"并复位 m_bITCRequestSent。
     */
    public static OutPacket setZzimDone() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x29);
        return outPacket;
    }

    /**
     * OnSetZzimFailed (0x2A) - add to cart (wishlist) failure (e.g. already in cart).
     * <p>matching reference: CITC::OnSetZzimFailed_576180 — 提示"无法重复放入购物车！"并复位标志。
     */
    public static OutPacket setZzimFailed() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x2A);
        return outPacket;
    }

    /**
     * OnDeleteZzimDone (0x2B) - remove from cart success.
     * <p>matching reference: CITC::OnDeleteZzimDone_5761C0 — 提示"已从购物车列表中删除。"。
     */
    public static OutPacket deleteZzimDone() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x2B);
        return outPacket;
    }

    /**
     * OnDeleteZzimFailed (0x2C) - remove from cart failure.
     * <p>matching reference: CITC::OnDeleteZzimFailed_5761F0 — 提示"购物车列表删除失败！"并复位标志。
     */
    public static OutPacket deleteZzimFailed() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x2C);
        return outPacket;
    }

    /**
     * OnLoadWishSaleListDone (0x2D) - view cart (wishlist) contents.
     * <p>matching reference: CITC::OnLoadWishSaleListDone_5769A0 — 读取 count + ITCITEM×count，
     * 首行复位 m_bITCRequestSent，打开购物车对话框；count=0 时提示"无登记内容…"。
     */
    public static OutPacket wishSaleListResult(List<AuctionListing> listings) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x2D);
        outPacket.encodeInt(listings.size());
        for (AuctionListing listing : listings) {
            encodeITCITEM(outPacket, listing);
        }
        return outPacket;
    }

    /**
     * OnLoadWishSaleListFailed (0x2E)
     */
    public static OutPacket wishSaleListFailed() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x2E);
        return outPacket;
    }

    /**
     * CartCheckoutResult (0x35) - batch cart checkout result.
     * <p>逐条上报成功情况：count + (listingId + success(1))×count，客户端据此结算购物车。
     */
    public static OutPacket cartCheckoutResult(AuctionManager.CheckoutResult result) {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x35);
        outPacket.encodeInt(result.bought().size() + result.failed().size());
        for (int listingId : result.bought()) {
            outPacket.encodeInt(listingId);
            outPacket.encodeByte(1);
        }
        for (int listingId : result.failed()) {
            outPacket.encodeInt(listingId);
            outPacket.encodeByte(0);
        }
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

    /**
     * OnBuyWishDone (0x2F) - 从购物车购买单件成功。
     * <p>matching reference: CITC::OnBuyWishDone_576270 — 仅读取子操作码，
     * 弹出"购买申请成功！"（原版 Done 不复位 m_bITCRequestSent）。
     */
    public static OutPacket buyWishDone() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x2F);
        return outPacket;
    }

    /**
     * OnBuyWishFailed (0x30) - 从购物车购买单件失败。
     * <p>matching reference: CITC::OnBuyWishFailed_5762A0 — 提示"购买申请失败！"并复位标志。
     */
    public static OutPacket buyWishFailed() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x30);
        return outPacket;
    }

    /**
     * OnCancelWishDone (0x31) - 从购物车移除单件成功。
     * <p>matching reference: CITC::OnCancelWishDone_5762E0 — 提示"购买申请已取消。"并复位标志。
     */
    public static OutPacket cancelWishDone() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x31);
        return outPacket;
    }

    /**
     * OnCancelWishFailed (0x32) - 从购物车移除单件失败。
     * <p>matching reference: CITC::OnCancelWishFailed_576320 — 提示"购买申请取消失败！"并复位标志。
     */
    public static OutPacket cancelWishFailed() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x32);
        return outPacket;
    }

    /**
     * OnBuyZzimItemDone (0x35) - 从购物车直接购买单件成功（BuyZzim）。
     * <p>matching reference: CITC::OnBuyZzimItemDone_5763D0 — 仅读取子操作码，
     * 提示"你已成功购买该物品。"（原版 Done 不复位 m_bITCRequestSent）。
     * 注意：此 0x35 与 Godot 的 CartCheckoutResult(0x35) 值相同但载荷不同，
     * 二者互斥（v95 不发送 0x15 结算，Godot 不发送 0x11 BuyZzim），互不冲突。
     */
    public static OutPacket buyZzimItemDone() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x35);
        return outPacket;
    }

    /**
     * OnBuyZzimItemFailed (0x36) - 从购物车直接购买单件失败（BuyZzim）。
     * <p>matching reference: CITC::OnBuyZzimItemFailed_576400 — 提示"购买物品失败。"并复位标志。
     */
    public static OutPacket buyZzimItemFailed() {
        final OutPacket outPacket = OutPacket.of(OutHeader.ITCNormalItemResult);
        outPacket.encodeByte(0x36);
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