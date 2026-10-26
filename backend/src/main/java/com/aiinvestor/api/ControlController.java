package com.aiinvestor.api;

import com.aiinvestor.backtest.BacktestService;
import com.aiinvestor.domain.Enums.AiMode;
import com.aiinvestor.persistence.AuditRepository;
import com.aiinvestor.persistence.RunRepository;
import com.aiinvestor.risk.KillSwitch;
import com.aiinvestor.runtime.PaperTradingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** State-changing endpoints — ADMIN only, validated, audited. None of them can enable live trading or alter risk limits. */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('ADMIN')")
public class ControlController {
    public record KillSwitchRequest(boolean engaged, @NotBlank @Size(max = 300) String reason) {}
    public record NewPaperRunRequest(@NotNull AiMode aiMode, @NotBlank @Size(max = 100) String name) {}
    public record BacktestDto(@NotBlank @Size(max = 100) String name, @NotNull LocalDate from, @NotNull LocalDate to,
                              @Size(max = 20) String periodLabel, Map<String, Map<String, Object>> strategyParams,
                              @DecimalMin("10000") @DecimalMax("1000000000") BigDecimal capital, List<AiMode> modes,
                              @Min(0) @Max(5000) Integer maxAiCalls, @DecimalMin("0") @DecimalMax("200") Double equitySlippageBps,
                              @DecimalMin("0") @DecimalMax("20") Double optionSlippagePct, List<@Size(max = 40) String> strategies,
                              @Min(0) @Max(400) Integer developmentDays, @Min(0) @Max(400) Integer validationDays, @Min(0) @Max(400) Integer outOfSampleDays) {}

    private final KillSwitch kill;
    private final PaperTradingService paper;
    private final BacktestJobService jobs;
    private final AuditRepository audit;

    public ControlController(KillSwitch kill, PaperTradingService paper, BacktestJobService jobs, AuditRepository audit) {
        this.kill = kill; this.paper = paper; this.jobs = jobs; this.audit = audit;
    }

    @PostMapping("/risk/kill-switch")
    public Map<String, Object> killSwitch(@Valid @RequestBody KillSwitchRequest r, Authentication auth) {
        kill.set(r.engaged(), auth.getName(), r.reason());
        return Map.of("kill_switch", kill.isEngaged());
    }

    @PostMapping("/paper/runs")
    public Map<String, Object> newPaperRun(@Valid @RequestBody NewPaperRunRequest r, Authentication auth) {
        RunRepository.Run run = paper.startNewRun(r.aiMode(), r.name(), auth.getName());
        return Map.of("run_id", run.id(), "ai_mode", run.aiMode().name());
    }

    @PostMapping("/backtests")
    public Map<String, Object> backtest(@Valid @RequestBody BacktestDto d, Authentication auth) {
        if (d.to().isBefore(d.from())) throw new IllegalArgumentException("'to' must not be before 'from'");
        if (java.time.temporal.ChronoUnit.DAYS.between(d.from(), d.to()) > 400) throw new IllegalArgumentException("range too long (max 400 days)");
        BacktestService.Request base = new BacktestService.Request(d.name(), d.from(), d.to(), d.periodLabel(), d.strategyParams(), d.capital(), d.modes(),
                d.maxAiCalls(), d.equitySlippageBps(), d.optionSlippagePct(), d.strategies());
        audit.log(auth.getName(), "BACKTEST_SUBMITTED", "backtest", null, d.name() + " " + d.from() + ".." + d.to());
        boolean suite = d.developmentDays() != null || d.validationDays() != null || d.outOfSampleDays() != null;
        String id = suite
                ? jobs.submit(null, new BacktestService.SuiteRequest(d.name(), d.from(), d.to(), nz(d.developmentDays()), nz(d.validationDays()), nz(d.outOfSampleDays()), base))
                : jobs.submit(base, null);
        return Map.of("job_id", id);
    }

    @GetMapping("/backtests/jobs/{id}")
    public Map<String, Object> job(@PathVariable String id) { return jobs.get(id); }

    private static int nz(Integer i) { return i == null ? 0 : i; }
}
