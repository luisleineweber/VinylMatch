package Server.http;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded bulkhead for synchronous third-party provider calls. */
public final class ProviderExecutor {

    private static final int THREADS = envInt("PROVIDER_THREADS", 8);
    private static final int QUEUE_CAPACITY = envInt("PROVIDER_QUEUE_CAPACITY", 32);
    private static final Duration TIMEOUT = Duration.ofSeconds(envInt("PROVIDER_TIMEOUT_SECONDS", 20));
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
        THREADS, THREADS, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY), daemonFactory(), new ThreadPoolExecutor.AbortPolicy()
    );

    private ProviderExecutor() {}

    public static <T> T call(Callable<T> operation) throws Exception {
        final Future<T> future;
        try {
            future = EXECUTOR.submit(operation);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new ProviderUnavailableException("provider_queue_full", e);
        }
        try {
            return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ProviderUnavailableException("provider_timeout", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    public static Map<String, Integer> status() {
        return Map.of(
            "active", EXECUTOR.getActiveCount(),
            "queued", EXECUTOR.getQueue().size(),
            "threads", THREADS,
            "queueCapacity", QUEUE_CAPACITY
        );
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "provider-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static int envInt(String name, int fallback) {
        try {
            String raw = System.getenv(name);
            return raw == null || raw.isBlank() ? fallback : Math.max(1, Integer.parseInt(raw));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static final class ProviderUnavailableException extends Exception {
        public ProviderUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
