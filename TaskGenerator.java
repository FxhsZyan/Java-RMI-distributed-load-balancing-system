package loadbalancer;

import java.rmi.RemoteException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Continuously generates CPU-intensive tasks and submits them to the cluster.
 *
 * Design:
 *  - SENDER_THREADS concurrent submission threads per generator
 *  - Staggered starts so they don't all fire simultaneously
 *  - Heavy task weights: 40% matrix (largest), 35% sort, 25% prime
 *  - Fast enough submission rate to reliably trigger load-balancing forwarding
 */
public class TaskGenerator {

    private static final int  SENDER_THREADS  = 3;
    private static final int  SUBMIT_DELAY_MS = 300;

    private final String                   genId;
    private final List<NodeInterface>      nodes;
    private final ScheduledExecutorService scheduler;
    private final Random                   rng = new Random();
    private final NodeImpl                 localNode; // for logging

    public TaskGenerator(String genId, List<NodeInterface> nodes, NodeImpl localNode) {
        this.genId     = genId;
        this.nodes     = nodes;
        this.localNode = localNode;
        this.scheduler = Executors.newScheduledThreadPool(SENDER_THREADS, r -> {
            Thread t = new Thread(r, "Gen-" + genId + "-Sender");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        for (int i = 0; i < SENDER_THREADS; i++) {
            final int sid = i;
            scheduler.scheduleWithFixedDelay(
                    () -> submitOne(sid),
                    sid * 80L,
                    SUBMIT_DELAY_MS,
                    TimeUnit.MILLISECONDS);
        }
        localNode.log("GEN-START",
                genId + " — " + SENDER_THREADS + " senders @ " + SUBMIT_DELAY_MS + "ms");
    }

    private void submitOne(int sid) {
        List<NodeInterface> snapshot;
        synchronized (nodes) { snapshot = new ArrayList<>(nodes); }
        if (snapshot.isEmpty()) { localNode.log("GEN-WARN", "no nodes"); return; }

        NodeInterface target = snapshot.get(rng.nextInt(snapshot.size()));
        Task          task   = heavyTask();

        try {
            String tid = target.getNodeId();
            double tl  = target.getCurrentLoad();
            localNode.log("GEN-SEND",
                    String.format("[s%d] %s → %s (load=%.0f%%)", sid, task.getTaskId(), tid, tl * 100));
            String result = target.submitTask(task);
            localNode.log("GEN-RESULT", "[s" + sid + "] " + result);
        } catch (RemoteException e) {
            localNode.log("GEN-ERR", "node unreachable, removing: " + e.getMessage());
            synchronized (nodes) { nodes.remove(target); }
        }
    }

    /**
     * Heavy task distribution:
     *  40% MATRIX_MULTIPLY  N = 1200–1800  (several seconds each)
     *  35% SORT_ARRAY        10–20 M ints
     *  25% PRIME_SEARCH      10–50 M limit
     */
    private Task heavyTask() {
        int roll = rng.nextInt(100);
        Task.TaskType type;
        int param;
        if (roll < 40) {
            type  = Task.TaskType.MATRIX_MULTIPLY;
            param = 1200 + rng.nextInt(600);   // 1200–1800
        } else if (roll < 75) {
            type  = Task.TaskType.SORT_ARRAY;
            param = 10_000_000 + rng.nextInt(10_000_000); // 10–20 M
        } else {
            type  = Task.TaskType.PRIME_SEARCH;
            param = 10_000_000 + rng.nextInt(40_000_000); // 10–50 M
        }
        return new Task(type, param, genId);
    }

    public void stop() {
        scheduler.shutdownNow();
        localNode.log("GEN-STOP", genId);
    }
}
