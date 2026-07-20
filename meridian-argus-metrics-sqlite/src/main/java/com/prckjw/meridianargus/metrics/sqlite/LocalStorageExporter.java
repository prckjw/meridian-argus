package com.prckjw.meridianargus.metrics.sqlite;

import com.prckjw.meridianargus.core.exporter.AbstractMetricsExporter;
import com.prckjw.meridianargus.core.model.CostEstimate;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Writes every observed {@link LLMCallRecord} (plus its computed
 * {@link CostEstimate}) as a row in a single-file SQLite database, via plain
 * JDBC — no server process, no extra infrastructure. Meant for teams that
 * want usage/cost history without standing up Prometheus, and as a
 * lightweight local backend for ad-hoc queries (see {@link UsageQuery}) or
 * a nightly export job.
 * <p>
 * <b>Reliability contract</b>: matching every other exporter in this
 * project, a failure to write a row is logged and swallowed here, never
 * thrown — one broken export path must never break the caller's business
 * logic. If you need write failures to be visible, monitor the SLF4J
 * warning this class logs on failure through your usual logging pipeline.
 * <p>
 * <b>Concurrency</b>: SQLite serializes writers at the database level
 * regardless, so {@link #export(LLMCallRecord, CostEstimate)} synchronizes
 * on {@code this} to avoid "database is locked" exceptions under concurrent
 * calls rather than let them surface as write failures. Write-Ahead Logging
 * ({@code PRAGMA journal_mode=WAL}) is enabled on open so concurrent reads
 * (e.g. from {@link UsageQuery}, or an external tool like the
 * {@code sqlite3} CLI) aren't blocked by an in-flight write.
 * <p>
 * Instances hold an open JDBC connection and should be {@link #close()}d on
 * application shutdown — the Spring Boot starter's auto-configuration
 * handles this automatically via a {@code DisposableBean}-style destroy
 * method when this exporter is created as a Spring bean.
 * <p>
 * Typical usage:
 * <pre>{@code
 * LocalStorageExporter exporter = new LocalStorageExporter(pricingEngine, Path.of("meridian-argus.db"));
 * registry.register(exporter);
 * // ... later, on shutdown:
 * exporter.close();
 * }</pre>
 */
public final class LocalStorageExporter extends AbstractMetricsExporter implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(LocalStorageExporter.class);

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS llm_calls (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                provider TEXT NOT NULL,
                model TEXT NOT NULL,
                started_at TEXT NOT NULL,
                latency_ms INTEGER NOT NULL,
                success INTEGER NOT NULL,
                error_type TEXT,
                caller_id TEXT,
                input_tokens INTEGER NOT NULL,
                output_tokens INTEGER NOT NULL,
                price_known INTEGER NOT NULL,
                input_cost_usd TEXT,
                output_cost_usd TEXT,
                total_cost_usd TEXT
            )
            """;

    private static final String INSERT_SQL = """
            INSERT INTO llm_calls
                (provider, model, started_at, latency_ms, success, error_type, caller_id,
                 input_tokens, output_tokens, price_known, input_cost_usd, output_cost_usd, total_cost_usd)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final Connection connection;

    /**
     * Opens (creating if necessary, including parent directories) a SQLite
     * database at {@code databaseFile} and ensures the {@code llm_calls}
     * table and its indexes exist.
     *
     * @throws IllegalStateException if the file/directories can't be
     *                                created or the database can't be opened
     */
    public LocalStorageExporter(PricingEngine pricingEngine, Path databaseFile) {
        super(pricingEngine);
        this.connection = openConnection(databaseFile);
        createSchemaIfNeeded(connection);
    }

    private static Connection openConnection(Path databaseFile) {
        try {
            Path parent = databaseFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
            }
            return c;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to open MeridianArgus SQLite database at " + databaseFile, e);
        }
    }

    private static void createSchemaIfNeeded(Connection connection) {
        try (Statement s = connection.createStatement()) {
            s.execute(CREATE_TABLE_SQL);
            s.execute("CREATE INDEX IF NOT EXISTS idx_llm_calls_started_at ON llm_calls(started_at)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_llm_calls_provider_model ON llm_calls(provider, model)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize MeridianArgus SQLite schema", e);
        }
    }

    @Override
    public synchronized void export(LLMCallRecord record, CostEstimate cost) {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
            ps.setString(1, record.getProviderModel().getProvider());
            ps.setString(2, record.getProviderModel().getModel());
            ps.setString(3, record.getStartedAt().toString());
            ps.setLong(4, record.getLatency().toMillis());
            ps.setInt(5, record.isSuccess() ? 1 : 0);
            ps.setString(6, record.getErrorType());
            ps.setString(7, record.getCallerId());
            ps.setInt(8, record.getTokenUsage().getInputTokens());
            ps.setInt(9, record.getTokenUsage().getOutputTokens());
            ps.setInt(10, cost.isPriceKnown() ? 1 : 0);
            ps.setString(11, cost.isPriceKnown() ? cost.getInputCost().toPlainString() : null);
            ps.setString(12, cost.isPriceKnown() ? cost.getOutputCost().toPlainString() : null);
            ps.setString(13, cost.isPriceKnown() ? cost.getTotalCost().toPlainString() : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to write LLM call record to SQLite database (provider={}, model={})",
                    record.getProviderModel().getProvider(), record.getProviderModel().getModel(), e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.warn("Failed to close MeridianArgus SQLite connection cleanly", e);
        }
    }
}
