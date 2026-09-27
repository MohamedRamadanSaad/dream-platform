package com.saadat.users.repo;

import com.saadat.users.domain.UserNote;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNoteRepository extends JpaRepository<UserNote, UUID> {
}
