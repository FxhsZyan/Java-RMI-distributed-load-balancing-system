package loadbalancer;

import java.rmi.RemoteException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Aggressively generates CPU-intensive tasks and submits them to the cluster.
 *
 * Design goals
 * ────────────
 *  • Submission rate is fast enough to keep all node threads busy most of the time,
 *    so load balancing (forwarding) actually happens and is visible in the logs.
 *  • Each generator maintains a small thread pool of its own so multiple tasks
 *    can be in-flight simultaneously (RMI submitTask() is blocking per-call).
 *  • Tasks are weighted toward the HEAVIEST types (matrix multiply, large sort)
 *    so CPU usage is clearly visible in Task Manager / top.
 */
public class TaskGenerator {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** How many concurrent submission threads this generator uses. */
    private static final int SENDER_THREADS = 4;

    /** Delay between task submissions per sender thread (ms). Short = more pressure. */
    private static final int SUBMIT_DELAY_MS = 200;

    private final String                   id;
    private final List<NodeInterface>      nodes;
    private final ScheduledExecutorService scheduler;
    private final Random                   rng = new Random();

    public TaskGenerator(String id, List<NodeInterface> nodes) {
        this.id    = id;
        this.nodes = nodes;
        this.scheduler = Executors.newScheduledThreadPool(SENDER_THREADS, r -> {
            Thread t = new Thread(r, "Gen-" + id + "-Sender");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts SENDER_THREADS concurrent submission loops. */
    public void start() {
        for (int i = 0; i < SENDER_THREADS; i++) {
            final int senderId = i;
            // Stagger the starts so they don't all fire at the same instant
            scheduler.scheduleWithFixedDelay(
                    () -> submitOne(senderId),
                    senderId * 50L,        // initial delay (stagger)
                    SUBMIT_DELAY_MS,       // between submissions
                    TimeUnit.MILLISECONDS);
        }
        log("STARTED", SENDER_THREADS + " sender threads, delay=" + SUBMIT_DELAY_MS + "ms");
    }

    private void submitOne(int senderId) {
        if (nodes.isEmpty()) { log("WARN", "No nodes available"); return; }

        // Pick target node at random — simulates uneven real-world arrival
        NodeInterface target = nodes.get(rng.nextInt(nodes.size()));
        Task          task   = randomHeavyTask();

        try {
            String targetId = target.getNodeId();
            double tLoad    = target.getCurrentLoad();
            log("SEND",
                String.format("[sender-%d] %s → %s (load=%.0f%%)",
                        senderId, task.getTaskId(), targetId, tLoad * 100));

            String result = target.submitTask(task);
            log("RESULT", "[sender-" + senderId + "] " + result);

        } catch (RemoteException e) {
            log("ERROR", "Node unreachable, removing: " + e.getMessage());
            synchronized (nodes) { nodes.remove(target); }
        }
    }

    /**
     * Produces a task weighted toward heavy computations.
     * Weight: 40% matrix, 35% sort, 25% prime — all with large parameters
     * so MIN_BURN_MS in TaskProcessor is easily exceeded.
     */
    private Task randomHeavyTask() {
        int roll = rng.nextInt(100);
        Task.TaskType type;
        int param;

        if (roll < 40) {
            // Matrix multiply N=350–500 → ~200–800 ms per iteration
            type  = Task.TaskType.MATRIX_MULTIPLY;
            param = 350 + rng.nextInt(150);
        } else if (roll < 75) {
            // Sort 5–15 million elements → ~400–1500 ms per iteration
            type  = Task.TaskType.SORT_ARRAY;
            param = 5_000_000 + rng.nextInt(10_000_000);
        } else {
            // Prime sieve up to 3–8 million → ~80–250 ms per iteration
            type  = Task.TaskType.PRIME_SEARCH;
            param = 3_000_000 + rng.nextInt(5_000_000);
        }

        return new Task(type, param, id);
    }

    public void stop() {
        scheduler.shutdownNow();
        log("STOPPED", "");
    }

    private void log(String event, String detail) {
        System.out.printf("[%s] %-12s %-10s %s%n",
                LocalTime.now().format(TS), "GEN-" + id, event, detail);
    }
}