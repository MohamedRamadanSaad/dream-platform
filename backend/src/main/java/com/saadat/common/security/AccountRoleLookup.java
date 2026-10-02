package com.saadat.common.security;

import com.saadat.common.domain.Role;
import java.util.Optional;
import java.util.UUID;

/**
 * The account's CURRENT role from the database. The access token only proves who the caller is; what the caller
 * may do is decided by the stored role on every request, so a revoked role or a deleted account takes effect
 * immediately instead of when the token expires.
 */
@FunctionalInterface
public interface AccountRoleLookup {

    /** Role of an active account; empty when the account does not exist or was deleted. */
    Optional<Role> currentRole(UUID userId);
}
