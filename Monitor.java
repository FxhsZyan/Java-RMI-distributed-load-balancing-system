package loadbalancer;

import java.rmi.RemoteException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.*;

/**
 * Prints a rich dashboard to the console every INTERVAL_SECONDS.
 *
 * Example output:
 * ════════════════════════════════════════════════════════════════════════
 *  CLUSTER DASHBOARD  [12:34:56]   nodes=3   total-completed=127
 * ════════════════════════════════════════════════════════════════════════
 *  Node-1  | load= 83% [5/6] |████████░░| done= 48 | fwd-out= 12 | fwd-in=  3
 *  Node-2  | load= 50% [3/6] |█████░░░░░| done= 43 | fwd-out=  4 | fwd-in=  9
 *  Node-3  | load= 17% [1/6] |██░░░░░░░░| done= 36 | fwd-out=  1 | fwd-in=  7
 * ────────────────────────────────────────────────────────────────────────
 *  LOAD BALANCE PROOF: tasks have been redistributed between nodes ✔
 * ════════════════════════════════════════════════════════════════════════
 */
public class Monitor {

    private static final int INTERVAL_SECONDS = 4;
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int BAR_WIDTH = 10;

    private final List<NodeInterface>      nodes;
    private final ScheduledExecutorService scheduler;

    public Monitor(List<NodeInterface> nodes) {
        this.nodes     = nodes;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Monitor");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::print,
                INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
        System.out.println("[Monitor] Dashboard every " + INTERVAL_SECONDS + "s");
    }

    private void print() {
        String time = LocalTime.now().format(TS);
        int totalDone = 0;
        int totalFwd  = 0;
        int totalRcv  = 0;

        // ── Collect status from all nodes ────────────────────────────────────
        NodeStatus[] statuses = new NodeStatus[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            try {
                statuses[i] = nodes.get(i).getStatus();
                totalDone  += statuses[i].getCompletedTasks();
                totalFwd   += statuses[i].getForwardedTasks();
                totalRcv   += statuses[i].getReceivedTasks();
            } catch (RemoteException e) {
                // node offline — leave null
            }
        }

        // ── Print dashboard ──────────────────────────────────────────────────
        String sep  = "═".repeat(76);
        String thin = "─".repeat(76);
        System.out.println();
        System.out.println(sep);
        System.out.printf("  CLUSTER DASHBOARD  [%s]   nodes=%d   total-completed=%d%n",
                time, nodes.size(), totalDone);
        System.out.println(sep);

        for (NodeStatus s : statuses) {
            if (s == null) {
                System.out.println("  ??? — node unreachable");
                continue;
            }
            String bar = bar(s.getLoad(), BAR_WIDTH);
            System.out.printf("  %s  |%s|%n", s, bar);
        }

        System.out.println(thin);

        // ── Load-balancing proof line ────────────────────────────────────────
        if (totalFwd > 0) {
            System.out.printf("  ✔  LOAD BALANCING ACTIVE: %d task(s) forwarded across nodes%n",
                    totalFwd);
        } else {
            System.out.println("  ⏳ Load balancing not yet triggered (nodes not overloaded)");
        }
        System.out.println(sep);
    }

    private static String bar(double load, int width) {
        int filled = (int) Math.round(load * width);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < width; i++) sb.append(i < filled ? '█' : '░');
        return sb.toString();
    }

    public void stop() { scheduler.shutdownNow(); }
}