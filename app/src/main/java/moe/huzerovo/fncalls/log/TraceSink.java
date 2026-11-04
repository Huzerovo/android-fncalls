package moe.huzerovo.fncalls.log;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import moe.huzerovo.fncalls.BuildConfig;
import moe.huzerovo.fncalls.config.Config;
import moe.huzerovo.fncalls.hook.HookLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Batches trace lines and forwards them to {@link TraceReceiver} in the module
 * process. Runs on a daemon thread so hooked app threads never wait for disk IO.
 */
public final class TraceSink {

    private static final String TAG = "FnCalls";
    private static final int FLUSH_THRESHOLD = 16;
    private static final int MAX_BATCH = 64;
    private static final int MAX_QUEUE = 16384;
    private static final long FLUSH_INTERVAL_MS = 300L;

    private static final TraceSink INSTANCE = new TraceSink();
    private static final ThreadLocal<Boolean> INTERNAL_THREAD = ThreadLocal.withInitial(() -> false);

    public static boolean isInternalThread() {
        return Boolean.TRUE.equals(INTERNAL_THREAD.get());
    }

    private static boolean enterInternal() {
        boolean previous = Boolean.TRUE.equals(INTERNAL_THREAD.get());
        INTERNAL_THREAD.set(true);
        return previous;
    }

    private static void leaveInternal(boolean previous) {
        INTERNAL_THREAD.set(previous);
    }

    private final Object lock = new Object();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "FnCalls-log");
            thread.setDaemon(true);
            return thread;
        }
    });

    private volatile Context context;
    private volatile String packageName = "unknown";
    private volatile String processName = "unknown";
    private volatile boolean logXposed;
    private volatile int maxLogMb = Config.DEFAULT_MAX_LOG_MB;
    private volatile int dropped;
    private volatile int deliveryFailures;

    private TraceSink() {
    }

    public static TraceSink get() {
        return INSTANCE;
    }

    public void init(String packageName, String processName, Config config) {
        this.packageName = packageName;
        this.processName = processName;
        this.logXposed = config.logXposed;
        this.maxLogMb = config.maxLogMb;
    }

    public void setContext(Context context) {
        if (context == null) {
            return;
        }
        this.context = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        scheduleFlush(true);
    }

    public void trace(String line) {
        if (line == null) {
            return;
        }
        boolean previous = enterInternal();
        try {
            if (logXposed) {
                HookLog.info(line);
            }

            boolean immediate;
            synchronized (lock) {
                if (queue.size() >= MAX_QUEUE) {
                    queue.removeFirst();
                    dropped++;
                }
                queue.addLast(line);
                immediate = queue.size() >= FLUSH_THRESHOLD;
            }
            scheduleFlush(immediate);
        } finally {
            leaveInternal(previous);
        }
    }

    /**
     * Writes a line that must reach the framework log even when per-call logging
     * is disabled. Used for banners and diagnostics.
     */
    public static void frameworkLog(String line) {
        boolean previous = enterInternal();
        try {
            HookLog.info(line);
        } finally {
            leaveInternal(previous);
        }
    }

    public void flushNow() {
        flushBatch();
    }

    private void scheduleFlush(boolean immediate) {
        boolean previous = enterInternal();
        try {
            if (!flushScheduled.compareAndSet(false, true)) {
                return;
            }
            try {
                executor.schedule(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            flushBatch();
                        } finally {
                            flushScheduled.set(false);
                            boolean again;
                            synchronized (lock) {
                                again = !queue.isEmpty();
                            }
                            if (again && context != null) {
                                scheduleFlush(false);
                            }
                        }
                    }
                }, immediate ? 0L : FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                flushScheduled.set(false);
            }
        } finally {
            leaveInternal(previous);
        }
    }

    private void flushBatch() {
        boolean previous = enterInternal();
        try {
            flushBatchInternal();
        } finally {
            leaveInternal(previous);
        }
    }

    private void flushBatchInternal() {
        Context localContext = context;
        if (localContext == null) {
            // The Application has not been created yet; keep buffering.
            return;
        }

        List<String> batch;
        int droppedSnapshot;
        synchronized (lock) {
            if (queue.isEmpty()) {
                return;
            }
            int count = Math.min(queue.size(), MAX_BATCH);
            batch = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                batch.add(queue.removeFirst());
            }
            droppedSnapshot = dropped;
            dropped = 0;
        }

        if (droppedSnapshot > 0) {
            batch.add(0, "[FnCalls] 日志队列溢出，丢弃了 " + droppedSnapshot + " 行");
        }

        Intent intent = new Intent(TraceReceiver.ACTION_APPEND);
        intent.setComponent(new ComponentName(
                BuildConfig.APPLICATION_ID, TraceReceiver.class.getName()));
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(TraceReceiver.EXTRA_PACKAGE, packageName);
        intent.putExtra(TraceReceiver.EXTRA_MAX_MB, maxLogMb);
        intent.putStringArrayListExtra(TraceReceiver.EXTRA_LINES, new ArrayList<>(batch));

        try {
            localContext.sendBroadcast(intent);
            deliveryFailures = 0;
        } catch (Throwable t) {
            onDeliveryFailure(t.toString());
            requeue(batch);
        }
    }

    private void requeue(List<String> batch) {
        if (deliveryFailures >= 5) {
            return;
        }
        synchronized (lock) {
            for (int i = batch.size() - 1; i >= 0; i--) {
                if (queue.size() >= MAX_QUEUE) {
                    queue.removeLast();
                    dropped++;
                }
                queue.addFirst(batch.get(i));
            }
        }
    }

    private void onDeliveryFailure(String reason) {
        int failures = ++deliveryFailures;
        if (failures <= 3) {
            Log.w(TAG, "写入 FnCalls 日志失败 (" + reason + ")，目标: " + processName);
            if (logXposed) {
                HookLog.warn("写入模块日志失败: " + reason
                        + "；请确认模块 " + BuildConfig.APPLICATION_ID + " 已安装");
            }
        }
        if (failures == 5) {
            Log.w(TAG, "FnCalls 日志通道连续失败，后续仅使用框架日志（如已开启）");
        }
        // Keeping a bounded queue avoids an unbounded retry loop against a
        // missing receiver while still preserving recent events.
        synchronized (lock) {
            if (queue.size() > MAX_QUEUE / 2) {
                int discarded = queue.size() - MAX_QUEUE / 2;
                for (int i = 0; i < discarded; i++) {
                    queue.removeFirst();
                }
                dropped += discarded;
            }
        }
    }
}
