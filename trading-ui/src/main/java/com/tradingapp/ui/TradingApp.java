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
        primaryStage.setTitle("TradingApp — Paper Trading Simulator");
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
        launch(args);
    }
}
