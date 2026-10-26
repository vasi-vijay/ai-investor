package com.aiinvestor.ai;

import com.aiinvestor.ai.AnalysisOutcome.Status;
import com.aiinvestor.domain.Enums.AiMode;
import com.aiinvestor.domain.Enums.Decision;
import com.aiinvestor.domain.Enums.RunMode;
import com.aiinvestor.persistence.RunRepository.Run;
import com.aiinvestor.support.AbstractDbTest;
import com.aiinvestor.support.AiTestConfig;
import com.aiinvestor.support.Fixtures;
import com.aiinvestor.support.ScriptedAnalyst;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import(AiTestConfig.class)
class AiAnalysisServiceTest extends AbstractDbTest {
    @Autowired AiAnalysisService service;
    @Autowired ScriptedAnalyst analyst;
    @Autowired Fixtures fx;
    Run run;

    @BeforeEach
    void setUp() {
        run = fx.run(RunMode.BACKTEST, AiMode.AI_GATED);
        analyst.calls.set(0);
        analyst.configured = true;
        analyst.script = ScriptedAnalyst::agree;
    }

    private AnalysisRequest request(String tag) {
        Map<String, Object> snap = new LinkedHashMap<>();
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("entry", 100.0); prop.put("stop", 99.0); prop.put("target", 102.0);
        snap.put("strategy_proposal", prop);
        snap.put("tag", tag);
        return new AnalysisRequest(run.id(), "BACKTEST", "sig-" + tag, "RELIANCE", "VWAP_TREND", "1.0", "5m", Decision.BUY_CANDIDATE, 100.0, 1.0, snap);
    }

    private Map<String, Object> record(String id) { return jdbc.sql("SELECT * FROM ai_analysis_records WHERE id=?").param(id).query().singleRow(); }

    @Test
    void validResponseIsPersistedWithRawAndParsedSeparately() {
        AnalysisOutcome o = service.analyze(request("a"), true, 10);
        assertThat(o.isValid()).isTrue();
        Map<String, Object> r = record(o.recordId());
        assertThat(r.get("validation_status")).isEqualTo("VALID");
        assertThat((String) r.get("raw_response")).contains("scripted test response");
        assertThat((String) r.get("parsed_json")).contains("\"decision\"");
        assertThat(r.get("prompt_version")).isEqualTo("p1");
        assertThat(r.get("schema_version")).isEqualTo("1");
        assertThat(r.get("model")).isEqualTo("scripted-test-model");
        assertThat(((Number) r.get("input_tokens")).longValue()).isEqualTo(100L);
        assertThat(r.get("latency_ms")).isNotNull();
        assertThat((String) r.get("prompt_hash")).hasSize(64);
        assertThat((String) r.get("request_json")).contains("user_prompt");
    }

    @Test
    void malformedModelOutputIsRejectedButStillRecorded() {
        analyst.script = r -> "Sure! Here is my analysis: buy it.";
        AnalysisOutcome o = service.analyze(request("b"), true, 10);
        assertThat(o.status()).isEqualTo(Status.INVALID);
        assertThat(o.isValid()).isFalse();
        assertThat(o.assessment()).isNull();
        Map<String, Object> r = record(o.recordId());
        assertThat(r.get("raw_response")).isEqualTo("Sure! Here is my analysis: buy it.");     // raw output kept
        assertThat(r.get("parsed_json")).isNull();                                              // nothing reaches validated state
        assertThat((String) r.get("validation_errors")).contains("malformed JSON");
    }

    @Test
    void invalidPricesAreRejected() {
        analyst.script = r -> ScriptedAnalyst.agree(r).replace("\"stop_loss\":99.0", "\"stop_loss\":101.0");
        AnalysisOutcome o = service.analyze(request("c"), true, 10);
        assertThat(o.status()).isEqualTo(Status.INVALID);
        assertThat(o.errors().toString()).contains("not ordered");
    }

    @Test
    void apiTimeoutIsRecordedAsTimeout() {
        analyst.script = r -> { throw ScriptedAnalyst.timeout(); };
        AnalysisOutcome o = service.analyze(request("d"), true, 10);
        assertThat(o.status()).isEqualTo(Status.TIMEOUT);
        assertThat(record(o.recordId()).get("validation_status")).isEqualTo("TIMEOUT");
        assertThat(service.health().lastFailureMessage()).contains("TIMEOUT");
    }

    @Test
    void refusalAndUnexpectedErrorsAreRecordedAndNeverEscape() {
        analyst.script = r -> { throw new AiException(AiException.Kind.REFUSED, "model refused (stop_reason=refusal)"); };
        assertThat(service.analyze(request("e"), true, 10).status()).isEqualTo(Status.REFUSED);
        analyst.script = r -> { throw new IllegalStateException("boom"); };
        assertThat(service.analyze(request("f"), true, 10).status()).isEqualTo(Status.ERROR);
    }

    @Test
    void missingCredentialsYieldAnErrorRecordNotAnExecutableSignal() {
        analyst.configured = false;
        AnalysisOutcome o = service.analyze(request("g"), true, 10);
        assertThat(o.status()).isEqualTo(Status.ERROR);
        assertThat(o.errors().get(0)).contains("not configured");
        assertThat(analyst.calls.get()).isZero();
    }

    @Test
    void identicalPromptsAreReusedWithoutCallingTheModelAgain() {
        AnalysisOutcome first = service.analyze(request("h"), true, 10);
        AnalysisOutcome second = service.analyze(request("h"), true, 10);
        assertThat(analyst.calls.get()).isEqualTo(1);
        assertThat(second.isValid()).isTrue();
        assertThat(second.reusedFromId()).isEqualTo(first.recordId());
        assertThat(record(second.recordId()).get("reused_from_id")).isEqualTo(first.recordId());
    }

    @Test
    void callBudgetIsEnforcedForBacktests() {
        assertThat(service.analyze(request("i1"), true, 2).isValid()).isTrue();
        assertThat(service.analyze(request("i2"), true, 2).isValid()).isTrue();
        AnalysisOutcome third = service.analyze(request("i3"), true, 2);
        assertThat(third.status()).isEqualTo(Status.SKIPPED);
        assertThat(third.errors().get(0)).contains("budget exhausted");
        assertThat(analyst.calls.get()).isEqualTo(2);
    }

    @Test
    void agreementRequiresTheSameDirectionAndNoRejectFlag() {
        AnalysisOutcome agree = service.analyze(request("j"), true, 10);
        assertThat(AiAnalysisService.agrees(Decision.BUY_CANDIDATE, agree.assessment())).isTrue();
        assertThat(AiAnalysisService.agrees(Decision.SELL_CANDIDATE, agree.assessment())).isFalse();
        analyst.script = ScriptedAnalyst::disagree;
        AnalysisOutcome no = service.analyze(request("k"), true, 10);
        assertThat(AiAnalysisService.agrees(Decision.BUY_CANDIDATE, no.assessment())).isFalse();
        assertThat(AiAnalysisService.disagreementNote(Decision.BUY_CANDIDATE, no)).contains("NO_TRADE");
    }
}
