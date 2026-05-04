package loadbalancer;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * RMI Remote Interface for each node in the distributed load balancing system.
 * Every node exposes these methods so other nodes can communicate with it.
 */
public interface NodeInterface extends Remote {

    /**
     * Submit a task to this node for processing.
     * Returns the result of the computation.
     */
    String submitTask(Task task) throws RemoteException;

    /**
     * Returns the current load of this node (0.0 = idle, 1.0 = fully loaded).
     * Used by the load balancer to decide where to route tasks.
     */
    double getCurrentLoad() throws RemoteException;

    /**
     * Returns the unique identifier of this node (e.g., "Node-1").
     */
    String getNodeId() throws RemoteException;

    /**
     * Returns the number of tasks currently being processed.
     */
    int getActiveTaskCount() throws RemoteException;

    /**
     * Health check: returns true if the node is alive and accepting tasks.
     */
    boolean isAlive() throws RemoteException;

    /**
     * Returns a snapshot of this node's status for logging/monitoring.
     */
    NodeStatus getStatus() throws RemoteException;

    /**
     * Registers the addresses of peer nodes so this node can forward overflow tasks.
     */
    void registerPeers(List<String> peerAddresses) throws RemoteException;
}
