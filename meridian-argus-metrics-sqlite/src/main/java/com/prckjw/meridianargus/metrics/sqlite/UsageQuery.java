package com.prckjw.meridianargus.metrics.sqlite;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-side query helper for a MeridianArgus SQLite database written by
 * {@link LocalStorageExporter}. Opens a short-lived connection per call
 * rather than holding one open — this class is meant for occasional
 * questions ("what did we spend in the last hour?"), a scheduled report
 * job, or a small CLI tool, not a hot path. For anything higher-frequency,
 * query the SQLite file directly with your own connection pooling.
 * <p>
 * Safe to use concurrently with an active {@link LocalStorageExporter}
 * writing to the same file: the database is opened with
 * {@code PRAGMA journal_mode=WAL}, which allows readers to proceed without
 * blocking on an in-flight writer.
 * <p>
 * Typical usage:
 * <pre>{@code
 * UsageQuery query = new UsageQuery(Path.of("meridian-argus.db"));
 * List<ProviderModelCostSummary> lastHour = query.costByProviderModel(Duration.ofHours(1));
 * BigDecimal totalLast24h = query.totalCost(Duration.ofHours(24));
 * }</pre>
 */
public final class UsageQuery {

    private final Path databaseFile;

    public UsageQuery(Path databaseFile) {
        this.databaseFile = databaseFile;
    }

    /**
     * Per-provider/model breakdown of call count, error count, token usage,
     * and total cost for calls started within {@code lookback} of now.
     * Providers/models with zero calls in the window are simply absent from
     * the result, not returned with zeroed-out rows.
     */
    public List<ProviderModelCostSummary> costByProviderModel(Duration lookback) {
        String sql = """
                SELECT provider,
                       model,
                       COUNT(*) AS call_count,
                       SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS error_count,
                       SUM(input_tokens) AS input_tokens,
                       SUM(output_tokens) AS output_tokens,
                       SUM(CASE WHEN price_known = 1 THEN CAST(total_cost_usd AS REAL) ELSE 0 END) AS total_cost,
                       SUM(price_known) AS priced_call_count
                FROM llm_calls
                WHERE started_at >= ?
                GROUP BY provider, model
                ORDER BY total_cost DESC
                """;

        List<ProviderModelCostSummary> results = new ArrayList<>();
        try (Connection connection = openReadConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, cutoff(lookback));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long pricedCallCount = rs.getLong("priced_call_count");
                    BigDecimal totalCost = pricedCallCount > 0
                            ? BigDecimal.valueOf(rs.getDouble("total_cost"))
                            : null;

                    results.add(new ProviderModelCostSummary(
                            rs.getString("provider"),
                            rs.getString("model"),
                            rs.getLong("call_count"),
                            rs.getLong("error_count"),
                            rs.getLong("input_tokens"),
                            rs.getLong("output_tokens"),
                            totalCost));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query MeridianArgus SQLite database at " + databaseFile, e);
        }
        return results;
    }

    /**
     * Total estimated cost across every provider/model for calls started
     * within {@code lookback} of now. Returns {@link BigDecimal#ZERO} if no
     * calls in the window have a known price yet — unlike
     * {@link ProviderModelCostSummary}, this aggregate collapses the
     * unknown-vs-zero distinction for simplicity; use
     * {@link #costByProviderModel(Duration)} if you need to know whether any
     * calls had unpriced models.
     */
    public BigDecimal totalCost(Duration lookback) {
        return costByProviderModel(lookback).stream()
                .map(s -> s.totalCostUsd() == null ? BigDecimal.ZERO : s.totalCostUsd())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Total number of calls (successful or not) started within {@code lookback} of now. */
    public long callCount(Duration lookback) {
        String sql = "SELECT COUNT(*) AS c FROM llm_calls WHERE started_at >= ?";
        try (Connection connection = openReadConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, cutoff(lookback));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("c") : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query MeridianArgus SQLite database at " + databaseFile, e);
        }
    }

    private Connection openReadConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
    }

    private static String cutoff(Duration lookback) {
        // started_at is stored as Instant#toString() (ISO-8601), which sorts
        // lexicographically the same as chronologically, so a plain string
        // comparison in SQL is enough -- no need for SQLite date functions.
        return Instant.now().minus(lookback).toString();
    }
}
