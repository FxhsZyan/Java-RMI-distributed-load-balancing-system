package loadbalancer;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concrete implementation of a distributed node.
 *
 * Each node is simultaneously:
 *   • An RMI server  — accepts tasks from other nodes or the task generator
 *   • A load balancer — forwards overflow tasks to the least-loaded peer
 *   • A task generator — periodically creates its own work
 *
 * Architecture decisions:
 *   • Thread pool size = MAX_THREADS limits concurrency and provides backpressure
 *   • Load is reported as activeTasks / MAX_THREADS (capped at 1.0)
 *   • Tasks are forwarded when load > OVERLOAD_THRESHOLD
 *   • If all peers are also overloaded, the task is queued locally anyway
 *     (no tasks are dropped — fault tolerance for overload)
 */
public class NodeImpl extends UnicastRemoteObject implements NodeInterface {

    private static final long serialVersionUID = 1L;

    // ── Configuration constants ───────────────────────────────────────────────
    /** Max concurrent tasks before this node is considered "overloaded" */
    private static final int    MAX_THREADS         = 4;

    /** Load fraction above which tasks get forwarded (0.75 = 75% busy) */
    private static final double OVERLOAD_THRESHOLD  = 0.75;

    /** How long a peer lookup is cached before re-checking liveness (ms) */
    private static final long   PEER_CACHE_TTL_MS   = 5_000;

    // ── State ─────────────────────────────────────────────────────────────────
    private final String           nodeId;
    private final ExecutorService  pool;
    private final AtomicInteger    activeTasks    = new AtomicInteger(0);
    private final AtomicInteger    completedTasks = new AtomicInteger(0);
    private final AtomicInteger    forwardedTasks = new AtomicInteger(0);

    /** RMI binding addresses of peer nodes, e.g. "rmi://localhost:1200/Node-2" */
    private final List<String>     peerAddresses  = new ArrayList<>();

    /** Cached live peer stubs (refreshed every PEER_CACHE_TTL_MS) */
    private final List<NodeInterface> livePeers   = new ArrayList<>();
    private long   lastPeerRefresh = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    public NodeImpl(String nodeId) throws RemoteException {
        super(); // exports this object so RMI can use it
        this.nodeId = nodeId;
        this.pool   = Executors.newFixedThreadPool(MAX_THREADS, r -> {
            Thread t = new Thread(r, nodeId + "-Worker");
            t.setDaemon(true);
            return t;
        });
        log("Node started (max threads: " + MAX_THREADS + ")");
    }

    // ── NodeInterface implementation ──────────────────────────────────────────

    @Override
    public String submitTask(Task task) throws RemoteException {
        double load = getCurrentLoad();

        // If we are overloaded, try to forward the task to a less-loaded peer
        if (load >= OVERLOAD_THRESHOLD) {
            NodeInterface target = findLeastLoadedPeer(this);
            if (target != null) {
                try {
                    log(String.format("⇢ Forwarding %s (load=%.0f%%) → %s",
                            task, load * 100, target.getNodeId()));
                    forwardedTasks.incrementAndGet();
                    return target.submitTask(task);
                } catch (RemoteException e) {
                    // Peer died between discovery and forward — process locally
                    log("Peer became unavailable during forward, processing locally: " + e.getMessage());
                }
            }
        }

        // Process the task locally in the thread pool
        activeTasks.incrementAndGet();
        log(String.format("▶ Accepting %s (load=%.0f%%)", task, load * 100));

        // We submit to the pool and block until done (RMI call is synchronous)
        try {
            String result = pool.submit(() -> {
                try {
                    return TaskProcessor.process(task);
                } finally {
                    activeTasks.decrementAndGet();
                    completedTasks.incrementAndGet();
                }
            }).get(); // .get() blocks the calling RMI thread until the task finishes

            log("✔ Done: " + result);
            return result;

        } catch (Exception e) {
            activeTasks.decrementAndGet();
            throw new RemoteException("Task execution failed: " + e.getMessage(), e);
        }
    }

    @Override
    public double getCurrentLoad() throws RemoteException {
        return Math.min(1.0, (double) activeTasks.get() / MAX_THREADS);
    }

    @Override
    public String getNodeId() throws RemoteException {
        return nodeId;
    }

    @Override
    public int getActiveTaskCount() throws RemoteException {
        return activeTasks.get();
    }

    @Override
    public boolean isAlive() throws RemoteException {
        return true; // if we can call this, the node is alive
    }

    @Override
    public NodeStatus getStatus() throws RemoteException {
        return new NodeStatus(
                nodeId,
                getCurrentLoad(),
                activeTasks.get(),
                completedTasks.get(),
                forwardedTasks.get()
        );
    }

    @Override
    public synchronized void registerPeers(List<String> addresses) throws RemoteException {
        peerAddresses.clear();
        peerAddresses.addAll(addresses);
        livePeers.clear();      // force refresh on next task
        lastPeerRefresh = 0;
        log("Registered " + addresses.size() + " peer(s): " + addresses);
    }

    // ── Load Balancing ────────────────────────────────────────────────────────

    /**
     * Returns the live peer with the lowest current load, or null if all peers
     * are as loaded as (or more loaded than) this node.
     *
     * Also refreshes the live peer list when the cache TTL expires, removing
     * any peers that no longer respond — this is the fault-tolerance mechanism.
     */
    private synchronized NodeInterface findLeastLoadedPeer(NodeImpl self) {
        long now = System.currentTimeMillis();

        // Refresh the live peer cache periodically
        if (now - lastPeerRefresh > PEER_CACHE_TTL_MS) {
            refreshLivePeers();
            lastPeerRefresh = now;
        }

        NodeInterface best      = null;
        double        bestLoad  = Double.MAX_VALUE;

        for (NodeInterface peer : livePeers) {
            try {
                double peerLoad = peer.getCurrentLoad();
                if (peerLoad < bestLoad) {
                    bestLoad = peerLoad;
                    best     = peer;
                }
            } catch (RemoteException e) {
                // Peer went offline; will be pruned on next refresh
                log("Peer unreachable during load query: " + e.getMessage());
            }
        }

        // Only forward if the best peer is actually less loaded than us
        try {
            if (best != null && bestLoad < self.getCurrentLoad()) {
                return best;
            }
        } catch (RemoteException ignored) {}

        return null;
    }

    /**
     * Rebuilds the livePeers list by trying to look up each registered address.
     * Peers that throw an exception are silently excluded (fault tolerance).
     */
    private void refreshLivePeers() {
        livePeers.clear();
        for (String addr : peerAddresses) {
            try {
                NodeInterface peer = (NodeInterface) java.rmi.Naming.lookup(addr);
                if (peer.isAlive()) {
                    livePeers.add(peer);
                }
            } catch (Exception e) {
                log("⚠ Peer offline or unreachable: " + addr + " (" + e.getMessage() + ")");
            }
        }
        log("Live peer count: " + livePeers.size() + "/" + peerAddresses.size());
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    public void shutdown() {
        pool.shutdownNow();
        log("Node shutting down.");
    }

    // ── Logging ───────────────────────────────────────────────────────────────

    private void log(String msg) {
        System.out.printf("[%s] %s%n", nodeId, msg);
    }
}
