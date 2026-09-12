package com.okotu.aihuman.db;

import com.okotu.aihuman.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Shared MySQL datasource (HikariCP pool) + schema application on startup.
 *
 * <p>Table names are resolved through {@link #table(String)}, which prepends
 * the configured {@code mysql-table-prefix} for the active profile (prod/test).
 * This lets several deployments share the same physical database with
 * different prefixes if needed.
 */
public class Database {

    private final Plugin plugin;
    private final HikariDataSource dataSource;
    private final String tablePrefix;
    private final String profileName;
    private final String databaseName;

    public Database(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.tablePrefix = config.mysqlTablePrefix == null ? "" : config.mysqlTablePrefix;
        this.profileName = config.activeProfile;
        this.databaseName = config.mysqlDatabase;

        String jdbcUrl = "jdbc:mysql://" + config.mysqlHost + ":" + config.mysqlPort
                + "/" + config.mysqlDatabase
                + (config.mysqlExtraParams.isBlank() ? "" : "?" + config.mysqlExtraParams);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(config.mysqlUsername);
        hikariConfig.setPassword(config.mysqlPassword);
        hikariConfig.setMaximumPoolSize(config.mysqlPoolSize);
        hikariConfig.setPoolName("okotu-npc-ai-pool-" + profileName);
        // Recommended by HikariCP for the MySQL driver, reduces overhead on repeated queries
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        this.dataSource = new HikariDataSource(hikariConfig);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Resolves a base table name (e.g. "npc_character") to its prefixed form. */
    public String table(String baseName) {
        return tablePrefix + baseName;
    }

    public String profileName() {
        return profileName;
    }

    public String databaseName() {
        return databaseName;
    }

    /** Creates the tables if they don't exist, reading schema.sql bundled in the jar. */
    public void applySchema() {
        List<String> statements = readSchemaStatements();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
            plugin.getLogger().info("MySQL schema checked/applied on profile '" + profileName
                    + "' (database=" + databaseName + ", table-prefix='" + tablePrefix + "', "
                    + statements.size() + " statements).");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not apply the MySQL schema. "
                    + "The plugin may not work correctly.", e);
        }
    }

    private List<String> readSchemaStatements() {
        List<String> statements = new ArrayList<>();
        try (InputStream in = plugin.getResource("schema.sql")) {
            if (in == null) {
                plugin.getLogger().warning("schema.sql not found in the jar.");
                return statements;
            }
            StringBuilder buffer = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                        continue;
                    }
                    buffer.append(line).append('\n');
                    if (trimmed.endsWith(";")) {
                        statements.add(buffer.toString().replace("{{PREFIX}}", tablePrefix));
                        buffer.setLength(0);
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Error reading schema.sql", e);
        }
        return statements;
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
