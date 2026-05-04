package loadbalancer;

import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;
import java.util.Arrays;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  SINGLE-NODE LAUNCHER — for deploying one node per machine on a LAN
 * ═══════════════════════════════════════════════════════════════════
 *
 * Usage:
 *   java -cp dist/DistributedLoadBalancer.jar loadbalancer.SingleNodeMain \
 *        <nodeId> <port> <peer1addr> [<peer2addr> ...]
 *
 * Example (Machine A — Node-1 at port 1100):
 *   java ... loadbalancer.SingleNodeMain Node-1 1100 \
 *        rmi://192.168.1.11:1101/Node-2 rmi://192.168.1.12:1102/Node-3
 *
 * Example (Machine B — Node-2 at port 1101):
 *   java ... loadbalancer.SingleNodeMain Node-2 1101 \
 *        rmi://192.168.1.10:1100/Node-1 rmi://192.168.1.12:1102/Node-3
 *
 * ─── IMPORTANT: Run on every machine separately ───────────────────
 * Start peers first, then give each node the addresses of the others.
 * The node will print its own RMI address so you can copy it into the
 * peer list of the other nodes.
 */
public class SingleNodeMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: SingleNodeMain <nodeId> <port> [peer1addr] [peer2addr] ...");
            System.exit(1);
        }

        String nodeId = args[0];
        int    port   = Integer.parseInt(args[1]);

        // Collect peer addresses from remaining arguments
        List<String> peers = Arrays.asList(Arrays.copyOfRange(args, 2, args.length));

        String selfAddr = "rmi://localhost:" + port + "/" + nodeId;

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║  Single-Node Launcher  (Java RMI)        ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println("Node ID  : " + nodeId);
        System.out.println("Port     : " + port);
        System.out.println("Address  : " + selfAddr);
        System.out.println("Peers    : " + (peers.isEmpty() ? "(none yet)" : peers));
        System.out.println();

        // Start the RMI registry on the specified port
        LocateRegistry.createRegistry(port);

        // Create and export the node
        NodeImpl node = new NodeImpl(nodeId);
        Naming.rebind(selfAddr, node);

        if (!peers.isEmpty()) {
            node.registerPeers(peers);
        }

        // Start a local task generator that submits tasks to itself
        // In production you would also target the peers once they are up
        NodeInterface selfStub = (NodeInterface) Naming.lookup(selfAddr);
        TaskGenerator gen = new TaskGenerator(nodeId, new java.util.ArrayList<>(List.of(selfStub)));
        gen.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            gen.stop();
            node.shutdown();
        }));

        System.out.println("Node running. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
