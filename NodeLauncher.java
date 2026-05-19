package loadbalancer;

import java.net.*;
import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;
import java.util.*;
import javax.swing.*;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  NodeLauncher — single main class run on every machine
 * ═══════════════════════════════════════════════════════════════════
 *
 *  HOW TO RUN
 *  ──────────
 *  On EVERY machine (no arguments needed):
 *
 *    java -Djava.rmi.server.hostname=<THIS_MACHINE_IP> \
 *         -jar DistributedLoadBalancer.jar
 *
 *  Replace <THIS_MACHINE_IP> with the machine's actual IP:
 *    Node-1 machine (10.16.45.42):  -Djava.rmi.server.hostname=10.16.45.42
 *    Node-2 machine (10.16.45.43):  -Djava.rmi.server.hostname=10.16.45.43
 *    Node-3 machine (10.16.45.44):  -Djava.rmi.server.hostname=10.16.45.44
 *
 *  The launcher auto-detects which node it is by matching its IP
 *  against the hardcoded IPs in ClusterConfig — no other config needed.
 *
 *  FOR LOCAL TESTING (all nodes on one machine):
 *    Open 3 terminals and run with explicit IP overrides:
 *      Terminal 1: java -Djava.rmi.server.hostname=10.16.45.42 -jar ...
 *      (You must have the real IPs or use loopback aliases — see README)
 *
 *  NETBEANS:
 *    Project Properties → Run → VM Options:
 *      -Djava.rmi.server.hostname=10.16.45.42
 */
public class NodeLauncher {

    public static void main(String[] args) throws Exception {

        // ── 1. Determine this machine's identity ─────────────────────────────
        String selfIp = detectSelfIp();
        System.out.println("[Launcher] Detected IP: " + selfIp);

        String nodeId = ClusterConfig.nodeNameForIp(selfIp);

        if (nodeId == null) {
            // Fallback: allow explicit override via system property or arg
            if (args.length > 0) {
                nodeId = args[0]; // e.g.  java ... NodeLauncher Node-1
            } else {
                showErrorAndExit("This machine's IP (" + selfIp + ") is not in ClusterConfig.\n"
                        + "Expected: " + ClusterConfig.IP_NODE1
                        + ", " + ClusterConfig.IP_NODE2
                        + ", or " + ClusterConfig.IP_NODE3
                        + "\n\nSet -Djava.rmi.server.hostname=<IP> and retry.");
                return;
            }
        }

        System.out.println("[Launcher] Starting as: " + nodeId + " (" + selfIp + ")");

        // Instruct RMI to advertise the correct IP (critical for LAN use)
        System.setProperty("java.rmi.server.hostname", selfIp);

        final String selfAddr = ClusterConfig.addrForIp(selfIp) != null
                ? ClusterConfig.addrForIp(selfIp)
                : "rmi://" + selfIp + ":" + ClusterConfig.RMI_PORT + "/" + nodeId;

        // ── 2. Start RMI registry and bind this node ─────────────────────────
        try {
            LocateRegistry.createRegistry(ClusterConfig.RMI_PORT);
        } catch (java.rmi.server.ExportException e) {
            System.out.println("[Launcher] Registry already running on port "
                    + ClusterConfig.RMI_PORT + " — reusing.");
            LocateRegistry.getRegistry(selfIp, ClusterConfig.RMI_PORT);
        }

        NodeImpl node = new NodeImpl(nodeId);
        Naming.rebind(selfAddr, node);
        System.out.println("[Launcher] Bound at: " + selfAddr);

        // ── 3. Launch Swing GUI (must be on EDT) ─────────────────────────────
        final String finalSelfIp = selfIp;
        SwingUtilities.invokeLater(() -> new NodeGUI(node, finalSelfIp));

        // ── 4. Build peer stub list (peers are looked up lazily inside NodeImpl)
        //       For the task generator we do a best-effort immediate lookup.
        List<NodeInterface> peerStubs = new ArrayList<>();
        peerStubs.add(node); // always target self too
        for (String addr : ClusterConfig.allAddresses()) {
            if (addr.equals(selfAddr)) continue;
            try {
                NodeInterface peer = (NodeInterface) Naming.lookup(addr);
                peer.isAlive();
                peerStubs.add(peer);
                System.out.println("[Launcher] Connected to peer: " + peer.getNodeId());
            } catch (Exception e) {
                System.out.println("[Launcher] Peer not yet available: " + addr
                        + " (will retry in background)");
            }
        }

        // ── 5. Start task generator ───────────────────────────────────────────
        TaskGenerator gen = new TaskGenerator(nodeId, peerStubs, node);
        gen.start();

        // ── 6. Background peer re-connection loop ─────────────────────────────
        // Keeps trying to add peers that were offline at startup
        final List<NodeInterface> stubs = peerStubs;
        Thread reconnect = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(8_000); } catch (InterruptedException e) { break; }
                for (String addr : ClusterConfig.allAddresses()) {
                    if (addr.equals(selfAddr)) continue;
                    boolean alreadyHave = false;
                    synchronized (stubs) {
                        for (NodeInterface s : stubs) {
                            try {
                                if (s.getNodeId().equals(addrToName(addr))) {
                                    alreadyHave = true; break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    if (!alreadyHave) {
                        try {
                            NodeInterface peer = (NodeInterface) Naming.lookup(addr);
                            peer.isAlive();
                            synchronized (stubs) { stubs.add(peer); }
                            System.out.println("[Reconnect] Added peer: " + addr);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }, "Reconnect");
        reconnect.setDaemon(true);
        reconnect.start();

        // ── 7. Shutdown hook ──────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Launcher] Shutting down…");
            gen.stop();
            node.shutdown();
            try { Naming.unbind(selfAddr); }
            catch (Exception ignored) {}
            System.out.println("[Launcher] Done.");
        }));

        System.out.println("[Launcher] " + nodeId + " is running. Close the window to stop.");
    }

    // ── IP detection ──────────────────────────────────────────────────────────

    /**
     * Returns the first non-loopback IPv4 address that matches one of the
     * cluster IPs, or falls back to the first site-local address found.
     */
    private static String detectSelfIp() {
        // Check java.rmi.server.hostname first (set via -D flag)
        String prop = System.getProperty("java.rmi.server.hostname");
        if (prop != null && !prop.isBlank()) return prop.trim();

        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            List<String> candidates = new ArrayList<>();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        // Prefer known cluster IPs
                        if (ip.equals(ClusterConfig.IP_NODE1) ||
                            ip.equals(ClusterConfig.IP_NODE2) ||
                            ip.equals(ClusterConfig.IP_NODE3)) return ip;
                        candidates.add(ip);
                    }
                }
            }
            if (!candidates.isEmpty()) return candidates.get(0);
        } catch (SocketException ignored) {}

        return "127.0.0.1";
    }

    private static String addrToName(String addr) {
        String[] names = ClusterConfig.allNames();
        String[] addrs = ClusterConfig.allAddresses();
        for (int i = 0; i < addrs.length; i++)
            if (addrs[i].equals(addr)) return names[i];
        return addr;
    }

    private static void showErrorAndExit(String msg) {
        System.err.println("[ERROR] " + msg);
        JOptionPane.showMessageDialog(null, msg, "Configuration Error",
                JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }
}
