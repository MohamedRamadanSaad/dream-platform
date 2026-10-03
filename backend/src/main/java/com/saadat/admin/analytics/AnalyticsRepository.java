package com.saadat.admin.analytics;

import com.saadat.users.domain.User;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Read-only analytics queries (native PostgreSQL). Rows are {@code Object[]}; convert with {@link Rows}.
 */
public interface AnalyticsRepository extends Repository<User, UUID> {

    /**
     * One row per active USER: [0] id, [1] name, [2] email, [3] country_code, [4] visits, [5] dreams
     * (non-draft), [6] drafts, [7] avg_rating (null when none), [8] last_seen, [9] created_at.
     */
    @Query(value = """
            select u.id,
                   u.name,
                   u.email,
                   u.country_code,
                   (select count(*) from user_sessions s where s.user_id = u.id) as visits,
                   (select count(*) from dreams d where d.user_id = u.id and not d.deleted
                                                  and d.status <> 'DRAFT') as dreams,
                   (select count(*) from dreams d where d.user_id = u.id and not d.deleted
                                                  and d.status = 'DRAFT') as drafts,
                   (select avg(t.rating) from testimonials t where t.user_id = u.id) as avg_rating,
                   coalesce((select max(s.started_at) from user_sessions s where s.user_id = u.id),
                            u.last_login_at, u.created_at) as last_seen,
                   u.created_at
            from users u
            where u.deleted_at is null and u.role = 'USER'
            """, nativeQuery = true)
    List<Object[]> userRows();

    /** Same columns as {@link #userRows()} for one user (any role). */
    @Query(value = """
            select u.id,
                   u.name,
                   u.email,
                   u.country_code,
                   (select count(*) from user_sessions s where s.user_id = u.id) as visits,
                   (select count(*) from dreams d where d.user_id = u.id and not d.deleted
                                                  and d.status <> 'DRAFT') as dreams,
                   (select count(*) from dreams d where d.user_id = u.id and not d.deleted
                                                  and d.status = 'DRAFT') as drafts,
                   (select avg(t.rating) from testimonials t where t.user_id = u.id) as avg_rating,
                   coalesce((select max(s.started_at) from user_sessions s where s.user_id = u.id),
                            u.last_login_at, u.created_at) as last_seen,
                   u.created_at
            from users u
            where u.id = :userId
            """, nativeQuery = true)
    List<Object[]> userRow(@org.springframework.data.repository.query.Param("userId") UUID userId);
}
