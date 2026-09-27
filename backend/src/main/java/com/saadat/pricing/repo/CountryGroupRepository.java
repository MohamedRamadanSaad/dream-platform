package com.saadat.pricing.repo;

import com.saadat.pricing.domain.CountryGroup;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CountryGroupRepository extends JpaRepository<CountryGroup, UUID> {

    List<CountryGroup> findAllByOrderByNameAsc();
}
