package com.saadat.common.security;

import com.saadat.common.domain.Role;
import java.util.Optional;
import java.util.UUID;

/**
 * The account's CURRENT role from the database. The access token only proves who the caller is; what the caller
 * may do is decided by the stored role on every request, so a revoked role, a deleted account or a signed-out
 * device takes effect immediately instead of when the token expires.
 */
@FunctionalInterface
public interface AccountRoleLookup {

    /**
     * Role of an active account, read in one query; empty when the account does not exist or was deleted, or when
     * {@code sessionId} (the token's {@code sid} = refresh-token family) is given and that family has no active
     * token left (signed out from the devices list, logged out, reused or expired). A null {@code sessionId}
     * (access tokens issued before devices existed) skips the session check.
     */
    Optional<Role> currentRole(UUID userId, UUID sessionId);
}
