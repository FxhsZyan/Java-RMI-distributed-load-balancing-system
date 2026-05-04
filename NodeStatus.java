package loadbalancer;

import java.io.Serializable;

/**
 * A lightweight, serializable snapshot of a node's current state.
 * Sent over RMI so peers and monitors can display live load information.
 */
public class NodeStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String nodeId;
    private final double load;            // 0.0 – 1.0
    private final int    activeTasks;
    private final int    completedTasks;
    private final int    forwardedTasks;  // tasks this node offloaded to peers
    private final long   timestamp;

    public NodeStatus(String nodeId, double load, int activeTasks,
                      int completedTasks, int forwardedTasks) {
        this.nodeId         = nodeId;
        this.load           = load;
        this.activeTasks    = activeTasks;
        this.completedTasks = completedTasks;
        this.forwardedTasks = forwardedTasks;
        this.timestamp      = System.currentTimeMillis();
    }

    public String getNodeId()        { return nodeId; }
    public double getLoad()          { return load; }
    public int    getActiveTasks()   { return activeTasks; }
    public int    getCompletedTasks(){ return completedTasks; }
    public int    getForwardedTasks(){ return forwardedTasks; }
    public long   getTimestamp()     { return timestamp; }

    @Override
    public String toString() {
        return String.format("%-8s | load=%.0f%% | active=%2d | done=%3d | fwd=%2d",
                nodeId,
                load * 100,
                activeTasks,
                completedTasks,
                forwardedTasks);
    }
}
