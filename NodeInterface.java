package loadbalancer;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * RMI Remote Interface — every node exposes these methods over the network.
 */
public interface NodeInterface extends Remote {

    /** Submit a task. Blocks until the task completes and returns a result string. */
    String submitTask(Task task) throws RemoteException;

    /** Returns load as 0.0 (idle) – 1.0 (all threads saturated). */
    double getCurrentLoad() throws RemoteException;

    /** Human-readable node name, e.g. "Node-1". */
    String getNodeId() throws RemoteException;

    /** Number of tasks currently running. */
    int getActiveTaskCount() throws RemoteException;

    /** Ping — returns true if the node is alive and responsive. */
    boolean isAlive() throws RemoteException;

    /** Full status snapshot, serialised and sent over RMI. */
    NodeStatus getStatus() throws RemoteException;
}
