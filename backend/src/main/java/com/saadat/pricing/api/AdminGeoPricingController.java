package com.saadat.pricing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.pricing.service.PricingAdminService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** /admin/countries, /admin/country-groups, /admin/price-rules. ROLE_INTERPRETER via SecurityConfig. */
@RestController
@RequiredArgsConstructor
public class AdminGeoPricingController {

    private final PricingAdminService service;
    private final JsonMerge jsonMerge;

    // ---------------------------------------------------------------- countries

    @GetMapping(ApiPaths.Admin.COUNTRIES)
    public List<CountryDto> countries() {
        return service.countries();
    }

    // ---------------------------------------------------------------- groups

    @GetMapping(ApiPaths.Admin.COUNTRY_GROUPS)
    public List<CountryGroupDto> groups() {
        return service.groups();
    }

    @PostMapping(ApiPaths.Admin.COUNTRY_GROUPS)
    public ResponseEntity<CountryGroupDto> createGroup(@RequestBody JsonNode body) {
        CountryGroupDto dto = jsonMerge.merge(new CountryGroupDto(null, "", List.of()), body, CountryGroupDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createGroup(dto, AuthPrincipal.current().userId()));
    }

    @PutMapping(ApiPaths.Admin.COUNTRY_GROUP)
    public CountryGroupDto updateGroup(@PathVariable UUID id, @RequestBody JsonNode body) {
        CountryGroupDto dto = jsonMerge.merge(service.group(id), body, CountryGroupDto.class);
        return service.updateGroup(id, dto, AuthPrincipal.current().userId());
    }

    @DeleteMapping(ApiPaths.Admin.COUNTRY_GROUP)
    public ResponseEntity<Void> deleteGroup(@PathVariable UUID id) {
        service.deleteGroup(id, AuthPrincipal.current().userId());
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- price rules

    @GetMapping(ApiPaths.Admin.PRICE_RULES)
    public List<PriceRuleDto> priceRules() {
        return service.priceRules();
    }

    /** Upserts on (scope, scopeId, packageId): 201 when created, 200 when an existing rule was updated. */
    @PostMapping(ApiPaths.Admin.PRICE_RULES)
    public ResponseEntity<PriceRuleDto> upsertPriceRule(@RequestBody JsonNode body) {
        PriceRuleDto dto = jsonMerge.merge(new PriceRuleDto(null, null, null, null, null, null), body, PriceRuleDto.class);
        PricingAdminService.UpsertResult<PriceRuleDto> result =
                service.upsertPriceRule(dto, AuthPrincipal.current().userId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.value());
    }

    @PutMapping(ApiPaths.Admin.PRICE_RULE)
    public PriceRuleDto updatePriceRule(@PathVariable UUID id, @RequestBody JsonNode body) {
        PriceRuleDto dto = jsonMerge.merge(service.priceRule(id), body, PriceRuleDto.class);
        return service.updatePriceRule(id, dto, AuthPrincipal.current().userId());
    }

    @DeleteMapping(ApiPaths.Admin.PRICE_RULE)
    public ResponseEntity<Void> deletePriceRule(@PathVariable UUID id) {
        service.deletePriceRule(id, AuthPrincipal.current().userId());
        return ResponseEntity.noContent().build();
    }
}
