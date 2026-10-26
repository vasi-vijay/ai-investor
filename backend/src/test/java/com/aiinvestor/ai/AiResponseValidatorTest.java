package com.aiinvestor.ai;

import com.aiinvestor.domain.Enums.Decision;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiResponseValidatorTest {
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final AiResponseValidator validator = new AiResponseValidator(mapper);
    private final AnalysisRequest req = new AnalysisRequest("run", "BACKTEST", "sig", "RELIANCE", "VWAP_TREND", "1.0", "5m",
            Decision.BUY_CANDIDATE, 100.0, 1.0, Map.of());

    private static String json(String decision, String entry, String stop, String target) {
        return """
                {"instrument":"RELIANCE","decision":"%s","strategy":"VWAP_TREND","timeframe":"5m","confidence":55,
                 "entry_price":%s,"stop_loss":%s,"target_price":%s,"supporting_conditions":["a"],"contradicting_conditions":[],
                 "invalidation_conditions":["x"],"missing_information":[],"strategy_agreement":"AGREES","disagreement_explanation":"",
                 "reject_trade":false,"reasoning":["because"],"data_quality":"VALID"}""".formatted(decision, entry, stop, target);
    }

    @Test
    void acceptsAWellFormedCandidate() {
        var v = validator.validate(json("BUY_CANDIDATE", "100.0", "99.0", "102.0"), req);
        assertThat(v.valid()).as(v.errors().toString()).isTrue();
        assertThat(v.assessment().decision()).isEqualTo(Decision.BUY_CANDIDATE);
        assertThat(v.assessment().entryPrice()).isEqualTo(100.0);
        assertThat(v.parsedJson()).contains("\"entry_price\"");       // snake_case schema in stored validated state
    }

    @Test
    void acceptsNoTradeWithNullPricesAndMarkdownFences() {
        var v = validator.validate("```json\n" + json("NO_TRADE", "null", "null", "null") + "\n```", req);
        assertThat(v.valid()).as(v.errors().toString()).isTrue();
        assertThat(v.assessment().entryPrice()).isNull();
    }

    @Test
    void rejectsMalformedJsonAndEmptyResponses() {
        assertThat(validator.validate("{not json", req).valid()).isFalse();
        assertThat(validator.validate("", req).valid()).isFalse();
        assertThat(validator.validate(null, req).valid()).isFalse();
        assertThat(validator.validate("I think you should buy RELIANCE", req).valid()).isFalse();
        assertThat(validator.validate("{not json", req).errors().get(0)).startsWith("malformed JSON");
    }

    @Test
    void rejectsDecisionsOutsideTheAllowedSet() {
        var v = validator.validate(json("STRONG_BUY", "100", "99", "102"), req);
        assertThat(v.valid()).isFalse();
    }

    @Test
    void rejectsIdentifierMismatchesAndOutOfRangeConfidence() {
        String bad = json("NO_TRADE", "null", "null", "null").replace("RELIANCE", "TCS");
        assertThat(validator.validate(bad, req).errors().toString()).contains("instrument mismatch");
        String tf = json("NO_TRADE", "null", "null", "null").replace("\"5m\"", "\"15m\"");
        assertThat(validator.validate(tf, req).errors().toString()).contains("timeframe mismatch");
        String conf = json("NO_TRADE", "null", "null", "null").replace("\"confidence\":55", "\"confidence\":150");
        assertThat(validator.validate(conf, req).errors().toString()).contains("confidence out of range");
    }

    @Test
    void rejectsInvalidPrices() {
        assertThat(validator.validate(json("BUY_CANDIDATE", "100", "101", "102"), req).errors().toString()).contains("not ordered");         // stop above entry
        assertThat(validator.validate(json("SELL_CANDIDATE", "100", "99", "98"), req).errors().toString()).contains("not ordered");        // sell with long levels
        assertThat(validator.validate(json("BUY_CANDIDATE", "100", "null", "102"), req).errors().toString()).contains("requires entry_price");
        assertThat(validator.validate(json("BUY_CANDIDATE", "500", "490", "520"), req).errors().toString()).contains("implausible price");
        assertThat(validator.validate(json("BUY_CANDIDATE", "-5", "-6", "-4"), req).errors().toString()).contains("non-positive");
    }

    @Test
    void rejectsEmptyReasoning() {
        String noReason = json("NO_TRADE", "null", "null", "null").replace("\"reasoning\":[\"because\"]", "\"reasoning\":[]");
        assertThat(validator.validate(noReason, req).errors().toString()).contains("reasoning is empty");
    }

    @Test
    void ignoresUnknownFieldsButNeverInventsMissingOnes() {
        String extra = json("NO_TRADE", "null", "null", "null").replaceFirst("\\{", "{\\\"order_now\\\":true,\\\"set_risk_limit\\\":999999,");
        var v = validator.validate(extra, req);
        assertThat(v.valid()).isTrue();                              // unknown instructions are simply dropped, never acted upon
        assertThat(v.parsedJson()).doesNotContain("order_now").doesNotContain("set_risk_limit");
    }
}
