package kinoko.database;

import kinoko.world.user.family.Family;

import java.util.List;
import java.util.Optional;

public interface FamilyAccessor {
    Optional<Family> getFamilyByCharacterId(int characterId);

    List<Family> getFamiliesByParentId(int parentId);

    boolean saveFamily(Family family, boolean force);
}
