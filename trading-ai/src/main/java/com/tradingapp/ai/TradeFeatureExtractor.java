package com.tradingapp.ai;

import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class TradeFeatureExtractor {

    private static final Logger LOG = Logger.getLogger(TradeFeatureExtractor.class.getName());

    public List<LabeledTrade> extract(TransactionLog log) {
        List<TransactionRecord> all = new ArrayList<>(log.findAll());
        java.util.Collections.reverse(all); // findAll() returns DESC; process chronologically
        List<LabeledTrade> result = new ArrayList<>();

        for (int i = 0; i < all.size(); i++) {
            TransactionRecord buy = all.get(i);
            if (buy.getFeatures() == null || buy.getFeatures().isBlank()) continue;

            TransactionRecord.TransactionAction closeAction = matchingCloseAction(buy.getAction());
            if (closeAction == null) continue;

            for (int j = i + 1; j < all.size(); j++) {
                TransactionRecord sell = all.get(j);
                if (sell.getAction() != closeAction) continue;
                if (!sell.getSymbol().equals(buy.getSymbol())) continue;

                boolean win = sell.getPricePerUnit() > buy.getPricePerUnit();
                try {
                    FeatureVector fv = FeatureVector.fromCsv(buy.getFeatures());
                    result.add(new LabeledTrade(fv, win));
                } catch (Exception e) {
                    LOG.warning("Skipping trade id=" + buy.getId() + " due to bad features CSV: " + e.getMessage());
                }
                break;
            }
        }
        return result;
    }

    private TransactionRecord.TransactionAction matchingCloseAction(TransactionRecord.TransactionAction open) {
        return switch (open) {
            case BUY       -> TransactionRecord.TransactionAction.SELL;
            case CALL_BUY  -> TransactionRecord.TransactionAction.CALL_SELL;
            case PUT_BUY   -> TransactionRecord.TransactionAction.PUT_SELL;
            default        -> null;
        };
    }
}
