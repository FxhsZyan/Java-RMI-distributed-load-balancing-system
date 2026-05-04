package loadbalancer;

import java.util.Arrays;
import java.util.Random;

/**
 * Executes CPU-intensive workloads in worker threads.
 *
 * Every computation is deliberately heavy:
 *   PRIME_SEARCH    — sieve up to ~5 M     → ~50–200 ms on modern hardware
 *   MATRIX_MULTIPLY — N=350–500 matrices   → ~200–800 ms  (O(N³))
 *   SORT_ARRAY      — 5–15 million ints    → ~500–1500 ms
 *
 * Tasks are looped internally so each call burns at least MIN_BURN_MS of
 * wall-clock time, making CPU usage clearly visible in Task Manager / top.
 */
public class TaskProcessor {

    /** Minimum time each task should burn (milliseconds). */
    private static final long MIN_BURN_MS = 800;

    // ── Public dispatch ───────────────────────────────────────────────────────

    public static String process(Task task) {
        long start = System.currentTimeMillis();
        String result;

        switch (task.getType()) {
            case PRIME_SEARCH:      result = findPrimesLooped(task.getParameter(), start); break;
            case MATRIX_MULTIPLY:   result = matrixMultiplyLooped(task.getParameter(), start); break;
            case SORT_ARRAY:        result = sortArrayLooped(task.getParameter(), start); break;
            default:                result = "Unknown task type";
        }

        long elapsed = System.currentTimeMillis() - start;
        return String.format("[%s] %s — %d ms — %s",
                task.getTaskId(), task.getType(), elapsed, result);
    }

    // ── Prime Search (Sieve of Eratosthenes) ─────────────────────────────────

    private static String findPrimesLooped(int limit, long startMs) {
        int rounds = 0;
        int count  = 0;
        int largest = 2;

        do {
            boolean[] sieve = new boolean[limit + 1];
            Arrays.fill(sieve, true);
            sieve[0] = sieve[1] = false;
            for (int i = 2; (long)i * i <= limit; i++) {
                if (sieve[i]) {
                    for (int j = i * i; j <= limit; j += i) sieve[j] = false;
                }
            }
            count = 0; largest = 2;
            for (int i = 2; i <= limit; i++) {
                if (sieve[i]) { count++; largest = i; }
            }
            rounds++;
        } while (System.currentTimeMillis() - startMs < MIN_BURN_MS);

        return String.format("%d primes ≤ %,d (largest %,d) × %d rounds",
                count, limit, largest, rounds);
    }

    // ── Matrix Multiplication (O(N³)) ────────────────────────────────────────

    private static String matrixMultiplyLooped(int n, long startMs) {
        Random rng = new Random(42);
        int rounds = 0;
        double corner = 0;

        do {
            double[][] a = new double[n][n];
            double[][] b = new double[n][n];
            double[][] c = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    a[i][j] = rng.nextDouble() * 100;
                    b[i][j] = rng.nextDouble() * 100;
                }
            }
            // ikj loop order — cache-friendly
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < n; k++) {
                    for (int j = 0; j < n; j++) {
                        c[i][j] += a[i][k] * b[k][j];
                    }
                }
            }
            corner = c[0][0];
            rounds++;
        } while (System.currentTimeMillis() - startMs < MIN_BURN_MS);

        return String.format("%dx%d matrix, C[0][0]=%.1f × %d rounds", n, n, corner, rounds);
    }

    // ── Array Sort ────────────────────────────────────────────────────────────

    private static String sortArrayLooped(int size, long startMs) {
        Random rng = new Random(99);
        int rounds = 0;
        int min = 0, max = 0;

        do {
            int[] arr = new int[size];
            for (int i = 0; i < size; i++) arr[i] = rng.nextInt(Integer.MAX_VALUE);
            Arrays.sort(arr);
            min = arr[0];
            max = arr[arr.length - 1];
            rounds++;
        } while (System.currentTimeMillis() - startMs < MIN_BURN_MS);

        return String.format("sorted %,d ints, min=%,d max=%,d × %d rounds",
                size, min, max, rounds);
    }
}