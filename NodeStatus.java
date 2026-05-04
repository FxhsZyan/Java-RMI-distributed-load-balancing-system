package loadbalancer;

import java.io.Serializable;

/**
 * Lightweight, serialisable snapshot of a node's runtime state.
 * Transmitted over RMI so the monitor can display live cluster health.
 */
public class NodeStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String nodeId;
    private final double load;           // 0.0 – 1.0
    private final int    activeTasks;
    private final int    completedTasks;
    private final int    forwardedTasks; // tasks this node offloaded to peers
    private final int    receivedTasks;  // tasks forwarded TO this node
    private final int    maxThreads;
    private final long   timestamp;

    public NodeStatus(String nodeId, double load, int activeTasks,
                      int completedTasks, int forwardedTasks,
                      int receivedTasks, int maxThreads) {
        this.nodeId         = nodeId;
        this.load           = load;
        this.activeTasks    = activeTasks;
        this.completedTasks = completedTasks;
        this.forwardedTasks = forwardedTasks;
        this.receivedTasks  = receivedTasks;
        this.maxThreads     = maxThreads;
        this.timestamp      = System.currentTimeMillis();
    }

    public String getNodeId()         { return nodeId; }
    public double getLoad()           { return load; }
    public int    getActiveTasks()    { return activeTasks; }
    public int    getCompletedTasks() { return completedTasks; }
    public int    getForwardedTasks() { return forwardedTasks; }
    public int    getReceivedTasks()  { return receivedTasks; }
    public int    getMaxThreads()     { return maxThreads; }
    public long   getTimestamp()      { return timestamp; }

    /** One-line summary used in the dashboard. */
    @Override
    public String toString() {
        return String.format("%-8s | load=%3.0f%% [%d/%d threads] | done=%4d | fwd-out=%3d | fwd-in=%3d",
                nodeId, load * 100,
                activeTasks, maxThreads,
                completedTasks, forwardedTasks, receivedTasks);
    }
}