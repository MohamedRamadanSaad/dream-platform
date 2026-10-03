package com.saadat.pricing.service;

import com.saadat.common.audit.AuditService;
import com.saadat.common.domain.Continent;
import com.saadat.common.domain.PriceScope;
import com.saadat.common.domain.PromotionType;
import com.saadat.common.error.ConflictException;
import com.saadat.common.error.NotFoundException;
import com.saadat.common.error.ValidationException;
import com.saadat.pricing.api.AdminPackageDto;
import com.saadat.pricing.api.CountryDto;
import com.saadat.pricing.api.CountryGroupDto;
import com.saadat.pricing.api.CouponDto;
import com.saadat.pricing.api.PriceRuleDto;
import com.saadat.pricing.api.PromotionDto;
import com.saadat.pricing.domain.Country;
import com.saadat.pricing.domain.CountryGroup;
import com.saadat.pricing.domain.Coupon;
import com.saadat.pricing.domain.DreamPackage;
import com.saadat.pricing.domain.PriceRule;
import com.saadat.pricing.domain.Promotion;
import com.saadat.pricing.repo.CountryGroupRepository;
import com.saadat.pricing.repo.CountryRepository;
import com.saadat.pricing.repo.CouponRepository;
import com.saadat.pricing.repo.DreamPackageRepository;
import com.saadat.pricing.repo.PriceRuleRepository;
import com.saadat.pricing.repo.PromotionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interpreter CRUD for packages, countries/groups, price rules, promotions and coupons.
 * Every mutation is audited (contract rule 8) inside the same transaction.
 */
