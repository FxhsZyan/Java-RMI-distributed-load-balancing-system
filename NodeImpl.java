package loadbalancer;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ═══════════════════════════════════════════════════════════════
 *  NodeImpl — the heart of the distributed load balancing system
 * ═══════════════════════════════════════════════════════════════
 *
 * Each node is simultaneously:
 *  • An RMI SERVER   — accepts tasks from generators and other nodes
 *  • A LOAD BALANCER — forwards overflow tasks to the least-loaded peer
 *
 * Load balancing logic
 * ────────────────────
 *  • activeTasks / MAX_THREADS = current load (0.0 – 1.0)
 *  • If load ≥ OVERLOAD_THRESHOLD, find the peer with the lowest load
 *  • If that peer is less loaded than us, forward the task to it
 *  • If all peers are equally busy, process locally (no task is dropped)
 *
 * Fault tolerance
 * ───────────────
 *  • Live peer stubs are cached and refreshed every PEER_CACHE_TTL_MS
 *  • Any peer that throws RemoteException is removed from the live list
 *  • Tasks whose forward target dies mid-transfer are processed locally
 */
public class NodeImpl extends UnicastRemoteObject implements NodeInterface {

    private static final long serialVersionUID = 1L;

    // ── Tunable constants ─────────────────────────────────────────────────────

    /** Thread pool size — raise this to allow more concurrent tasks. */
    static final int MAX_THREADS = 6;

    /**
     * Load fraction (0–1) above which this node is considered overloaded
     * and will attempt to forward new tasks to a less-busy peer.
     * 0.67 = 4 of 6 threads busy → start offloading.
     */
    private static final double OVERLOAD_THRESHOLD = 0.67;

    /** Milliseconds between live-peer cache refreshes. */
    private static final long PEER_CACHE_TTL_MS = 4_000;

    // ── State ─────────────────────────────────────────────────────────────────

    private final String          nodeId;
    private final ExecutorService pool;

    private final AtomicInteger activeTasks    = new AtomicInteger(0);
    private final AtomicInteger completedTasks = new AtomicInteger(0);
    private final AtomicInteger forwardedTasks = new AtomicInteger(0);
    private final AtomicInteger receivedTasks  = new AtomicInteger(0);

    private final List<String>        peerAddresses = new ArrayList<>();
    private final List<NodeInterface> livePeers     = new ArrayList<>();
    private       long                lastPeerRefresh = 0;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ── Constructor ───────────────────────────────────────────────────────────

    public NodeImpl(String nodeId) throws RemoteException {
        super();
        this.nodeId = nodeId;
        this.pool   = Executors.newFixedThreadPool(MAX_THREADS, r -> {
            Thread t = new Thread(r, nodeId + "-Worker");
            t.setDaemon(true);
            return t;
        });
        log("STARTED", "thread pool size=" + MAX_THREADS
                + "  overload threshold=" + (int)(OVERLOAD_THRESHOLD * 100) + "%");
    }

    // ── NodeInterface ─────────────────────────────────────────────────────────

    @Override
    public String submitTask(Task task) throws RemoteException {
        double load = getCurrentLoad();

        // ── Overload check: try to forward before accepting ──────────────────
        if (load >= OVERLOAD_THRESHOLD) {
            NodeInterface target = leastLoadedPeer();
            if (target != null) {
                try {
                    double peerLoad = target.getCurrentLoad();
                    String peerId   = target.getNodeId();
                    log("OFFLOAD",
                        String.format("%s  load=%.0f%%→forwarding to %s (load=%.0f%%)",
                                task.getTaskId(), load * 100, peerId, peerLoad * 100));
                    forwardedTasks.incrementAndGet();
                    return target.submitTask(task);
                } catch (RemoteException e) {
                    log("WARN", "Forward failed, processing locally: " + e.getMessage());
                }
            } else {
                log("OVERLOAD",
                    String.format("%s  load=%.0f%% — all peers busy, processing locally",
                            task.getTaskId(), load * 100));
            }
        }

        // ── Accept the task locally ──────────────────────────────────────────
        boolean wasForwarded = !task.getOriginNodeId().equals(nodeId);
        if (wasForwarded) receivedTasks.incrementAndGet();

        activeTasks.incrementAndGet();
        int active = activeTasks.get();
        log("ACCEPT",
            String.format("%s  active=%d/%d (%.0f%%)%s",
                    task, active, MAX_THREADS, (double) active / MAX_THREADS * 100,
                    wasForwarded ? "  ★RECEIVED FROM " + task.getOriginNodeId() : ""));

        try {
            // Submit to thread pool and block the calling RMI thread
            String result = pool.submit(() -> {
                try {
                    return TaskProcessor.process(task);
                } finally {
                    int remaining = activeTasks.decrementAndGet();
                    completedTasks.incrementAndGet();
                    log("COMPLETE",
                        String.format("%s  active now=%d/%d (%.0f%%)",
                                task.getTaskId(), remaining, MAX_THREADS,
                                (double) remaining / MAX_THREADS * 100));
                }
            }).get(); // .get() blocks — keeps the RMI call synchronous

            return result;

        } catch (ExecutionException | InterruptedException e) {
            activeTasks.decrementAndGet();
            throw new RemoteException("Task execution failed: " + e.getMessage(), e);
        }
    }

