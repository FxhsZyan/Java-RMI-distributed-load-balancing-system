package loadbalancer;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * ═══════════════════════════════════════════════════════════════
 *  NodeImpl — RMI server + load balancer for one physical machine
 * ═══════════════════════════════════════════════════════════════
 *
 *  Hardcoded peer IPs come from ClusterConfig — no runtime args.
 *
 *  Load balancing:
 *    activeTasks / MAX_THREADS ≥ OVERLOAD_THRESHOLD
 *      → find least-loaded live peer and forward task there
 *      → if all peers equally busy, process locally
 *
 *  Fault tolerance:
 *    Live-peer stubs are cached and refreshed every PEER_CACHE_TTL_MS.
 *    Any peer that throws RemoteException is marked offline until
 *    the next refresh cycle.
 *
 *  GUI integration:
 *    Callers register a logListener (Consumer<String>) so every
 *    structured log line is also pushed to the Swing text area.
 */
public class NodeImpl extends UnicastRemoteObject implements NodeInterface {

    private static final long serialVersionUID = 1L;

    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ── State ─────────────────────────────────────────────────────────────────
    private final String          nodeId;
    private final ExecutorService pool;

    private final AtomicInteger activeTasks    = new AtomicInteger(0);
    private final AtomicInteger completedTasks = new AtomicInteger(0);
    private final AtomicInteger forwardedTasks = new AtomicInteger(0);
    private final AtomicInteger receivedTasks  = new AtomicInteger(0);

    // peer address → cached stub (null = offline)
    private final Map<String, NodeInterface> peerCache = new LinkedHashMap<>();
    private long lastPeerRefresh = 0;

    // Optional GUI log callback
    private Consumer<String> logListener;

    // ── Constructor ───────────────────────────────────────────────────────────

    public NodeImpl(String nodeId) throws RemoteException {
        super();
        this.nodeId = nodeId;
        this.pool   = Executors.newFixedThreadPool(
                ClusterConfig.MAX_THREADS, r -> {
                    Thread t = new Thread(r, nodeId + "-Worker");
                    t.setDaemon(true);
                    return t;
                });

        // Pre-populate peer cache with nulls (will be resolved on first use)
        for (String addr : ClusterConfig.allAddresses()) {
            String name = addrToName(addr);
            if (!name.equals(nodeId)) {        // skip self
                peerCache.put(addr, null);
            }
        }

        log("STARTED", "pool=" + ClusterConfig.MAX_THREADS
                + " overload=" + (int)(ClusterConfig.OVERLOAD_THRESHOLD * 100) + "%"
                + " peers=" + peerCache.keySet());
    }

    public void setLogListener(Consumer<String> listener) {
        this.logListener = listener;
    }

    // ── NodeInterface (called remotely) ───────────────────────────────────────

    @Override
    public String submitTask(Task task) throws RemoteException {
        double load = getCurrentLoad();

        // ── Try to forward if overloaded ─────────────────────────────────────
        if (load >= ClusterConfig.OVERLOAD_THRESHOLD) {
            NodeInterface target = leastLoadedPeer(load);
            if (target != null) {
                try {
                    String peerId   = target.getNodeId();
                    double peerLoad = target.getCurrentLoad();
                    log("OFFLOAD",
                        String.format("%s  myLoad=%.0f%%→%s(%.0f%%)",
                                task.getTaskId(), load * 100, peerId, peerLoad * 100));
                    forwardedTasks.incrementAndGet();
                    return target.submitTask(task);
                } catch (RemoteException e) {
                    log("WARN", "Forward failed, processing locally: " + e.getMessage());
                }
            } else {
                log("SATURATED",
                    String.format("%s  load=%.0f%% all peers busy — local fallback",
                            task.getTaskId(), load * 100));
            }
        }

        // ── Accept locally ────────────────────────────────────────────────────
        boolean wasForwarded = !task.getOriginNodeId().equals(nodeId);
        if (wasForwarded) receivedTasks.incrementAndGet();

        int slot = activeTasks.incrementAndGet();
        log("ACCEPT",
            String.format("%s  active=%d/%d (%.0f%%)%s",
                    task, slot, ClusterConfig.MAX_THREADS,
                    (double) slot / ClusterConfig.MAX_THREADS * 100,
                    wasForwarded ? "  ★ rcv-from=" + task.getOriginNodeId() : ""));

        try {
            String result = pool.submit(() -> {
                try {
                    return TaskProcessor.process(task);
                } finally {
                    int rem = activeTasks.decrementAndGet();
                    completedTasks.incrementAndGet();
                    log("DONE", String.format("%s  active=%d/%d (%.0f%%)",
                            task.getTaskId(), rem, ClusterConfig.MAX_THREADS,
                            (double) rem / ClusterConfig.MAX_THREADS * 100));
                }
            }).get();

            return result;

        } catch (ExecutionException | InterruptedException e) {
            activeTasks.decrementAndGet();
            throw new RemoteException("Execution failed: " + e.getMessage(), e);
        }
    }

