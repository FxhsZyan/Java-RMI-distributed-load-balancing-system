package loadbalancer;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.*;

/**
 * ═══════════════════════════════════════════════════════════════
 *  TaskProcessor — deliberately heavy CPU workloads
 * ═══════════════════════════════════════════════════════════════
 *
 *  Every task loops until MIN_BURN_MS has elapsed so CPU usage
 *  is clearly visible in Task Manager / top even on fast hardware.
 *
 *  MATRIX_MULTIPLY  N=1200–1800  → several seconds per iteration
 *  PRIME_SEARCH     up to 50M    → 1–3 s per sieve pass
 *  SORT_ARRAY       10–20M ints  → 1–2 s per sort pass
 */
public class TaskProcessor {

    private static final long MIN_BURN_MS = ClusterConfig.MIN_BURN_MS;

    // ── Public dispatch ───────────────────────────────────────────────────────

    public static String process(Task task) {
        long start = System.currentTimeMillis();
        String detail;

        switch (task.getType()) {
            case MATRIX_MULTIPLY: detail = matrixLoop(task.getParameter(), start); break;
            case PRIME_SEARCH:    detail = primeLoop(task.getParameter(), start);  break;
            case SORT_ARRAY:      detail = sortLoop(task.getParameter(), start);   break;
            default:              detail = "unknown type";
        }

        long ms = System.currentTimeMillis() - start;
        return String.format("[%s] %s %d ms — %s",
                task.getTaskId(), task.getType(), ms, detail);
    }

    // ── Matrix multiply (O(N³)) ───────────────────────────────────────────────

    private static String matrixLoop(int n, long t0) {
        Random rng = new Random(7);
        int rounds = 0;
        double corner = 0;
        do {
            double[][] a = new double[n][n];
            double[][] b = new double[n][n];
            double[][] c = new double[n][n];
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++) {
                    a[i][j] = rng.nextDouble() * 100;
                    b[i][j] = rng.nextDouble() * 100;
                }
            // ikj order — cache-friendly
            for (int i = 0; i < n; i++)
                for (int k = 0; k < n; k++)
                    for (int j = 0; j < n; j++)
                        c[i][j] += a[i][k] * b[k][j];
            corner = c[0][0];
            rounds++;
        } while (elapsed(t0) < MIN_BURN_MS);
        return String.format("%dx%d matrix ×%d rounds, C[0][0]=%.1f", n, n, rounds, corner);
    }

    // ── Prime sieve ───────────────────────────────────────────────────────────

    private static String primeLoop(int limit, long t0) {
        int rounds = 0, count = 0, largest = 2;
        do {
            boolean[] sieve = new boolean[limit + 1];
            Arrays.fill(sieve, true);
            sieve[0] = sieve[1] = false;
            for (int i = 2; (long)i * i <= limit; i++)
                if (sieve[i])
                    for (int j = i * i; j <= limit; j += i)
                        sieve[j] = false;
            count = 0; largest = 2;
            for (int i = 2; i <= limit; i++)
                if (sieve[i]) { count++; largest = i; }
            rounds++;
        } while (elapsed(t0) < MIN_BURN_MS);
        return String.format("%,d primes ≤ %,d (largest %,d) ×%d rounds",
                count, limit, largest, rounds);
    }

    // ── Large array sort ──────────────────────────────────────────────────────

    private static String sortLoop(int size, long t0) {
        Random rng = new Random(13);
        int rounds = 0, min = 0, max = 0;
        do {
            int[] arr = new int[size];
            for (int i = 0; i < size; i++) arr[i] = rng.nextInt(Integer.MAX_VALUE);
            Arrays.sort(arr);
            min = arr[0]; max = arr[arr.length - 1];
            rounds++;
        } while (elapsed(t0) < MIN_BURN_MS);
        return String.format("sorted %,d ints [%,d..%,d] ×%d rounds",
                size, min, max, rounds);
    }

    private static long elapsed(long t0) { return System.currentTimeMillis() - t0; }
}
