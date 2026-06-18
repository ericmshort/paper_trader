package com.tradingapp.sentiment;

import com.tradingapp.data.SentimentDirection;
import com.tradingapp.data.SentimentScore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calls the Claude API (Haiku — cheapest/fastest) with today's headlines for all
 * watched symbols in a single request. The system prompt is marked for prompt
 * caching so repeated intraday calls (e.g. after a restart) cost almost nothing.
 */
public class ClaudeNewsAnalyzer {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL   = "claude-haiku-4-5-20251001";
    private static final int    TIMEOUT_MS = 30_000;

    private static final String SYSTEM_PROMPT =
        "You analyze financial news headlines to assess near-term (same-day) intraday trading " +
        "sentiment for US equities.\n\n" +
        "For each symbol provided, return a JSON array where each element has:\n" +
        "- \"symbol\": the ticker symbol (string)\n" +
        "- \"direction\": \"BULLISH\", \"BEARISH\", or \"NEUTRAL\" (string)\n" +
        "- \"weight\": a float 0.0–2.0 indicating signal strength:\n" +
        "    0.0 = no meaningful signal\n" +
        "    1.0 = moderate (analyst rating change, sector news)\n" +
        "    1.5 = strong (earnings beat/miss, major product news)\n" +
        "    2.0 = very strong (FDA approval/rejection, M&A, major legal event)\n" +
        "- \"reason\": one short sentence explaining your assessment (string)\n\n" +
        "Rules:\n" +
        "- Focus only on news that affects same-day price action.\n" +
        "- If no relevant news exists, return NEUTRAL with weight 0.0.\n" +
        "- Respond ONLY with the JSON array — no text before or after.\n\n" +
        "Example:\n" +
        "[{\"symbol\":\"AAPL\",\"direction\":\"NEUTRAL\",\"weight\":0.0,\"reason\":\"No material news today\"}," +
        "{\"symbol\":\"NVDA\",\"direction\":\"BULLISH\",\"weight\":1.5,\"reason\":\"Analyst raised price target\"}," +
        "{\"symbol\":\"PFE\",\"direction\":\"BEARISH\",\"weight\":2.0,\"reason\":\"FDA rejected drug application\"}]";

    private final String apiKey;

    public ClaudeNewsAnalyzer(String apiKey) {
        this.apiKey = apiKey;
    }

    public List<SentimentScore> analyze(Map<String, List<String>> headlinesBySymbol) {
        if (headlinesBySymbol.isEmpty() || apiKey == null || apiKey.isBlank()) return List.of();

        String userContent = buildUserContent(headlinesBySymbol);
        String requestBody = buildRequestBody(userContent);

        try {
            String response = callApi(requestBody);
            return parseResponse(response, headlinesBySymbol.keySet());
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildUserContent(Map<String, List<String>> headlinesBySymbol) {
        StringBuilder sb = new StringBuilder("Today's news headlines by symbol:\n\n");
        for (Map.Entry<String, List<String>> entry : headlinesBySymbol.entrySet()) {
            sb.append(entry.getKey()).append(": ")
              .append(String.join(" | ", entry.getValue()))
              .append('\n');
        }
        return sb.toString();
    }

    private String buildRequestBody(String userContent) {
        // Cache the system prompt — same text all day, so subsequent calls get a cache hit.
        JSONObject sysBlock = new JSONObject();
        sysBlock.put("type", "text");
        sysBlock.put("text", SYSTEM_PROMPT);
        sysBlock.put("cache_control", new JSONObject().put("type", "ephemeral"));

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);

        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("max_tokens", 1024);
        body.put("system", new JSONArray(List.of(sysBlock)));
        body.put("messages", new JSONArray(List.of(userMsg)));
        return body.toString();
    }

    private String callApi(String requestBody) throws IOException {
        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setRequestProperty("anthropic-beta", "prompt-caching-2024-07-31");

        try (OutputStream out = conn.getOutputStream()) {
            out.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream stream = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String response = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();

        if (code >= 400) throw new IOException("Claude API error " + code + ": " + response);
        return response;
    }

    private List<SentimentScore> parseResponse(String response, Set<String> requestedSymbols) {
        try {
            JSONObject obj = new JSONObject(response);
            String text = obj.getJSONArray("content").getJSONObject(0).getString("text").strip();

            int start = text.indexOf('[');
            int end   = text.lastIndexOf(']');
            if (start < 0 || end < 0) return List.of();

            JSONArray arr = new JSONArray(text.substring(start, end + 1));
            List<SentimentScore> result = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                String symbol = item.getString("symbol");
                SentimentDirection dir = switch (item.getString("direction").toUpperCase()) {
                    case "BULLISH" -> SentimentDirection.BULLISH;
                    case "BEARISH" -> SentimentDirection.BEARISH;
                    default        -> SentimentDirection.NEUTRAL;
                };
                double weight  = Math.min(2.0, Math.max(0.0, item.optDouble("weight", 0.0)));
                String summary = item.optString("reason", "");
                result.add(new SentimentScore(symbol, dir, weight, summary));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }
}
