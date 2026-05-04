package loadbalancer;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * RMI Remote Interface — the network contract every node must fulfil.
 * Any method declared here can be called from another JVM over the network.
 */
public interface NodeInterface extends Remote {

    /** Submit a task for execution. Blocks until the result is ready. */
    String submitTask(Task task) throws RemoteException;

    /** 0.0 = idle, 1.0 = all threads saturated. */
    double getCurrentLoad() throws RemoteException;

    /** Human-readable node name, e.g. "Node-1". */
    String getNodeId() throws RemoteException;

    /** Number of tasks currently running in the thread pool. */
    int getActiveTaskCount() throws RemoteException;

    /** Health-check — returns true if the node is alive. */
    boolean isAlive() throws RemoteException;

    /** Full status snapshot (serialised and sent over RMI). */
    NodeStatus getStatus() throws RemoteException;

    /** Tell this node about its peers so it can forward overflow tasks. */
    void registerPeers(List<String> peerAddresses) throws RemoteException;
}