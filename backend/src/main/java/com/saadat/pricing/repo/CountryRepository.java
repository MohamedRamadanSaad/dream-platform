package com.saadat.pricing.repo;

import com.saadat.pricing.domain.Country;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CountryRepository extends JpaRepository<Country, String> {

    List<Country> findAllByOrderByNameEnAsc();

    List<Country> findByGroupId(UUID groupId);

    List<Country> findByCodeIn(Collection<String> codes);

    /** Removes every country from a group (before re-assigning or deleting the group). */
    @Modifying
    @Transactional
    @Query("update Country c set c.groupId = null where c.groupId = :groupId")
    int clearGroup(@Param("groupId") UUID groupId);

    /** Moves the given countries into a group (a country belongs to at most one group). */
    @Modifying
    @Transactional
    @Query("update Country c set c.groupId = :groupId where c.code in :codes")
    int assignGroup(@Param("groupId") UUID groupId, @Param("codes") Collection<String> codes);
}
