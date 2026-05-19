package loadbalancer;

import java.io.Serializable;

/**
 * Serialisable snapshot of a node's runtime state.
 * Transferred over RMI so the GUI and monitor can show live data.
 */
public class NodeStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String nodeId;
    private final double load;
    private final int    activeTasks;
    private final int    completedTasks;
    private final int    forwardedTasks;
    private final int    receivedTasks;
    private final int    maxThreads;
    private final boolean online;
    private final long   timestamp;

    public NodeStatus(String nodeId, double load, int activeTasks,
                      int completedTasks, int forwardedTasks,
                      int receivedTasks, int maxThreads, boolean online) {
        this.nodeId         = nodeId;
        this.load           = load;
        this.activeTasks    = activeTasks;
        this.completedTasks = completedTasks;
        this.forwardedTasks = forwardedTasks;
        this.receivedTasks  = receivedTasks;
        this.maxThreads     = maxThreads;
        this.online         = online;
        this.timestamp      = System.currentTimeMillis();
    }

    /** Offline placeholder used when a node cannot be reached. */
    public static NodeStatus offline(String nodeId) {
        return new NodeStatus(nodeId, 0, 0, 0, 0, 0, 0, false);
    }

    public String  getNodeId()         { return nodeId; }
    public double  getLoad()           { return load; }
    public int     getActiveTasks()    { return activeTasks; }
    public int     getCompletedTasks() { return completedTasks; }
    public int     getForwardedTasks() { return forwardedTasks; }
    public int     getReceivedTasks()  { return receivedTasks; }
    public int     getMaxThreads()     { return maxThreads; }
    public boolean isOnline()          { return online; }
    public long    getTimestamp()      { return timestamp; }

    @Override
    public String toString() {
        if (!online) return nodeId + " [OFFLINE]";
        return String.format("%s load=%.0f%% [%d/%d] done=%d fwd=%d rcv=%d",
                nodeId, load * 100, activeTasks, maxThreads,
                completedTasks, forwardedTasks, receivedTasks);
    }
}