    @Override
    public double getCurrentLoad() throws RemoteException {
        return Math.min(1.0, (double) activeTasks.get() / ClusterConfig.MAX_THREADS);
    }

    @Override
    public String getNodeId() throws RemoteException { return nodeId; }

    /** Local (non-RMI) accessor — avoids try/catch in GUI code. */
    public String getNodeId(boolean remote) { return nodeId; }

    @Override
    public int getActiveTaskCount() throws RemoteException { return activeTasks.get(); }

    @Override
    public boolean isAlive() throws RemoteException { return true; }

    @Override
    public NodeStatus getStatus() throws RemoteException {
        return buildStatus();
    }

    /** Non-RMI local wrapper — used by GUI refresh timer (no try/catch needed). */
    public NodeStatus getLocalStatus() {
        return buildStatus();
    }

    private NodeStatus buildStatus() {
        double load = Math.min(1.0, (double)activeTasks.get() / ClusterConfig.MAX_THREADS);
        return new NodeStatus(nodeId, load,
                activeTasks.get(), completedTasks.get(),
                forwardedTasks.get(), receivedTasks.get(),
                ClusterConfig.MAX_THREADS, true);
    }

    // ── Load balancing helpers ────────────────────────────────────────────────

    private synchronized NodeInterface leastLoadedPeer(double myLoad) {
        long now = System.currentTimeMillis();
        if (now - lastPeerRefresh > ClusterConfig.PEER_CACHE_TTL_MS) {
            refreshPeers();
            lastPeerRefresh = now;
        }

        NodeInterface best     = null;
        double        bestLoad = myLoad;  // only forward if strictly less busy

        for (Map.Entry<String, NodeInterface> e : peerCache.entrySet()) {
            NodeInterface peer = e.getValue();
            if (peer == null) continue;
            try {
                // Guard: never forward to ourselves in case addrToName()
                // failed to filter self out during construction.
                if (nodeId.equals(peer.getNodeId())) { e.setValue(null); continue; }
                double pl = peer.getCurrentLoad();
                if (pl < bestLoad) { bestLoad = pl; best = peer; }
            } catch (RemoteException ex) {
                log("PEER-DOWN", e.getKey() + " — " + ex.getMessage());
                e.setValue(null);   // mark offline until next refresh
            }
        }
        return best;
    }

    private void refreshPeers() {
        int live = 0;
        for (String addr : new ArrayList<>(peerCache.keySet())) {
            try {
                NodeInterface p = (NodeInterface) java.rmi.Naming.lookup(addr);
                p.isAlive();        // confirm it responds
                peerCache.put(addr, p);
                live++;
            } catch (Exception e) {
                peerCache.put(addr, null);
                log("PEER-OFFLINE", addr);
            }
        }
        log("PEERS", "live=" + live + "/" + peerCache.size());
    }

    // ── Peer status for GUI ───────────────────────────────────────────────────

    /**
     * Returns a NodeStatus for each peer (online or offline placeholder).
     * Called by the GUI refresh timer.
     */
    public synchronized List<NodeStatus> getPeerStatuses() {
        List<NodeStatus> list = new ArrayList<>();
        for (Map.Entry<String, NodeInterface> e : peerCache.entrySet()) {
            String name = addrToName(e.getKey());
            if (e.getValue() != null) {
                try {
                    list.add(e.getValue().getStatus());
                    continue;
                } catch (RemoteException ex) {
                    e.setValue(null);
                }
            }
            list.add(NodeStatus.offline(name));
        }
        return list;
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    public void shutdown() {
        pool.shutdownNow();
        log("STOPPED", "done=" + completedTasks + " fwd=" + forwardedTasks
                + " rcv=" + receivedTasks);
    }

    // ── Logging ───────────────────────────────────────────────────────────────

    public void log(String event, String detail) {
        String line = String.format("[%s] %-8s  %-10s  %s",
                LocalTime.now().format(TS), nodeId, event, detail);
        System.out.println(line);
        if (logListener != null) logListener.accept(line);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static String addrToName(String addr) {
        String[] names = ClusterConfig.allNames();
        String[] addrs = ClusterConfig.allAddresses();
        for (int i = 0; i < addrs.length; i++)
            if (addrs[i].equals(addr)) return names[i];
        return addr;
    }
}
// appended