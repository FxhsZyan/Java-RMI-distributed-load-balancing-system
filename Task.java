package loadbalancer;

import java.io.Serializable;
import java.util.UUID;

/**
 * A unit of work sent across the network via RMI.
 * Must be Serializable so Java can marshal it over the wire.
 */
public class Task implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum TaskType {
        PRIME_SEARCH,      // Sieve of Eratosthenes up to N
        MATRIX_MULTIPLY,   // Two N×N matrices — O(N³)
        SORT_ARRAY         // Dual-pivot quicksort on N random integers
    }

    private final String   taskId;
    private final TaskType type;
    private final int      parameter;
    private final String   originNodeId;
    private final long     createdAt;

    public Task(TaskType type, int parameter, String originNodeId) {
        this.taskId       = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.type         = type;
        this.parameter    = parameter;
        this.originNodeId = originNodeId;
        this.createdAt    = System.currentTimeMillis();
    }

    public String   getTaskId()       { return taskId; }
    public TaskType getType()         { return type; }
    public int      getParameter()    { return parameter; }
    public String   getOriginNodeId() { return originNodeId; }
    public long     getCreatedAt()    { return createdAt; }

    @Override
    public String toString() {
        return String.format("Task[%s|%s|param=%,d|from=%s]",
                taskId, type, parameter, originNodeId);
    }
}