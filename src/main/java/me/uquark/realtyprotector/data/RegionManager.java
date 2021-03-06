package me.uquark.realtyprotector.data;

import me.uquark.quarkcore.data.DatabaseProvider;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class RegionManager {
    private final Connection connection;
    public static final int MAX_VOLUME = 250000;
    private static final String MOD_NAME = "RealtyProtector";
    private static final String DB_NAME = "storage";
    private static final String DB_USER = "rp_user";
    private static final String DB_PASS = "";

    public enum RegionRegistrationResult {
        OK,
        Overlap,
        TooBig,
        Fail,
        ClientIsNotEnabled,
        NotEnoughPoints,
    }

    public enum RegionDeletionResult {
        OK,
        NotOwner,
        NoRegion,
        ClientIsNotEnabled,
        Fail,
    }

    public static class RegionInfo {
        public final int id;
        public final Box box;
        public final String name;
        public final UUID owner;
        public final List<UUID> members;

        public RegionInfo(int id, Box box, String name, UUID owner, List<UUID> members) {
            this.id = id;
            this.box = box;
            this.name = name;
            this.owner = owner;
            this.members = members;
        }
    }

    public RegionManager() throws SQLException, IOException {
        SetupManager.ensureValidDBPresent(MOD_NAME, DB_NAME);
        connection = DatabaseProvider.getConnection(MOD_NAME, DB_NAME, DB_USER, DB_PASS);
    }

    private boolean isMember(int regionId, String playerUUID) throws SQLException {
        final String QUERY = "SELECT FROM membership WHERE (\"regionId\" = ?) and (\"playerUUID\" = CAST(? as UNIQUEIDENTIFIER))";
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.prepareStatement(QUERY);
            statement.setInt(1, regionId);
            statement.setString(2, playerUUID);

            resultSet = statement.executeQuery();
            return resultSet.next();
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            if (statement != null) statement.close();
            if (resultSet != null) resultSet.close();
        }
        return false;
    }

    public RegionInfo getRegionInfo(BlockPos pos) throws SQLException {
        final String QUERY = "SELECT id, x1, y1, z1, x2, y2, z2, CAST(\"ownerUUID\" as char(36)), name FROM region WHERE (? between x1 and x2) and (? between y1 and y2) and (? between z1 and z2)";
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.prepareStatement(QUERY);
            statement.setInt(1, pos.getX());
            statement.setInt(2, pos.getY());
            statement.setInt(3, pos.getZ());

            resultSet = statement.executeQuery();

            if (resultSet.next()) {
                int id = resultSet.getInt(1);
                int x1 = resultSet.getInt(2);
                int y1 = resultSet.getInt(3);
                int z1 = resultSet.getInt(4);
                int x2 = resultSet.getInt(5);
                int y2 = resultSet.getInt(6);
                int z2 = resultSet.getInt(7);
                String uuid = resultSet.getString(8);
                String name = resultSet.getString(9);
                return new RegionInfo(id, new Box(x1, y1, z1, x2, y2, z2), name, UUID.fromString(uuid), null);
            } else
                return null;
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            if (resultSet != null) resultSet.close();
            if (statement != null) statement.close();
        }
        return null;
    }

    public boolean canPlayerModifyAt(PlayerEntity player, BlockPos pos) throws SQLException {
        final String QUERY = "SELECT id, CAST(\"ownerUUID\" as char(36)) FROM region WHERE (? between x1 and x2) and (? between y1 and y2) and (? between z1 and z2)";
        if (player.hasPermissionLevel(3))
            return true;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.prepareStatement(QUERY);
            statement.setInt(1, pos.getX());
            statement.setInt(2, pos.getY());
            statement.setInt(3, pos.getZ());

            resultSet = statement.executeQuery();

            if (resultSet.next()) {
                int id = resultSet.getInt(1);
                String uuid = resultSet.getString(2);
                if (player.getUuidAsString().equals(uuid))
                    return true;
                return isMember(id, player.getUuidAsString());
            } else
                return true;
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            if (resultSet != null) resultSet.close();
            if (statement != null) statement.close();
        }
        return false;
    }

    private RegionRegistrationResult isValidRegion(int x1, int y1, int z1, int x2, int y2, int z2) throws SQLException {
        final String QUERY = "SELECT id FROM region WHERE (? <= x2 and ? <= y2 and ? <= z2) and (? >= x1 and ? >= y1 and ? >= z1)";
        int x = x2 - x1;
        int y = y2 - y1;
        int z = z2 - z1;
        int volume = x * y * z;
        if (volume > MAX_VOLUME)
            return RegionRegistrationResult.TooBig;

        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.prepareStatement(QUERY);
            statement.setInt(1, x1);
            statement.setInt(2, y1);
            statement.setInt(3, z1);
            statement.setInt(4, x2);
            statement.setInt(5, y2);
            statement.setInt(6, z2);
            resultSet = statement.executeQuery();
            if (resultSet.next())
                return RegionRegistrationResult.Overlap;
            return RegionRegistrationResult.OK;
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            if (statement != null) statement.close();
            if (resultSet != null) resultSet.close();
        }
        return RegionRegistrationResult.Fail;
    }

    private void addMembership(int regionId, Set<PlayerEntity> members) throws SQLException {
        final String QUERY = "INSERT INTO membership(\"regionId\", \"playerUUID\") VALUES (?, CAST(? as UNIQUEIDENTIFIER))";
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement(QUERY);
            statement.setInt(1, regionId);
            for (PlayerEntity player : members) {
                statement.setString(2, player.getUuidAsString());
                statement.execute();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            if (statement != null) statement.close();
        }
    }

    public RegionRegistrationResult registerRegion(String name, BlockPos pos1, BlockPos pos2, PlayerEntity owner, Set<PlayerEntity> members) throws SQLException {
        final String INSERT_QUERY = "INSERT INTO region(x1, y1, z1, x2, y2, z2, \"ownerUUID\", name) VALUES (?, ?, ?, ?, ?, ?, CAST(? as UNIQUEIDENTIFIER), ?)";
        final String SELECT_QUERY = "SELECT SCOPE_IDENTITY()";

        int x1 = Math.min(pos1.getX(), pos2.getX());
        int y1 = Math.min(pos1.getY(), pos2.getY());
        int z1 = Math.min(pos1.getZ(), pos2.getZ());
        int x2 = Math.max(pos1.getX(), pos2.getX());
        int y2 = Math.max(pos1.getY(), pos2.getY());
        int z2 = Math.max(pos1.getZ(), pos2.getZ());

        RegionRegistrationResult regionRegistrationResult = isValidRegion(x1, y1, z1, x2, y2, z2);
        if (regionRegistrationResult != RegionRegistrationResult.OK)
            return regionRegistrationResult;

        String ownerUUID = "00000000-0000-0000-0000-000000000000";
        if (owner != null)
            ownerUUID = owner.getUuidAsString();

        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.prepareStatement(INSERT_QUERY);
            statement.setInt(1, x1);
            statement.setInt(2, y1);
            statement.setInt(3, z1);
            statement.setInt(4, x2);
            statement.setInt(5, y2);
            statement.setInt(6, z2);
            statement.setString(7, ownerUUID);
            statement.setString(8, name);
            statement.execute();
            statement.close();

            statement = connection.prepareStatement(SELECT_QUERY);
            resultSet = statement.executeQuery();
            int regionId = -1;
            if (resultSet.next())
                regionId = resultSet.getInt(1);
            else
                return RegionRegistrationResult.Fail;
            if (members != null)
                addMembership(regionId, members);
            return RegionRegistrationResult.OK;
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            if (statement != null) statement.close();
            if (resultSet != null) resultSet.close();
        }
        return RegionRegistrationResult.Fail;
    }

    public RegionDeletionResult deleteRegion(BlockPos pos, PlayerEntity player) throws SQLException {
        final String SELECT_QUERY = "SELECT id, CAST(\"ownerUUID\" as char(36)) FROM region WHERE (? between x1 and x2) and (? between y1 and y2) and (? between z1 and z2)";
        final String DELETE_QUERY = "DELETE FROM region WHERE id = ?";
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.prepareStatement(SELECT_QUERY);
            statement.setInt(1, pos.getX());
            statement.setInt(2, pos.getY());
            statement.setInt(3, pos.getZ());

            resultSet = statement.executeQuery();

            if (resultSet.next()) {
                int id = resultSet.getInt(1);
                String uuid = resultSet.getString(2);
                if (player.getUuidAsString().equals(uuid) || player.hasPermissionLevel(3)) {
                    resultSet.close();
                    statement.close();
                    statement = connection.prepareStatement(DELETE_QUERY);
                    statement.setInt(1, id);
                    statement.execute();
                    return RegionDeletionResult.OK;
                } else
                    return RegionDeletionResult.NotOwner;
            } else
                return RegionDeletionResult.NoRegion;
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            if (resultSet != null) resultSet.close();
            if (statement != null) statement.close();
        }
        return RegionDeletionResult.Fail;
    }

    public static void main(String[] args) throws SQLException, IOException {
        RegionManager regionManager = new RegionManager();
        Random random = new Random();
        for (int i = 0; i < 1000; i++) {
            BlockPos pos1 = new BlockPos(random.nextInt(100), random.nextInt(100), random.nextInt(100));
            BlockPos pos2 = new BlockPos(random.nextInt(100), random.nextInt(100), random.nextInt(100));
            regionManager.registerRegion("Region", pos1, pos2, null, null);
        }
    }
}