    @Override
    public double getCurrentLoad() throws RemoteException {
        return Math.min(1.0, (double) activeTasks.get() / MAX_THREADS);
    }

    @Override
    public String getNodeId() throws RemoteException { return nodeId; }

    @Override
    public int getActiveTaskCount() throws RemoteException { return activeTasks.get(); }

    @Override
    public boolean isAlive() throws RemoteException { return true; }

    @Override
    public NodeStatus getStatus() throws RemoteException {
        return new NodeStatus(nodeId, getCurrentLoad(),
                activeTasks.get(), completedTasks.get(),
                forwardedTasks.get(), receivedTasks.get(), MAX_THREADS);
    }

    @Override
    public synchronized void registerPeers(List<String> addresses) throws RemoteException {
        peerAddresses.clear();
        peerAddresses.addAll(addresses);
        livePeers.clear();
        lastPeerRefresh = 0;
        log("PEERS", "registered " + addresses.size() + " peer(s): " + addresses);
    }

    // ── Load balancing helpers ────────────────────────────────────────────────

    /**
     * Returns the live peer with the lowest load that is actually less loaded
     * than this node, or null if no such peer exists.
     */
    private synchronized NodeInterface leastLoadedPeer() {
        long now = System.currentTimeMillis();
        if (now - lastPeerRefresh > PEER_CACHE_TTL_MS) {
            refreshLivePeers();
            lastPeerRefresh = now;
        }

        NodeInterface best     = null;
        double        bestLoad = Double.MAX_VALUE;

        for (NodeInterface peer : livePeers) {
            try {
                double pl = peer.getCurrentLoad();
                if (pl < bestLoad) { bestLoad = pl; best = peer; }
            } catch (RemoteException e) {
                log("WARN", "Peer unreachable during load query: " + e.getMessage());
            }
        }

        try {
            if (best != null && bestLoad < getCurrentLoad()) return best;
        } catch (RemoteException ignored) {}

        return null;
    }

    /** Rebuilds livePeers, silently dropping any that don't respond. */
    private void refreshLivePeers() {
        livePeers.clear();
        for (String addr : peerAddresses) {
            try {
                NodeInterface p = (NodeInterface) java.rmi.Naming.lookup(addr);
                if (p.isAlive()) livePeers.add(p);
            } catch (Exception e) {
                log("PEER-DOWN", addr + " — " + e.getMessage());
            }
        }
        if (!peerAddresses.isEmpty()) {
            log("PEERS", "live=" + livePeers.size() + "/" + peerAddresses.size());
        }
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    public void shutdown() {
        pool.shutdownNow();
        log("STOPPED", "completed=" + completedTasks.get()
                + "  forwarded=" + forwardedTasks.get()
                + "  received=" + receivedTasks.get());
    }

    // ── Logging ───────────────────────────────────────────────────────────────

    /** Structured, timestamped log line for easy console reading. */
    void log(String event, String detail) {
        System.out.printf("[%s] %-8s %-10s %s%n",
                LocalTime.now().format(TS), nodeId, event, detail);
    }
}