@Service
@RequiredArgsConstructor
public class PricingAdminService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final DreamPackageRepository packageRepository;
    private final CountryRepository countryRepository;
    private final CountryGroupRepository groupRepository;
    private final PriceRuleRepository priceRuleRepository;
    private final PromotionRepository promotionRepository;
    private final CouponRepository couponRepository;
    private final AuditService auditService;
    private final Clock clock;

    // ================================================================== packages

    @Transactional(readOnly = true)
    public List<AdminPackageDto> packages() {
        return packageRepository.findAllByOrderBySortOrderAsc().stream().map(AdminPackageDto::from).toList();
    }

    @Transactional(readOnly = true)
    public AdminPackageDto packageDto(UUID id) {
        return AdminPackageDto.from(findPackage(id));
    }

    @Transactional
    public AdminPackageDto createPackage(AdminPackageDto dto, UUID actor) {
        validatePackage(dto);
        DreamPackage p = new DreamPackage();
        p.setCreatedAt(clock.instant());
        applyPackage(p, dto);
        packageRepository.save(p);
        AdminPackageDto after = AdminPackageDto.from(p);
        auditService.record(actor, "PACKAGE_CREATE", "packages", p.getId().toString(), null, after);
        return after;
    }

    @Transactional
    public AdminPackageDto updatePackage(UUID id, AdminPackageDto dto, UUID actor) {
        validatePackage(dto);
        DreamPackage p = findPackage(id);
        AdminPackageDto before = AdminPackageDto.from(p);
        applyPackage(p, dto);
        packageRepository.save(p);
        AdminPackageDto after = AdminPackageDto.from(p);
        auditService.record(actor, "PACKAGE_UPDATE", "packages", id.toString(), before, after);
        return after;
    }

    /** Soft delete: {@code active = false} (orders keep referencing the package). */
    @Transactional
    public void deactivatePackage(UUID id, UUID actor) {
        DreamPackage p = findPackage(id);
        AdminPackageDto before = AdminPackageDto.from(p);
        p.setActive(false);
        packageRepository.save(p);
        auditService.record(actor, "PACKAGE_DEACTIVATE", "packages", id.toString(), before, AdminPackageDto.from(p));
    }

    private void validatePackage(AdminPackageDto d) {
        if (isBlank(d.nameAr()) || isBlank(d.nameEn())) {
            throw new ValidationException("Package names are required", "INVALID_PACKAGE");
        }
        if (d.credits() < 1) {
            throw new ValidationException("Credits must be at least 1", "INVALID_PACKAGE");
        }
        if (d.validityMonths() != null && d.validityMonths() < 1) {
            throw new ValidationException("Validity must be positive", "INVALID_PACKAGE");
        }
    }

    private static void applyPackage(DreamPackage p, AdminPackageDto d) {
        p.setNameAr(d.nameAr().trim());
        p.setNameEn(d.nameEn().trim());
        p.setDescriptionAr(d.descriptionAr() == null ? "" : d.descriptionAr());
        p.setDescriptionEn(d.descriptionEn() == null ? "" : d.descriptionEn());
        p.setCredits(d.credits());
        p.setBadge(isBlank(d.badge()) ? null : d.badge().trim());
        p.setSortOrder(d.sortOrder());
        p.setActive(d.active());
        p.setValidityMonths(d.validityMonths());
    }

    private DreamPackage findPackage(UUID id) {
        return packageRepository.findById(id).orElseThrow(() -> NotFoundException.of("Package", id));
    }

    // ================================================================== countries & groups

    @Transactional(readOnly = true)
    public List<CountryDto> countries() {
        return countryRepository.findAllByOrderByNameEnAsc().stream().map(CountryDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CountryGroupDto> groups() {
        Map<UUID, List<String>> codesByGroup = new HashMap<>();
        for (Country c : countryRepository.findAllByOrderByNameEnAsc()) {
            if (c.getGroupId() != null) {
                codesByGroup.computeIfAbsent(c.getGroupId(), k -> new ArrayList<>()).add(c.getCode());
            }
        }
        return groupRepository.findAllByOrderByNameAsc().stream()
                .map(g -> new CountryGroupDto(g.getId(), g.getName(), codesByGroup.getOrDefault(g.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CountryGroupDto group(UUID id) {
        CountryGroup g = findGroup(id);
        List<String> codes = countryRepository.findByGroupId(id).stream().map(Country::getCode).sorted().toList();
        return new CountryGroupDto(g.getId(), g.getName(), codes);
    }

    @Transactional
    public CountryGroupDto createGroup(CountryGroupDto dto, UUID actor) {
        if (isBlank(dto.name())) {
            throw new ValidationException("Group name is required", "INVALID_GROUP");
        }
        List<String> codes = validCountryCodes(dto.countryCodes());
        CountryGroup g = new CountryGroup();
        g.setName(dto.name().trim());
        g.setCreatedAt(clock.instant());
        groupRepository.save(g);
        if (!codes.isEmpty()) {
            // a country belongs to one group: assigning moves it out of its previous group
            countryRepository.assignGroup(g.getId(), codes);
        }
        CountryGroupDto after = new CountryGroupDto(g.getId(), g.getName(), codes);
        auditService.record(actor, "COUNTRY_GROUP_CREATE", "country_groups", g.getId().toString(), null, after);
        return after;
    }

    @Transactional
    public CountryGroupDto updateGroup(UUID id, CountryGroupDto dto, UUID actor) {
        if (isBlank(dto.name())) {
            throw new ValidationException("Group name is required", "INVALID_GROUP");
        }
        CountryGroup g = findGroup(id);
        List<String> beforeCodes = countryRepository.findByGroupId(id).stream().map(Country::getCode).sorted().toList();
        CountryGroupDto before = new CountryGroupDto(g.getId(), g.getName(), beforeCodes);
        List<String> codes = validCountryCodes(dto.countryCodes());
        g.setName(dto.name().trim());
        groupRepository.save(g);
        countryRepository.clearGroup(id);
        if (!codes.isEmpty()) {
            countryRepository.assignGroup(id, codes);
        }
        CountryGroupDto after = new CountryGroupDto(g.getId(), g.getName(), codes);
        auditService.record(actor, "COUNTRY_GROUP_UPDATE", "country_groups", id.toString(), before, after);
        return after;
    }

    /**
     * Soft-deletes the group, detaches its countries and soft-deletes its GROUP price rules: done here because the
     * database's ON DELETE SET NULL / CASCADE no longer fires (rows are only flagged {@code deleted = true}).
     */
    @Transactional
    public void deleteGroup(UUID id, UUID actor) {
        CountryGroup g = findGroup(id);
        List<String> codes = countryRepository.findByGroupId(id).stream().map(Country::getCode).sorted().toList();
        CountryGroupDto before = new CountryGroupDto(g.getId(), g.getName(), codes);
        countryRepository.clearGroup(id);
        List<PriceRule> rules = priceRuleRepository.findByScopeAndScopeId(PriceScope.GROUP, id.toString());
        priceRuleRepository.deleteAll(rules);
        groupRepository.delete(g);
        auditService.record(actor, "COUNTRY_GROUP_DELETE", "country_groups", id.toString(), before, null);
    }

    private List<String> validCountryCodes(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String c : requested) {
            if (!isBlank(c)) {
                codes.add(c.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (codes.isEmpty()) {
            return List.of();
        }
        List<Country> found = countryRepository.findByCodeIn(codes);
        if (found.size() != codes.size()) {
            throw new ValidationException("Unknown country code in group", "UNKNOWN_COUNTRY");
        }
        return codes.stream().sorted().toList();
    }

    private CountryGroup findGroup(UUID id) {
        return groupRepository.findById(id).orElseThrow(() -> NotFoundException.of("Country group", id));
    }

    // ================================================================== price rules

    @Transactional(readOnly = true)
    public List<PriceRuleDto> priceRules() {
        return priceRuleRepository.findAllByOrderByScopeAscScopeIdAsc().stream().map(PriceRuleDto::from).toList();
    }

    @Transactional(readOnly = true)
    public PriceRuleDto priceRule(UUID id) {
        return PriceRuleDto.from(findRule(id));
    }

    /** Upsert on (scope, scopeId, packageId). */
    @Transactional
    public UpsertResult<PriceRuleDto> upsertPriceRule(PriceRuleDto dto, UUID actor) {
        PriceRuleDto d = normalizeRule(dto);
        Optional<PriceRule> existing = findRuleByKey(d.scope(), d.scopeId(), d.packageId());
        PriceRule rule = existing.orElseGet(PriceRule::new);
        PriceRuleDto before = existing.map(PriceRuleDto::from).orElse(null);
        applyRule(rule, d);
        priceRuleRepository.save(rule);
        PriceRuleDto after = PriceRuleDto.from(rule);
        auditService.record(actor, "PRICE_RULE_UPSERT", "price_rules", rule.getId().toString(), before, after);
        return new UpsertResult<>(after, existing.isEmpty());
    }

    @Transactional
    public PriceRuleDto updatePriceRule(UUID id, PriceRuleDto dto, UUID actor) {
        PriceRule rule = findRule(id);
        PriceRuleDto before = PriceRuleDto.from(rule);
        PriceRuleDto d = normalizeRule(dto);
        Optional<PriceRule> clash = findRuleByKey(d.scope(), d.scopeId(), d.packageId());
        if (clash.isPresent() && !clash.get().getId().equals(id)) {
            throw new ConflictException("A price rule already exists for this scope and package");
        }
        applyRule(rule, d);
        priceRuleRepository.save(rule);
        PriceRuleDto after = PriceRuleDto.from(rule);
        auditService.record(actor, "PRICE_RULE_UPDATE", "price_rules", id.toString(), before, after);
        return after;
    }

    @Transactional
    public void deletePriceRule(UUID id, UUID actor) {
        PriceRule rule = findRule(id);
        PriceRuleDto before = PriceRuleDto.from(rule);
        priceRuleRepository.delete(rule);
        auditService.record(actor, "PRICE_RULE_DELETE", "price_rules", id.toString(), before, null);
    }

    private PriceRuleDto normalizeRule(PriceRuleDto d) {
        if (d.scope() == null || d.packageId() == null || d.currency() == null || d.price() == null) {
            throw new ValidationException("scope, packageId, price and currency are required", "INVALID_PRICE_RULE");
        }
        if (d.price().signum() < 0) {
            throw new ValidationException("Price must not be negative", "INVALID_PRICE_RULE");
        }
        findPackage(d.packageId());
        String scopeId = normalizeScopeId(d.scope(), d.scopeId());
        return new PriceRuleDto(d.id(), d.scope(), scopeId, d.packageId(), d.price(), d.currency());
    }

    private Optional<PriceRule> findRuleByKey(PriceScope scope, String scopeId, UUID packageId) {
        return scopeId == null
                ? priceRuleRepository.findByScopeAndScopeIdIsNullAndPackageId(scope, packageId)
                : priceRuleRepository.findByScopeAndScopeIdAndPackageId(scope, scopeId, packageId);
    }

    private static void applyRule(PriceRule rule, PriceRuleDto d) {
        rule.setScope(d.scope());
        rule.setScopeId(d.scopeId());
        rule.setPackageId(d.packageId());
        rule.setPrice(d.price());
        rule.setCurrency(d.currency());
    }

    private PriceRule findRule(UUID id) {
        return priceRuleRepository.findById(id).orElseThrow(() -> NotFoundException.of("Price rule", id));
    }

    /** GLOBAL → null; COUNTRY → existing ISO code; GROUP → existing group id; CONTINENT → continent code. */
    private String normalizeScopeId(PriceScope scope, String raw) {
        if (scope == PriceScope.GLOBAL) {
            return null;
        }
        if (isBlank(raw)) {
            throw new ValidationException("scopeId is required for scope " + scope, "INVALID_SCOPE");
        }
        String v = raw.trim();
        switch (scope) {
            case COUNTRY -> {
                String code = v.toUpperCase(Locale.ROOT);
                if (!countryRepository.existsById(code)) {
                    throw new ValidationException("Unknown country: " + code, "INVALID_SCOPE");
                }
                return code;
            }
            case GROUP -> {
                UUID groupId;
                try {
                    groupId = UUID.fromString(v);
                } catch (IllegalArgumentException e) {
                    throw new ValidationException("Invalid group id", "INVALID_SCOPE");
                }
                if (!groupRepository.existsById(groupId)) {
                    throw new ValidationException("Unknown group: " + v, "INVALID_SCOPE");
                }
                return groupId.toString();
            }
            case CONTINENT -> {
                try {
                    return Continent.valueOf(v.toUpperCase(Locale.ROOT)).name();
                } catch (IllegalArgumentException e) {
                    throw new ValidationException("Unknown continent: " + v, "INVALID_SCOPE");
                }
            }
            default -> {
                return null;
            }
        }
    }

    // ================================================================== promotions

    @Transactional(readOnly = true)
    public List<PromotionDto> promotions() {
        return promotionRepository.findAllByOrderByStartsAtDesc().stream().map(PromotionDto::from).toList();
    }

    @Transactional(readOnly = true)
    public PromotionDto promotion(UUID id) {
        return PromotionDto.from(findPromotion(id));
    }

    @Transactional
    public PromotionDto createPromotion(PromotionDto dto, UUID actor) {
        PromotionDto d = normalizePromotion(dto);
        Promotion p = new Promotion();
        p.setCreatedAt(clock.instant());
        p.setUsedCount(0);
        applyPromotion(p, d);
        promotionRepository.save(p);
        PromotionDto after = PromotionDto.from(p);
        auditService.record(actor, "PROMOTION_CREATE", "promotions", p.getId().toString(), null, after);
        return after;
    }

    @Transactional
    public PromotionDto updatePromotion(UUID id, PromotionDto dto, UUID actor) {
        Promotion p = findPromotion(id);
        PromotionDto before = PromotionDto.from(p);
        applyPromotion(p, normalizePromotion(dto));
        promotionRepository.save(p);
        PromotionDto after = PromotionDto.from(p);
        auditService.record(actor, "PROMOTION_UPDATE", "promotions", id.toString(), before, after);
        return after;
    }

    @Transactional
    public void deletePromotion(UUID id, UUID actor) {
        Promotion p = findPromotion(id);
        PromotionDto before = PromotionDto.from(p);
        promotionRepository.delete(p);
        auditService.record(actor, "PROMOTION_DELETE", "promotions", id.toString(), before, null);
    }

    private PromotionDto normalizePromotion(PromotionDto d) {
        if (isBlank(d.name()) || d.type() == null || d.value() == null || d.startsAt() == null || d.endsAt() == null) {
            throw new ValidationException("name, type, value, startsAt and endsAt are required", "INVALID_PROMOTION");
        }
        if (!d.endsAt().isAfter(d.startsAt())) {
            throw new ValidationException("endsAt must be after startsAt", "INVALID_PROMOTION");
        }
        if (d.value().signum() < 0 || (d.type() == PromotionType.PERCENT && d.value().compareTo(HUNDRED) > 0)) {
            throw new ValidationException("Invalid promotion value", "INVALID_PROMOTION");
        }
        if (d.maxUses() != null && d.maxUses() < 0) {
            throw new ValidationException("maxUses must not be negative", "INVALID_PROMOTION");
        }
        List<UUID> packageIds = d.packageIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(d.packageIds()));
        for (UUID pid : packageIds) {
            findPackage(pid);
        }
        PriceScope scope = d.scope() == null ? PriceScope.GLOBAL : d.scope();
        String scopeId = normalizeScopeId(scope, d.scopeId());
        return new PromotionDto(d.id(), d.name().trim(), packageIds, d.type(), d.value(), d.startsAt(), d.endsAt(),
                d.maxUses(), d.usedCount(), scope, scopeId, d.active());
    }

    private static void applyPromotion(Promotion p, PromotionDto d) {
        p.setName(d.name());
        p.setPackageIds(new ArrayList<>(d.packageIds()));
        p.setType(d.type());
        p.setValue(d.value());
        p.setStartsAt(d.startsAt());
        p.setEndsAt(d.endsAt());
        p.setMaxUses(d.maxUses());
        p.setScope(d.scope());
        p.setScopeId(d.scopeId());
        p.setActive(d.active());
        // usedCount is server-managed: never taken from the request
    }

    private Promotion findPromotion(UUID id) {
        return promotionRepository.findById(id).orElseThrow(() -> NotFoundException.of("Promotion", id));
    }

    // ================================================================== coupons

    @Transactional(readOnly = true)
    public List<CouponDto> coupons() {
        return couponRepository.findAllByOrderByCodeAsc().stream().map(CouponDto::from).toList();
    }

    @Transactional(readOnly = true)
    public CouponDto coupon(UUID id) {
        return CouponDto.from(findCoupon(id));
    }

    @Transactional
    public CouponDto createCoupon(CouponDto dto, UUID actor) {
        validateCoupon(dto);
        Coupon c = new Coupon();
        c.setCreatedAt(clock.instant());
        c.setUsedCount(0);
        applyCoupon(c, dto);
        if (couponRepository.existsByCode(c.getCode())) {
            throw new ConflictException("Coupon code already exists", "COUPON_EXISTS");
        }
        couponRepository.save(c);
        CouponDto after = CouponDto.from(c);
        auditService.record(actor, "COUPON_CREATE", "coupons", c.getId().toString(), null, after);
        return after;
    }

    @Transactional
    public CouponDto updateCoupon(UUID id, CouponDto dto, UUID actor) {
        validateCoupon(dto);
        Coupon c = findCoupon(id);
        CouponDto before = CouponDto.from(c);
        String oldCode = c.getCode();
        applyCoupon(c, dto);
        if (!c.getCode().equals(oldCode)) {
            Optional<Coupon> clash = couponRepository.findByCode(c.getCode());
            if (clash.isPresent() && !clash.get().getId().equals(id)) {
                throw new ConflictException("Coupon code already exists", "COUPON_EXISTS");
            }
        }
        couponRepository.save(c);
        CouponDto after = CouponDto.from(c);
        auditService.record(actor, "COUPON_UPDATE", "coupons", id.toString(), before, after);
        return after;
    }

    @Transactional
    public void deleteCoupon(UUID id, UUID actor) {
        Coupon c = findCoupon(id);
        CouponDto before = CouponDto.from(c);
        couponRepository.delete(c);
        auditService.record(actor, "COUPON_DELETE", "coupons", id.toString(), before, null);
    }

    private static void validateCoupon(CouponDto d) {
        if (isBlank(d.code()) || d.type() == null || d.value() == null) {
            throw new ValidationException("code, type and value are required", "INVALID_COUPON_DEFINITION");
        }
        if (d.value().signum() < 0 || (d.type() == com.saadat.common.domain.CouponType.PERCENT
                && d.value().compareTo(HUNDRED) > 0)) {
            throw new ValidationException("Invalid coupon value", "INVALID_COUPON_DEFINITION");
        }
        if (d.perUserLimit() < 1 || (d.maxUses() != null && d.maxUses() < 0)) {
            throw new ValidationException("Invalid coupon limits", "INVALID_COUPON_DEFINITION");
        }
    }

    private static void applyCoupon(Coupon c, CouponDto d) {
        c.setCode(d.code());
        c.setType(d.type());
        c.setValue(d.value());
        c.setMaxUses(d.maxUses());
        c.setPerUserLimit(d.perUserLimit());
        c.setExpiresAt(d.expiresAt());
        c.setActive(d.active());
    }

    private Coupon findCoupon(UUID id) {
        return couponRepository.findById(id).orElseThrow(() -> NotFoundException.of("Coupon", id));
    }

    // ================================================================== helpers

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Result of an upsert: the row and whether it was created (→ 201) or updated (→ 200). */
    public record UpsertResult<T>(T value, boolean created) {
    }
}
