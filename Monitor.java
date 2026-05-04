package loadbalancer;

import java.rmi.RemoteException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically polls all nodes and prints a live dashboard to the console.
 *
 * Example output:
 * ══════════════════════════════════════════════
 *  Cluster Status  [12:34:56]
 * ══════════════════════════════════════════════
 *  Node-1   | load= 75% | active= 3 | done= 42 | fwd= 5
 *  Node-2   | load= 25% | active= 1 | done= 38 | fwd= 2
 *  Node-3   | load=  0% | active= 0 | done= 31 | fwd= 0
 * ══════════════════════════════════════════════
 */
public class Monitor {

    private final List<NodeInterface>      nodes;
    private final ScheduledExecutorService scheduler;
    private static final int INTERVAL_SECONDS = 5;

    public Monitor(List<NodeInterface> nodes) {
        this.nodes     = nodes;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Monitor");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::printDashboard,
                INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
        System.out.println("[Monitor] Dashboard polling every " + INTERVAL_SECONDS + "s");
    }

    private void printDashboard() {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println();
        System.out.println("══════════════════════════════════════════════════");
        System.out.printf ("  Cluster Status  [%s]%n", time);
        System.out.println("══════════════════════════════════════════════════");

        for (NodeInterface node : nodes) {
            try {
                NodeStatus s = node.getStatus();
                // Visual load bar (10 chars wide)
                String bar = loadBar(s.getLoad(), 10);
                System.out.printf("  %s  [%s]%n", s, bar);
            } catch (RemoteException e) {
                System.out.println("  ??? (node unreachable: " + e.getMessage() + ")");
            }
        }

        System.out.println("══════════════════════════════════════════════════");
    }

    /** Returns a simple ASCII bar like [████░░░░░░] */
    private static String loadBar(double load, int width) {
        int filled = (int) Math.round(load * width);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? '█' : '░');
        }
        sb.append(']');
        return sb.toString();
    }

    public void stop() {
        scheduler.shutdownNow();
    }
}
