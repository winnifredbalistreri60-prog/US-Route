package app.usroute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Concurrently probes proxy profiles and selects the fastest successful US exit. */
public final class ProxySelector {
    private ProxySelector() { }

    public static final class Result {
        public final ProxyBridge.ProbeResult best;
        public final int completed;
        public final String report;

        private Result(ProxyBridge.ProbeResult best, int completed, String report) {
            this.best = best;
            this.completed = completed;
            this.report = report;
        }
    }

    public static Result scan(List<ProxyProfile> profiles, ExecutorService workers,
                              Function<ProxyProfile, ProxyBridge.ProbeResult> probe,
                              BooleanSupplier cancelled, long budgetMs) {
        if (profiles == null || workers == null || probe == null || cancelled == null)
            throw new IllegalArgumentException("Arguments must not be null");
        final int total = profiles.size();
        final List<Future<ProxyBridge.ProbeResult>> futures = new ArrayList<>();
        final List<String> successes = new ArrayList<>();
        ProxyBridge.ProbeResult best = null;
        int completed = 0;
        boolean interrupted = false;
        CompletionService<ProxyBridge.ProbeResult> completion = new ExecutorCompletionService<>(workers);
        long now = System.nanoTime();
        long budgetNanos = budgetMs <= 0 ? 0L : TimeUnit.MILLISECONDS.toNanos(budgetMs);
        long deadline = budgetNanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + budgetNanos;
        try {
            for (final ProxyProfile profile : profiles) {
                futures.add(completion.submit(() -> probe.apply(profile)));
            }
            int remaining = total;
            while (remaining > 0) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) break;
                long nanos = deadline - System.nanoTime();
                if (nanos <= 0) break;
                Future<ProxyBridge.ProbeResult> done;
                try {
                    done = completion.poll(Math.min(nanos, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                    break;
                }
                if (done == null) continue;
                remaining--;
                completed++;
                try {
                    ProxyBridge.ProbeResult result = done.get();
                    if (result != null && result.ok && "US".equals(result.country)) {
                        String name = result.profile == null ? "(unknown profile)" : result.profile.name;
                        successes.add(name + " — " + result.latencyMs + " мс");
                        if (best == null || result.latencyMs < best.latencyMs) best = result;
                    }
                } catch (ExecutionException | CancellationException ignored) {
                    // A failed task is still a completed task; continue collecting the others.
                } catch (InterruptedException e) {
                    interrupted = true;
                    break;
                }
            }
        } finally {
            for (Future<?> future : futures) if (!future.isDone()) future.cancel(true);
            if (interrupted) Thread.currentThread().interrupt();
        }
        StringBuilder report = new StringBuilder();
        report.append("Подтверждённые US-профили:");
        if (successes.isEmpty()) report.append(" нет");
        else for (String line : successes) report.append('\n').append(line);
        report.append("\nЗавершено ").append(completed).append('/').append(total).append(" проверок.");
        return new Result(best, completed, report.toString());
    }
}
