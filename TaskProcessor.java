package loadbalancer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Executes CPU-intensive tasks in dedicated threads.
 * Each of the three task types genuinely stresses the CPU so the load
 * simulation is realistic rather than just Thread.sleep().
 */
public class TaskProcessor {

    /**
     * Dispatch the task to the appropriate computation method.
     * Called inside a thread-pool worker so it is safe to block here.
     */
    public static String process(Task task) {
        long start = System.currentTimeMillis();
        String result;

        switch (task.getType()) {
            case PRIME_SEARCH:
                result = findPrimes(task.getParameter());
                break;
            case MATRIX_MULTIPLY:
                result = matrixMultiply(task.getParameter());
                break;
            case SORT_ARRAY:
                result = sortArray(task.getParameter());
                break;
            default:
                result = "Unknown task type";
        }

        long elapsed = System.currentTimeMillis() - start;
        return String.format("[%s] %s completed in %d ms — %s",
                task.getTaskId(), task.getType(), elapsed, result);
    }

    // ── Prime Number Search (Sieve of Eratosthenes) ──────────────────────────

    /**
     * Finds all prime numbers up to 'limit' using a sieve.
     * For large limits (>500 000) this takes a noticeable amount of CPU time.
     */
    private static String findPrimes(int limit) {
        boolean[] sieve = new boolean[limit + 1];
        Arrays.fill(sieve, true);
        sieve[0] = sieve[1] = false;

        for (int i = 2; (long) i * i <= limit; i++) {
            if (sieve[i]) {
                for (int j = i * i; j <= limit; j += i) {
                    sieve[j] = false;
                }
            }
        }

        int count = 0;
        int largest = 2;
        for (int i = 2; i <= limit; i++) {
            if (sieve[i]) { count++; largest = i; }
        }
        return String.format("%d primes up to %d (largest: %d)", count, limit, largest);
    }

    // ── Matrix Multiplication ─────────────────────────────────────────────────

    /**
     * Multiplies two randomly-initialized N×N matrices.
     * Complexity is O(N³), so even N=200 creates a real workload.
     */
    private static String matrixMultiply(int n) {
        Random rng = new Random(42);
        double[][] a = new double[n][n];
        double[][] b = new double[n][n];
        double[][] c = new double[n][n];

        // Initialize with random values
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                a[i][j] = rng.nextDouble() * 100;
                b[i][j] = rng.nextDouble() * 100;
            }
        }

        // Standard triple-loop multiply
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < n; k++) {
                for (int j = 0; j < n; j++) {
                    c[i][j] += a[i][k] * b[k][j];
                }
            }
        }

        // Use a corner element so the JIT won't eliminate dead code
        return String.format("%dx%d matrix multiplied, C[0][0]=%.2f", n, n, c[0][0]);
    }

    // ── Large Array Sort ──────────────────────────────────────────────────────

    /**
     * Generates and sorts an array of 'size' random integers.
     * Uses Arrays.sort (dual-pivot quicksort) on sizes up to several million.
     */
    private static String sortArray(int size) {
        Random rng = new Random(99);
        int[] arr = new int[size];
        for (int i = 0; i < size; i++) {
            arr[i] = rng.nextInt(Integer.MAX_VALUE);
        }
        Arrays.sort(arr);
        return String.format("Sorted %d elements, min=%d max=%d",
                size, arr[0], arr[arr.length - 1]);
    }
}
