package com.saadat.pricing.service;

import com.saadat.common.domain.Currency;
import com.saadat.pricing.domain.Country;
import com.saadat.pricing.domain.CountryGroup;
import com.saadat.pricing.domain.DreamPackage;
import com.saadat.pricing.domain.PriceRule;
import com.saadat.pricing.repo.CountryGroupRepository;
import com.saadat.pricing.repo.CountryRepository;
import com.saadat.pricing.repo.DreamPackageRepository;
import com.saadat.pricing.repo.PriceRuleRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which countries cannot buy which active package (GET /admin/pricing/gaps): the same {@link PriceResolver#pick}
 * the catalog uses finds no price in the country's currency, so the package is hidden there. Shown to the interpreter
 * as a red warning on the prices page.
 */
@Service
@RequiredArgsConstructor
public class PricingCoverage {

    private final CountryRepository countryRepository;
    private final CountryGroupRepository groupRepository;
    private final DreamPackageRepository packageRepository;
    private final PriceRuleRepository priceRuleRepository;

    public record PackageRef(UUID id, String nameAr, String nameEn) {
    }

    /** One country with at least one active package it cannot buy. {@code allMissing}: it cannot buy anything. */
    public record Gap(String countryCode, String nameAr, String nameEn, Currency currency, UUID groupId,
                      String groupName, boolean allMissing, List<PackageRef> missing) {
    }

    @Transactional(readOnly = true)
    public List<Gap> gaps() {
        List<DreamPackage> packages = packageRepository.findByActiveTrueOrderBySortOrderAsc();
        if (packages.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<PriceRule>> rulesByPackage = priceRuleRepository.findAll().stream()
                .collect(Collectors.groupingBy(PriceRule::getPackageId));
        Map<UUID, String> groupNames = groupRepository.findAll().stream()
                .collect(Collectors.toMap(CountryGroup::getId, CountryGroup::getName, (a, b) -> a));
        Map<UUID, PackageRef> refs = packages.stream()
                .collect(Collectors.toMap(DreamPackage::getId,
                        p -> new PackageRef(p.getId(), p.getNameAr(), p.getNameEn()), (a, b) -> a));

        List<Gap> gaps = new ArrayList<>();
        for (Country c : countryRepository.findAllByOrderByNameEnAsc()) {
            List<PackageRef> missing = packages.stream()
                    .filter(p -> PriceResolver.pick(c, rulesByPackage.getOrDefault(p.getId(), List.of())).isEmpty())
                    .map(p -> refs.get(p.getId()))
                    .toList();
            if (!missing.isEmpty()) {
                UUID groupId = c.getGroupId();
                gaps.add(new Gap(c.getCode(), c.getNameAr(), c.getNameEn(), c.getDefaultCurrency(), groupId,
                        groupId == null ? null : groupNames.get(groupId), missing.size() == packages.size(),
                        missing));
            }
        }
        return gaps;
    }
}
