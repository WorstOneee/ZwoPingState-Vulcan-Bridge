package dev.zwo.pingstate.core;

/** Par estado + motivo. Instâncias pré-criadas: {@link #of} não aloca. */
public record PingStatus(PingState state, PingReason reason) {

    private static final PingStatus[][] CACHE;

    static {
        CACHE = new PingStatus[PingState.values().length][PingReason.values().length];
        for (PingState s : PingState.values()) {
            for (PingReason r : PingReason.values()) {
                CACHE[s.ordinal()][r.ordinal()] = new PingStatus(s, r);
            }
        }
    }

    public static final PingStatus NOT_TRACKED = of(PingState.UNKNOWN, PingReason.NOT_TRACKED);
    public static final PingStatus STALE = of(PingState.UNKNOWN, PingReason.STALE_DATA);
    public static final PingStatus SERVER_UNSTABLE = of(PingState.UNSTABLE, PingReason.SERVER_UNSTABLE);

    public static PingStatus of(PingState state, PingReason reason) {
        return CACHE[state.ordinal()][reason.ordinal()];
    }
}
