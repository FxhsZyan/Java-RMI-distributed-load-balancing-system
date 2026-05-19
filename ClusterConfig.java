package loadbalancer;

/**
 * ═══════════════════════════════════════════════════════════
 *  ClusterConfig — single source of truth for all IPs / ports
 * ═══════════════════════════════════════════════════════════
 *
 *  Edit THIS file only when the network changes.
 *  Everything else (RMI binding, GUI, peer discovery) reads
 *  from these constants — no command-line args needed.
 */
public final class ClusterConfig {

    private ClusterConfig() {}

    // ── Fixed IP addresses ────────────────────────────────────────────────────
    public static final String IP_NODE1 = "10.16.45.23";  // your current PC
    public static final String IP_NODE2 = "10.16.45.22";  // 2nd PC's IP
    public static final String IP_NODE3 = "10.16.45.21";  // 3rd PC's IP

    // ── RMI registry port (same on every machine) ─────────────────────────────
    public static final int RMI_PORT = 1099;

    // ── RMI service names ─────────────────────────────────────────────────────
    public static final String NAME_NODE1 = "Node-1";
    public static final String NAME_NODE2 = "Node-2";
    public static final String NAME_NODE3 = "Node-3";

    // ── Full RMI addresses ────────────────────────────────────────────────────
    public static final String ADDR_NODE1 =
            "rmi://" + IP_NODE1 + ":" + RMI_PORT + "/" + NAME_NODE1;
    public static final String ADDR_NODE2 =
            "rmi://" + IP_NODE2 + ":" + RMI_PORT + "/" + NAME_NODE2;
    public static final String ADDR_NODE3 =
            "rmi://" + IP_NODE3 + ":" + RMI_PORT + "/" + NAME_NODE3;

    // ── Thread / load tuning ──────────────────────────────────────────────────
    /** Max concurrent tasks per node before it is considered overloaded. */
    public static final int MAX_THREADS = 6;

    /** Load fraction (0–1) above which tasks are forwarded to peers. */
    public static final double OVERLOAD_THRESHOLD = 0.50;

    /** How long (ms) between peer-liveness cache refreshes. */
    public static final long PEER_CACHE_TTL_MS = 5_000;

    /** Minimum CPU burn time per task in milliseconds. */
    public static final long MIN_BURN_MS = 1_500;

    // ── Helper: derive node info from the machine's own IP ───────────────────

    /** Returns the node name for a given IP, or null if not recognised. */
    public static String nodeNameForIp(String ip) {
        if (IP_NODE1.equals(ip)) return NAME_NODE1;
        if (IP_NODE2.equals(ip)) return NAME_NODE2;
        if (IP_NODE3.equals(ip)) return NAME_NODE3;
        return null;
    }

    public static String addrForIp(String ip) {
        if (IP_NODE1.equals(ip)) return ADDR_NODE1;
        if (IP_NODE2.equals(ip)) return ADDR_NODE2;
        if (IP_NODE3.equals(ip)) return ADDR_NODE3;
        return null;
    }

    /** All three peer addresses in an array. */
    public static String[] allAddresses() {
        return new String[]{ ADDR_NODE1, ADDR_NODE2, ADDR_NODE3 };
    }

    /** All three node names in an array (same index order as allAddresses). */
    public static String[] allNames() {
        return new String[]{ NAME_NODE1, NAME_NODE2, NAME_NODE3 };
    }
}