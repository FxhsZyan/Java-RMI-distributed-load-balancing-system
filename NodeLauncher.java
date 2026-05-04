package loadbalancer;

import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  MAIN ENTRY POINT — starts the full distributed load-balancing cluster
 * ═══════════════════════════════════════════════════════════════════
 *
 * Usage (run from the project root or via NetBeans Run menu):
 *
 *   java -cp dist/DistributedLoadBalancer.jar loadbalancer.NodeLauncher [numNodes]
 *
 * Examples:
 *   java ... loadbalancer.NodeLauncher         → starts 3 nodes (default)
 *   java ... loadbalancer.NodeLauncher 5       → starts 5 nodes
 *
 * To simulate a multi-machine deployment on a LAN:
 *   • Run one JVM per node, passing a single nodeIndex argument (0-based).
 *   • Edit BASE_PORT and HOST to match your network configuration.
 *   • Each machine only starts the node for its own index.
 *   (See "Multi-Machine Instructions" at the bottom of this file.)
 *
 * Ports used: BASE_PORT, BASE_PORT+1, BASE_PORT+2, ...
 *   Each node gets its own RMI Registry on a unique port so they can
 *   all run on the same machine without conflict.
 */
public class NodeLauncher {

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Change to the actual hostname/IP when running across machines */
    private static final String HOST      = "localhost";

    /** First port; each additional node increments this by 1 */
    private static final int    BASE_PORT = 1100;

    /** Default cluster size if no argument is given */
    private static final int    DEFAULT_NODES = 3;

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {

        int numNodes = DEFAULT_NODES;
        if (args.length > 0) {
            numNodes = Integer.parseInt(args[0]);
        }

        System.out.println("╔═══════════════════════════════════════════╗");
        System.out.println("║   Distributed Load Balancer  (Java RMI)   ║");
        System.out.println("╚═══════════════════════════════════════════╝");
        System.out.println("Starting " + numNodes + " node(s) on " + HOST + " ...");
        System.out.println();

        // ── Step 1: Start one RMI registry per node and bind the NodeImpl ────
        List<NodeImpl>      nodeImpls = new ArrayList<>();
        List<NodeInterface> nodeStubs = new ArrayList<>();
        List<String>        allAddrs  = new ArrayList<>();

        for (int i = 0; i < numNodes; i++) {
            int    port   = BASE_PORT + i;
            String nodeId = "Node-" + (i + 1);
            String addr   = "rmi://" + HOST + ":" + port + "/" + nodeId;

            // Start a dedicated RMI registry on this port
            Registry registry = LocateRegistry.createRegistry(port);

            // Create and export the node
            NodeImpl node = new NodeImpl(nodeId);
            Naming.rebind(addr, node);

            nodeImpls.add(node);
            allAddrs.add(addr);
            System.out.println("  ✔ Bound " + nodeId + " at " + addr);
        }

        // ── Step 2: Give every node the addresses of its peers ───────────────
        // Each node gets all addresses EXCEPT its own
        for (int i = 0; i < numNodes; i++) {
            List<String> peers = new ArrayList<>(allAddrs);
            peers.remove(allAddrs.get(i)); // remove self
            nodeImpls.get(i).registerPeers(peers);
        }

        // ── Step 3: Retrieve remote stubs for the monitor & task generator ────
        for (String addr : allAddrs) {
            nodeStubs.add((NodeInterface) Naming.lookup(addr));
        }

        // ── Step 4: Start the live dashboard ─────────────────────────────────
        Monitor monitor = new Monitor(nodeStubs);
        monitor.start();

        // ── Step 5: Start task generators (one per node, targeting all nodes) ─
        // Each generator aims at the full node list to create cross-node traffic
        List<TaskGenerator> generators = new ArrayList<>();
        for (int i = 0; i < numNodes; i++) {
            // Give each generator a mutable copy so it can remove dead nodes
            List<NodeInterface> targets = new ArrayList<>(nodeStubs);
            TaskGenerator gen = new TaskGenerator("Gen-" + (i + 1), targets);
            gen.start();
            generators.add(gen);
        }

        // ── Step 6: Keep the JVM alive; register a shutdown hook ─────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Launcher] Shutting down...");
            generators.forEach(TaskGenerator::stop);
            monitor.stop();
            nodeImpls.forEach(NodeImpl::shutdown);
        }));

        System.out.println();
        System.out.println("Cluster is running. Press Ctrl+C to stop.");
        System.out.println();

        // Block the main thread forever
        Thread.currentThread().join();
    }
}
