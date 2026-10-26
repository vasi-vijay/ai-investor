package com.aiinvestor.engine;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.market.InstrumentMetadataProvider;
import com.aiinvestor.persistence.InstrumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Resolves the configured symbols to persisted instruments (ids assigned) via the active metadata provider. */
@Component
public class UniverseService {
    private static final Logger log = LoggerFactory.getLogger(UniverseService.class);
    private final InstrumentMetadataProvider metadata;
    private final InstrumentRepository repo;
    private final AppProperties props;

    public UniverseService(InstrumentMetadataProvider metadata, InstrumentRepository repo, AppProperties props) {
        this.metadata = metadata;
        this.repo = repo;
        this.props = props;
    }

    /** Equities and index underlyings that strategies analyse. Indices are analysed but never traded directly. */
    public List<Instrument> analysisUniverse() {
        List<Instrument> out = new ArrayList<>();
        for (String s : props.universe().equities()) {
            Optional<Instrument> i = metadata.findEquity(s);
            if (i.isPresent()) out.add(repo.upsert(i.get())); else log.warn("equity {} not found in instrument metadata", s);
        }
        if (props.universe().optionsEnabled()) {
            for (String s : props.universe().indexUnderlyings()) {
                Optional<Instrument> i = metadata.findIndex(s);
                if (i.isPresent()) out.add(repo.upsert(i.get())); else log.warn("index {} not found in instrument metadata", s);
            }
        }
        return out;
    }

    public Instrument persist(Instrument contract) { return contract.id() != null ? contract : repo.upsert(contract); }
}
