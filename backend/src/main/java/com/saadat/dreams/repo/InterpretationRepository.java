package com.saadat.dreams.repo;

import com.saadat.dreams.domain.Interpretation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterpretationRepository extends JpaRepository<Interpretation, UUID> {

    Optional<Interpretation> findByDreamId(UUID dreamId);

    boolean existsByDreamId(UUID dreamId);
}
