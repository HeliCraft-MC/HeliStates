package ru.helicraft.helistates.database;

import org.bukkit.configuration.ConfigurationSection;
import ru.helicraft.helistates.HeliStates;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Открывает соединение к SQLite или MySQL по данным из config.yml
 * и инициализирует схему таблицы regions с нужным индексом.
 */
public final class DatabaseManager {

    private Connection connection;

    /** Подключается и инициализирует схему. */
    public void connect(ConfigurationSection db) throws SQLException {
        String type = db.getString("type", "sqlite").toLowerCase();
        switch (type) {
            case "sqlite" -> {
                String file = db.getString("file", "plugins/HeliStates/regions.db");
                connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                try (Statement s = connection.createStatement()) {
                    // Для устойчивости к сбоям
                    s.execute("PRAGMA journal_mode=WAL;");
                }
            }
            case "mysql" -> {
                String url = db.getConfigurationSection("mysql")
                        .getString("connectionString");
                connection = DriverManager.getConnection(url);
            }
            default -> throw new SQLException("Unknown DB type: " + type);
        }
        initSchema();
    }

    /**
     * Создаёт таблицу regions и индекс world_bounds,
     * подбирая DDL в зависимости от СУБД.
     */
    private void initSchema() throws SQLException {
        String driver = connection.getMetaData().getDriverName().toLowerCase();
        try (Statement st = connection.createStatement()) {
            if (driver.contains("sqlite")) {
                // SQLite: таблица без inline-индекса…
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS regions (
                      id      TEXT      PRIMARY KEY,
                      world   TEXT,
                      biome   TEXT,
                      area    INTEGER,
                      outline TEXT,
                      min_x   INTEGER,
                      min_z   INTEGER,
                      max_x   INTEGER,
                      max_z   INTEGER
                    );
                    """);  // SQLite не поддерживает INDEX внутри CREATE TABLE :contentReference[oaicite:2]{index=2}

                // …создаём индекс отдельно
                st.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS world_bounds
                      ON regions(world, min_x, max_x, min_z, max_z);
                    """);  // Поддерживается IF NOT EXISTS :contentReference[oaicite:3]{index=3}

            } else {
                // MySQL: таблица + inline-индекс
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS regions (
                      id      VARCHAR(36) PRIMARY KEY,
                      world   VARCHAR(64),
                      biome   VARCHAR(64),
                      area    INT,
                      outline TEXT,
                      min_x   INT,
                      min_z   INT,
                      max_x   INT,
                      max_z   INT,
                      INDEX world_bounds (world, min_x, max_x, min_z, max_z)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);  // INLINE INDEX поддерживается в MySQL :contentReference[oaicite:4]{index=4}
            }
        }
    }

    /** Возвращает открытое соединение. */
    public Connection getConnection() {
        return connection;
    }

    /** Закрывает соединение к БД. */
    public void disconnect() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException e) {
            HeliStates.getInstance().getLogger()
                    .warning("Failed to close DB connection: " + e.getMessage());
        }
    }
}
