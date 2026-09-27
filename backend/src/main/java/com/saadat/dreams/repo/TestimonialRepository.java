package com.saadat.dreams.repo;

import com.saadat.dreams.domain.Testimonial;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestimonialRepository extends JpaRepository<Testimonial, UUID> {

    /** Admin filter / public list (pass a Pageable sorted by createdAt desc). */
    Page<Testimonial> findByApproved(boolean approved, Pageable pageable);

    List<Testimonial> findByApprovedOrderByCreatedAtDesc(boolean approved);

    Page<Testimonial> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<Testimonial> findByDreamId(UUID dreamId);

    boolean existsByDreamId(UUID dreamId);

    List<Testimonial> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Null when the user has no testimonials. */
    @Query("select avg(t.rating) from Testimonial t where t.userId = :userId")
    Double averageRatingForUser(@Param("userId") UUID userId);

    /** [userId, avgRating] rows. */
    @Query("select t.userId, avg(t.rating) from Testimonial t group by t.userId")
    List<Object[]> averageRatingByUser();
}
