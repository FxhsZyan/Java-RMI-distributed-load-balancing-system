package loadbalancer;

import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  SINGLE-NODE LAUNCHER — one node per physical machine on a LAN
 * ═══════════════════════════════════════════════════════════════════
 *
 * Usage:
 *   java -cp DistributedLoadBalancer.jar loadbalancer.SingleNodeMain \
 *        <nodeId> <port> [peerAddr1] [peerAddr2] …
 *
 * Machine A (192.168.1.10):
 *   java … SingleNodeMain Node-1 1100 \
 *       rmi://192.168.1.11:1101/Node-2 rmi://192.168.1.12:1102/Node-3
 *
 * Machine B (192.168.1.11):
 *   java … SingleNodeMain Node-2 1101 \
 *       rmi://192.168.1.10:1100/Node-1 rmi://192.168.1.12:1102/Node-3
 *
 * IMPORTANT: add -Djava.rmi.server.hostname=<this-machine-IP> so RMI
 * advertises the correct address to other machines.
 */
public class SingleNodeMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: SingleNodeMain <nodeId> <port> [peerAddr…]");
            System.exit(1);
        }

        String       nodeId = args[0];
        int          port   = Integer.parseInt(args[1]);
        List<String> peers  = new ArrayList<>(
                Arrays.asList(Arrays.copyOfRange(args, 2, args.length)));

        String selfAddr = "rmi://localhost:" + port + "/" + nodeId;

        System.out.printf("Starting %s on port %d  peers=%s%n", nodeId, port, peers);

        LocateRegistry.createRegistry(port);
        NodeImpl node = new NodeImpl(nodeId);
        Naming.rebind(selfAddr, node);

        if (!peers.isEmpty()) node.registerPeers(peers);

        NodeInterface selfStub = (NodeInterface) Naming.lookup(selfAddr);
        TaskGenerator gen = new TaskGenerator(nodeId, new ArrayList<>(List.of(selfStub)));
        gen.start();

        Monitor monitor = new Monitor(new ArrayList<>(List.of(selfStub)));
        monitor.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            gen.stop();
            monitor.stop();
            node.shutdown();
            try { Naming.unbind(selfAddr); } catch (Exception ignored) {}
        }));

        System.out.println("Running. Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}