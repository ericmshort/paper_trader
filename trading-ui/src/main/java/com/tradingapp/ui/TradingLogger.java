package com.tradingapp.ui;

import com.tradingapp.broker.AppConfig;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

class TradingLogger {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path LOG_DIR = AppConfig.getDataDir();

    private LocalDate currentDate;
    private Path currentFile;

    TradingLogger() {
        rotate(ZonedDateTime.now(ET).toLocalDate());
    }

    void log(String message) {
        ZonedDateTime now = ZonedDateTime.now(ET);
        LocalDate today = now.toLocalDate();
        if (!today.equals(currentDate)) {
            rotate(today);
        }
        String line = "[" + now.format(STAMP) + "] " + message + System.lineSeparator();
        try {
            Files.writeString(currentFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("TradingLogger write failed: " + e.getMessage());
        }
    }

    private void rotate(LocalDate date) {
        currentDate = date;
        currentFile = LOG_DIR.resolve("events-" + date.format(FILE_DATE) + ".log"); // date is ET
        try {
            Files.createDirectories(LOG_DIR);
        } catch (IOException e) {
            System.err.println("TradingLogger: cannot create log dir: " + e.getMessage());
        }
    }
}
