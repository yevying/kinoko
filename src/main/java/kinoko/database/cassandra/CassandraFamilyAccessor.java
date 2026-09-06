package kinoko.database.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.querybuilder.SchemaBuilder;
import com.datastax.oss.driver.api.querybuilder.insert.Insert;
import kinoko.database.FamilyAccessor;
import kinoko.world.user.family.Family;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.*;
import static kinoko.database.schema.FamilySchema.*;

public final class CassandraFamilyAccessor extends CassandraAccessor implements FamilyAccessor {
    private static final String tableName = "family_table";

    public CassandraFamilyAccessor(CqlSession session, String keyspace) {
        super(session, keyspace);
    }

    private Family loadFamily(Row row) {
        final Family family = new Family(row.getInt(CHARACTER_ID));
        family.setParentId(row.getInt(PARENT_ID));
        family.setFamousPoint(row.getInt(FAMOUS_POINT));
        family.setTotalFamousPoint(row.getInt(TOTAL_FAMOUS_POINT));
        family.setTodayParentPoint(row.getInt(TODAY_PARENT_POINT));
        family.setTodayGrandparentPoint(row.getInt(TODAY_GRANDPARENT_POINT));
        family.setPointResetDay(row.getLong(POINT_RESET_DATE));
        family.setPrecept(row.getString(PRECEPT));
        Family.decodePrivilegeUse(row.getString(PRIVILEGE_USE), family.getPrivilegeUse());
        return family;
    }

    @Override
    public Optional<Family> getFamilyByCharacterId(int characterId) {
        final ResultSet selectResult = getSession().execute(
                selectFrom(getKeyspace(), tableName).all()
                        .whereColumn(CHARACTER_ID).isEqualTo(literal(characterId))
                        .build()
        );
        for (Row row : selectResult) {
            return Optional.of(loadFamily(row));
        }
        return Optional.empty();
    }

    @Override
    public List<Family> getFamiliesByParentId(int parentId) {
        final List<Family> families = new ArrayList<>();
        final ResultSet selectResult = getSession().execute(
                selectFrom(getKeyspace(), tableName).all()
                        .whereColumn(PARENT_ID).isEqualTo(literal(parentId))
                        .build()
        );
        for (Row row : selectResult) {
            families.add(loadFamily(row));
        }
        return families;
    }

    @Override
    public boolean saveFamily(Family family, boolean force) {
        Insert insert = insertInto(getKeyspace(), tableName)
                .value(CHARACTER_ID, literal(family.getCharacterId()))
                .value(PARENT_ID, literal(family.getParentId()))
                .value(FAMOUS_POINT, literal(family.getFamousPoint()))
                .value(TOTAL_FAMOUS_POINT, literal(family.getTotalFamousPoint()))
                .value(TODAY_PARENT_POINT, literal(family.getTodayParentPoint()))
                .value(TODAY_GRANDPARENT_POINT, literal(family.getTodayGrandparentPoint()))
                .value(POINT_RESET_DATE, literal(family.getPointResetDay()))
                .value(PRECEPT, literal(family.getPrecept()))
                .value(PRIVILEGE_USE, literal(Family.encodePrivilegeUse(family.getPrivilegeUse())));
        if (!force) {
            insert = insert.ifNotExists();
        }
        final ResultSet insertResult = getSession().execute(insert.build());
        return insertResult.wasApplied();
    }

    public static void createTable(CqlSession session, String keyspace) {
        session.execute(
                SchemaBuilder.createTable(keyspace, tableName)
                        .ifNotExists()
                        .withPartitionKey(CHARACTER_ID, DataTypes.INT)
                        .withColumn(PARENT_ID, DataTypes.INT)
                        .withColumn(FAMOUS_POINT, DataTypes.INT)
                        .withColumn(TOTAL_FAMOUS_POINT, DataTypes.INT)
                        .withColumn(TODAY_PARENT_POINT, DataTypes.INT)
                        .withColumn(TODAY_GRANDPARENT_POINT, DataTypes.INT)
                        .withColumn(POINT_RESET_DATE, DataTypes.BIGINT)
                        .withColumn(PRECEPT, DataTypes.TEXT)
                        .withColumn(PRIVILEGE_USE, DataTypes.TEXT)
                        .build()
        );
        session.execute(
                SchemaBuilder.createIndex()
                        .ifNotExists()
                        .onTable(keyspace, tableName)
                        .andColumn(PARENT_ID)
                        .build()
        );
    }
}
