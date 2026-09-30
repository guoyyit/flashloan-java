package com.github.guoyyit.flashloanjava.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import com.github.guoyyit.flashloanjava.domain.model.LoanConfig;
import com.github.guoyyit.flashloanjava.domain.model.LoanRecord;
import com.github.guoyyit.flashloanjava.domain.model.Pool;

public class PersistentState {

    private TreeMap<String, Pool> pools = new TreeMap<>();
    private LoanConfig config;
    private long nextLoanId = 1;
    private List<LoanRecord> records = new ArrayList<>();

    public TreeMap<String, Pool> getPools() {
        return pools;
    }

    public void setPools(TreeMap<String, Pool> pools) {
        this.pools = pools == null ? new TreeMap<>() : pools;
    }

    public LoanConfig getConfig() {
        return config;
    }

    public void setConfig(LoanConfig config) {
        this.config = config;
    }

    public long getNextLoanId() {
        return nextLoanId;
    }

    public void setNextLoanId(long nextLoanId) {
        this.nextLoanId = nextLoanId;
    }

    public List<LoanRecord> getRecords() {
        return records;
    }

    public void setRecords(List<LoanRecord> records) {
        this.records = records == null ? new ArrayList<>() : records;
    }
}
