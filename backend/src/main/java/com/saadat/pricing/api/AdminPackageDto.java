package com.saadat.pricing.api;

import com.saadat.pricing.domain.DreamPackage;
import java.util.UUID;

/** types.ts AdminPackage. */
public record AdminPackageDto(
        UUID id,
        String nameAr,
        String nameEn,
        String descriptionAr,
        String descriptionEn,
        int credits,
        String badge,
        int sortOrder,
        boolean active,
        Integer validityMonths) {

    public static AdminPackageDto from(DreamPackage p) {
        return new AdminPackageDto(p.getId(), p.getNameAr(), p.getNameEn(), p.getDescriptionAr(), p.getDescriptionEn(),
                p.getCredits(), p.getBadge(), p.getSortOrder(), p.isActive(), p.getValidityMonths());
    }

    /** Defaults for a new package (POST body is merged over these). */
    public static AdminPackageDto blank() {
        return new AdminPackageDto(null, "", "", "", "", 1, null, 0, true, null);
    }
}
