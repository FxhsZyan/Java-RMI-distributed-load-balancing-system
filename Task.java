package loadbalancer;

import java.io.Serializable;
import java.util.UUID;

/**
 * Represents a unit of work that can be sent across the network via RMI.
 * Must implement Serializable so Java can marshal/unmarshal it over the wire.
 */
public class Task implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Unique ID for tracking this task end-to-end */
    private final String taskId;

    /** What kind of computation to run */
    private final TaskType type;

    /** Parameter for the computation (e.g., upper bound for prime search, matrix size) */
    private final int parameter;

    /** Which node originally created this task */
    private final String originNodeId;

    /** Timestamp when the task was created */
    private final long createdAt;

    public enum TaskType {
        PRIME_SEARCH,       // Find all primes up to parameter
        MATRIX_MULTIPLY,    // Multiply two NxN matrices (N = parameter)
        SORT_ARRAY          // Sort a random array of 'parameter' elements
    }

    public Task(TaskType type, int parameter, String originNodeId) {
        this.taskId     = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.type       = type;
        this.parameter  = parameter;
        this.originNodeId = originNodeId;
        this.createdAt  = System.currentTimeMillis();
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String  getTaskId()      { return taskId; }
    public TaskType getType()       { return type; }
    public int     getParameter()   { return parameter; }
    public String  getOriginNodeId(){ return originNodeId; }
    public long    getCreatedAt()   { return createdAt; }

    @Override
    public String toString() {
        return String.format("Task[%s | %s | param=%d | from=%s]",
                taskId, type, parameter, originNodeId);
    }
}
