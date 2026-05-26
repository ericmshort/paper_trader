package com.tradingapp.ui;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

class TradingLogger {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path LOG_DIR = Path.of(System.getProperty("user.home"), ".tradingapp");

    private LocalDate currentDate;
    private Path currentFile;

    TradingLogger() {
        rotate(LocalDate.now());
    }

    void log(String message) {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDate)) {
            rotate(today);
        }
        String line = "[" + LocalDateTime.now().format(STAMP) + "] " + message + System.lineSeparator();
        try {
            Files.writeString(currentFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("TradingLogger write failed: " + e.getMessage());
        }
    }

    private void rotate(LocalDate date) {
        currentDate = date;
        currentFile = LOG_DIR.resolve("events-" + date.format(FILE_DATE) + ".log");
        try {
            Files.createDirectories(LOG_DIR);
        } catch (IOException e) {
            System.err.println("TradingLogger: cannot create log dir: " + e.getMessage());
        }
    }
}
