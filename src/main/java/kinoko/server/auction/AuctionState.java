package kinoko.server.auction;

public enum AuctionState {
    ACTIVE(1),
    SOLD(2),
    EXPIRED(3),
    CANCELLED(4);

    private final int value;

    AuctionState(int value) {
        this.value = value;
    }

    public final int getValue() {
        return value;
    }

    public static AuctionState getByValue(int value) {
        for (AuctionState state : values()) {
            if (state.getValue() == value) {
                return state;
            }
        }
        return null;
    }
}