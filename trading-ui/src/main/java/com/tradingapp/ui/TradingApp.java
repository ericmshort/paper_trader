package com.tradingapp.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class TradingApp extends Application {

    private DashboardController controller;

    @Override
    public void start(Stage primaryStage) throws IOException {
        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/com/tradingapp/ui/dashboard.fxml"));
        Scene scene = new Scene(loader.load(), 1200, 800);
        controller = loader.getController();
        primaryStage.setTitle("Day Trader");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.stop();
        }
    }

    public static void main(String[] args) {
        // Force pure-Java netlib implementations; native JARs are excluded by the JavaFX
        // module plugin because "native" is not a valid Java identifier in a module name.
        System.setProperty("com.github.fommil.netlib.ARPACK", "com.github.fommil.netlib.F2jARPACK");
        System.setProperty("com.github.fommil.netlib.BLAS",   "com.github.fommil.netlib.F2jBLAS");
        System.setProperty("com.github.fommil.netlib.LAPACK",  "com.github.fommil.netlib.F2jLAPACK");
        launch(args);
    }
}
