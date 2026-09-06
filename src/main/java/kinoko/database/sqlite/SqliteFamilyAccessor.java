package kinoko.database.sqlite;

import kinoko.database.FamilyAccessor;
import kinoko.world.user.family.Family;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static kinoko.database.schema.FamilySchema.*;

public final class SqliteFamilyAccessor extends SqliteAccessor implements FamilyAccessor {
    private static final String tableName = "family_table";

    public SqliteFamilyAccessor(Connection connection) {
        super(connection);
    }

    private Family loadFamily(ResultSet rs) throws SQLException {
        final Family family = new Family(rs.getInt(CHARACTER_ID));
        family.setParentId(rs.getInt(PARENT_ID));
        family.setFamousPoint(rs.getInt(FAMOUS_POINT));
        family.setTotalFamousPoint(rs.getInt(TOTAL_FAMOUS_POINT));
        family.setTodayParentPoint(rs.getInt(TODAY_PARENT_POINT));
        family.setTodayGrandparentPoint(rs.getInt(TODAY_GRANDPARENT_POINT));
        family.setPointResetDay(rs.getLong(POINT_RESET_DATE));
        family.setPrecept(rs.getString(PRECEPT));
        Family.decodePrivilegeUse(rs.getString(PRIVILEGE_USE), family.getPrivilegeUse());
        return family;
    }

    @Override
    public Optional<Family> getFamilyByCharacterId(int characterId) {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM " + tableName + " WHERE " + CHARACTER_ID + " = ?"
        )) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(loadFamily(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    @Override
    public List<Family> getFamiliesByParentId(int parentId) {
        final List<Family> families = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM " + tableName + " WHERE " + PARENT_ID + " = ?"
        )) {
            ps.setInt(1, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    families.add(loadFamily(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return families;
    }

    @Override
    public boolean saveFamily(Family family, boolean force) {
        final String onConflict = force ? "DO UPDATE SET " +
                PARENT_ID + " = excluded." + PARENT_ID + ", " +
                FAMOUS_POINT + " = excluded." + FAMOUS_POINT + ", " +
                TOTAL_FAMOUS_POINT + " = excluded." + TOTAL_FAMOUS_POINT + ", " +
                TODAY_PARENT_POINT + " = excluded." + TODAY_PARENT_POINT + ", " +
                TODAY_GRANDPARENT_POINT + " = excluded." + TODAY_GRANDPARENT_POINT + ", " +
                POINT_RESET_DATE + " = excluded." + POINT_RESET_DATE + ", " +
                PRECEPT + " = excluded." + PRECEPT + ", " +
                PRIVILEGE_USE + " = excluded." + PRIVILEGE_USE
                : "DO NOTHING";
        try (PreparedStatement ps = getConnection().prepareStatement(
                "INSERT INTO " + tableName + " (" +
                        CHARACTER_ID + ", " +
                        PARENT_ID + ", " +
                        FAMOUS_POINT + ", " +
                        TOTAL_FAMOUS_POINT + ", " +
                        TODAY_PARENT_POINT + ", " +
                        TODAY_GRANDPARENT_POINT + ", " +
                        POINT_RESET_DATE + ", " +
                        PRECEPT + ", " +
                        PRIVILEGE_USE + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(" + CHARACTER_ID + ")" + onConflict
        )) {
            ps.setInt(1, family.getCharacterId());
            ps.setInt(2, family.getParentId());
            ps.setInt(3, family.getFamousPoint());
            ps.setInt(4, family.getTotalFamousPoint());
            ps.setInt(5, family.getTodayParentPoint());
            ps.setInt(6, family.getTodayGrandparentPoint());
            ps.setLong(7, family.getPointResetDay());
            ps.setString(8, family.getPrecept());
            ps.setString(9, Family.encodePrivilegeUse(family.getPrivilegeUse()));
            if (ps.executeUpdate() > 0) {
                return true;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static void createTable(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                            CHARACTER_ID + " INTEGER PRIMARY KEY, " +
                            PARENT_ID + " INTEGER NOT NULL DEFAULT 0, " +
                            FAMOUS_POINT + " INTEGER NOT NULL DEFAULT 0, " +
                            TOTAL_FAMOUS_POINT + " INTEGER NOT NULL DEFAULT 0, " +
                            TODAY_PARENT_POINT + " INTEGER NOT NULL DEFAULT 0, " +
                            TODAY_GRANDPARENT_POINT + " INTEGER NOT NULL DEFAULT 0, " +
                            POINT_RESET_DATE + " INTEGER NOT NULL DEFAULT 0, " +
                            PRECEPT + " TEXT NOT NULL DEFAULT '', " +
                            PRIVILEGE_USE + " TEXT NOT NULL DEFAULT ''" +
                    ")"
            );

            s.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_family_parent_id ON " +
                            tableName + "(" + PARENT_ID + ")"
            );
        }
    }
}
