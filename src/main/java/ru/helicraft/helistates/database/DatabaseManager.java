package ru.helicraft.helistates.database;

import ru.helicraft.helistates.HeliStates;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private Connection connection;

    public void connect(String connectionString) throws SQLException {
        connection = DriverManager.getConnection(connectionString);
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS regions (" +
                    "id VARCHAR(36) PRIMARY KEY," +
                    "world VARCHAR(64)," +
                    "biome VARCHAR(64)," +
                    "area INT," +
                    "outline MEDIUMTEXT," +
                    "min_x INT," +
                    "min_z INT," +
                    "max_x INT," +
                    "max_z INT" +
                    ")"
            );
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public void disconnect() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                HeliStates.getInstance().getLogger().warning("Failed to close DB connection: " + e.getMessage());
            }
        }
    }
}
