package loadbalancer;

import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  MAIN ENTRY POINT — starts a full local cluster for testing
 * ═══════════════════════════════════════════════════════════════════
 *
 * Run in NetBeans:
 *   Right-click project → Properties → Run → Main Class: loadbalancer.NodeLauncher
 *   Arguments field: 3        (or leave blank for default 3 nodes)
 *   Then press F6.
 *
 * From the command line:
 *   java -jar dist/DistributedLoadBalancer.jar [numNodes]
 *
 * Ports: BASE_PORT, BASE_PORT+1, … (one per node, no conflicts)
 *
 * If you get "Port already in use":
 *   Windows → taskkill /F /IM java.exe
 *   macOS   → killall java
 */
public class NodeLauncher {

    private static final String HOST      = "localhost";
    private static final int    BASE_PORT = 1100;
    private static final int    DEFAULT_NODES = 3;

    public static void main(String[] args) throws Exception {

        int numNodes = (args.length > 0) ? Integer.parseInt(args[0]) : DEFAULT_NODES;

        banner(numNodes);

        // ── 1. Boot one RMI registry + NodeImpl per node ─────────────────────
        List<NodeImpl>      nodeImpls = new ArrayList<>();
        List<NodeInterface> nodeStubs = new ArrayList<>();
        List<String>        allAddrs  = new ArrayList<>();

        for (int i = 0; i < numNodes; i++) {
            int    port   = BASE_PORT + i;
            String nodeId = "Node-" + (i + 1);
            String addr   = "rmi://" + HOST + ":" + port + "/" + nodeId;

            // Reuse existing registry if port is already bound (safe restart)
            try {
                LocateRegistry.createRegistry(port);
            } catch (java.rmi.server.ExportException e) {
                System.out.println("  [warn] Port " + port + " reusing existing registry");
                LocateRegistry.getRegistry(HOST, port);
            }

            NodeImpl node = new NodeImpl(nodeId);
            Naming.rebind(addr, node);
            nodeImpls.add(node);
            allAddrs.add(addr);
            System.out.println("  ✔  " + nodeId + " bound at " + addr);
        }

        // ── 2. Exchange peer lists ────────────────────────────────────────────
        for (int i = 0; i < numNodes; i++) {
            List<String> peers = new ArrayList<>(allAddrs);
            peers.remove(allAddrs.get(i));
            nodeImpls.get(i).registerPeers(peers);
        }

        // ── 3. Lookup stubs for monitor + generators ──────────────────────────
        for (String addr : allAddrs) {
            nodeStubs.add((NodeInterface) Naming.lookup(addr));
        }

        // ── 4. Live dashboard ─────────────────────────────────────────────────
        Monitor monitor = new Monitor(nodeStubs);
        monitor.start();

        // ── 5. Task generators — one per node, targeting all nodes ────────────
        // Multiple generators + multiple sender threads per generator = high load
        List<TaskGenerator> generators = new ArrayList<>();
        for (int i = 0; i < numNodes; i++) {
            TaskGenerator gen = new TaskGenerator("G" + (i + 1),
                    new ArrayList<>(nodeStubs));
            gen.start();
            generators.add(gen);
        }

        // ── 6. Shutdown hook: unbind RMI names → ports freed immediately ──────
        final List<String> boundAddrs = new ArrayList<>(allAddrs);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Launcher] Shutting down…");
            generators.forEach(TaskGenerator::stop);
            monitor.stop();
            nodeImpls.forEach(NodeImpl::shutdown);
            boundAddrs.forEach(addr -> {
                try { Naming.unbind(addr); } catch (Exception ignored) {}
            });
            System.out.println("[Launcher] Clean shutdown complete. Ports are free.");
        }));

        System.out.println();
        System.out.println("  Cluster running. Watch the logs. Press Ctrl+C to stop.");
        System.out.println();

        Thread.currentThread().join();
    }

    private static void banner(int n) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║   Distributed Load Balancer  ·  Java RMI             ║");
        System.out.println("║   Nodes: " + n + "  ·  Threads/node: " + NodeImpl.MAX_THREADS
                + "  ·  Overload @ 67%              ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }
}