package loadbalancer;

import java.io.Serializable;
import java.util.UUID;

/**
 * A unit of work passed between nodes over RMI.
 * Must be Serializable so Java can marshal it across the network.
 */
public class Task implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum TaskType {
        MATRIX_MULTIPLY,    // N×N matrix multiply — O(N³), N = 1200–1800
        PRIME_SEARCH,       // Sieve of Eratosthenes up to 10–50 million
        SORT_ARRAY          // Sort 10–20 million random integers
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
        return String.format("Task[%s|%s|%,d|%s]",
                taskId, type, parameter, originNodeId);
    }
}
