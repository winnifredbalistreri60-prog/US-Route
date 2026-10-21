package app.usroute;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class ProxySelectorTest {
    private static ProxyProfile profile(String id) {
        return new ProxyProfile(id, id, "http", "127.0.0.1", 8080, null, null);
    }

    private static ProxyBridge.ProbeResult result(ProxyProfile p, boolean ok, long latency, String country) {
        return new ProxyBridge.ProbeResult(p, ok, latency, null, country, null);
    }

    @Test public void slowerCompletedFirstDoesNotWinOverLaterLowerLatency() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            ProxyProfile first = profile("first"), later = profile("later");
            ProxySelector.Result scan = ProxySelector.scan(Arrays.asList(first, later), workers, p -> {
                if (p == first) {
                    try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return result(p, true, 100, "US");
                }
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return result(p, true, 10, "US");
            }, () -> false, 2_000);
            assertSame(later, scan.best.profile);
            assertEquals(2, scan.completed);
            assertTrue(scan.report.contains("first — 100 мс"));
            assertTrue(scan.report.contains("later — 10 мс"));
            assertTrue(scan.report.contains("Завершено 2/2"));
        } finally { workers.shutdownNow(); }
    }

    @Test public void nonUsAndFailedResultsCannotWin() {
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            ProxyProfile failed = profile("failed"), nonUs = profile("nonus"), us = profile("us");
            ProxySelector.Result scan = ProxySelector.scan(Arrays.asList(failed, nonUs, us), workers, p -> {
                if (p == failed) throw new IllegalStateException("probe failed");
                if (p == nonUs) return result(p, true, 1, "CA");
                return result(p, true, 20, "US");
            }, () -> false, 2_000);
            assertSame(us, scan.best.profile);
            assertEquals(3, scan.completed);
            assertTrue(scan.report.contains("us — 20 мс"));
            assertFalse(scan.report.contains("nonus"));
        } finally { workers.shutdownNow(); }
    }

    @Test public void commonDeadlineDoesNotWaitForHangingTask() {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(2);
        try {
            ProxyProfile one = profile("one"), two = profile("two");
            long start = System.nanoTime();
            ProxySelector.Result scan = ProxySelector.scan(Arrays.asList(one, two), workers, p -> {
                started.countDown();
                try { new CountDownLatch(1).await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return result(p, true, 1, "US");
            }, () -> false, 150);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(0, scan.completed);
            assertNull(scan.best);
            assertTrue("elapsed=" + elapsedMs, elapsedMs < 1_000);
            assertEquals(0, started.getCount());
        } finally { workers.shutdownNow(); }
    }

    @Test public void cancellationStopsCollectionAndCancelsOutstandingTasks() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(1);
        AtomicBoolean cancel = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        try {
            ProxyProfile p = profile("blocked");
            Thread setter = new Thread(() -> {
                try { started.await(); Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                cancel.set(true);
            });
            setter.start();
            ProxySelector.Result scan = ProxySelector.scan(Collections.singletonList(p), workers, proxy -> {
                started.countDown();
                try { new CountDownLatch(1).await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return result(proxy, true, 1, "US");
            }, cancel::get, 5_000);
            setter.join(1_000);
            assertEquals(0, scan.completed);
            assertNull(scan.best);
            assertTrue(scan.report.contains("Завершено 0/1"));
        } finally { workers.shutdownNow(); }
    }
}
