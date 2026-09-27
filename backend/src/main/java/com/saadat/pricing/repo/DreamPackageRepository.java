package com.saadat.pricing.repo;

import com.saadat.pricing.domain.DreamPackage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DreamPackageRepository extends JpaRepository<DreamPackage, UUID> {

    List<DreamPackage> findByActiveTrueOrderBySortOrderAsc();

    List<DreamPackage> findAllByOrderBySortOrderAsc();
}
