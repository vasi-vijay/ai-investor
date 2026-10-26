package com.aiinvestor.api;

import com.aiinvestor.backtest.BacktestService;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs backtests one at a time off the request thread; clients poll the job. */
@Service
public class BacktestJobService {
    private final BacktestService backtests;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "backtest-worker"); t.setDaemon(true); return t; });
    private final Map<String, Map<String, Object>> jobs = new ConcurrentHashMap<>();

    public BacktestJobService(BacktestService backtests) { this.backtests = backtests; }

    public String submit(BacktestService.Request req, BacktestService.SuiteRequest suite) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> job = new ConcurrentHashMap<>();
        job.put("id", id); job.put("status", "QUEUED"); job.put("submitted_at", Instant.now().toString());
        jobs.put(id, job);
        executor.submit(() -> {
            job.put("status", "RUNNING");
            try {
                Map<String, Object> result = suite != null ? backtests.runSuite(suite) : backtests.run(req);
                job.put("result", result);
                job.put("status", "COMPLETED");
            } catch (RuntimeException e) {
                job.put("status", "FAILED");
                job.put("error", e.getClass().getSimpleName() + ": " + com.aiinvestor.util.Redactor.redact(String.valueOf(e.getMessage())));
            }
            job.put("finished_at", Instant.now().toString());
        });
        return id;
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> j = jobs.get(id);
        if (j == null) throw new IllegalArgumentException("unknown job");
        return new LinkedHashMap<>(j);
    }

    @PreDestroy void stop() { executor.shutdownNow(); }
}
