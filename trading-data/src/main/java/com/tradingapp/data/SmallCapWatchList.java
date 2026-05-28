package com.tradingapp.data;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SmallCapWatchList {
    public static final List<String> SYMBOLS = Collections.unmodifiableList(Arrays.asList(
        // Fintech / consumer finance
        "SOFI", "HOOD", "AFRM", "UPST", "OPEN", "LMND", "HIMS", "BRZE",
        // EV / clean energy
        "RIVN", "LCID", "CHPT", "PLUG", "BE", "BLNK", "JOBY", "ACHR",
        // Crypto-adjacent / high-volatility
        "GME", "AMC", "MARA", "RIOT", "CLSK",
        // Biotech / genomics
        "BEAM", "EDIT", "NTLA", "RXRX", "PACB", "ARWR", "EXAS", "NTRA", "CRSP", "FATE",
        // Retail / consumer
        "CHWY", "PRGO", "FIGS", "FIVE", "OSTK",
        // Materials / mining
        "MP", "PAAS", "HL",
        // Software / SaaS
        "PSTG", "APPN", "NCNO", "COUR", "FRSH", "DUOL",
        // Diversified small-cap
        "BB", "CLF", "PENN", "DKNG", "RUN"
    ));

    private SmallCapWatchList() {}
}
