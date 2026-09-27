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

/** /admin/promotions and /admin/coupons CRUD. ROLE_INTERPRETER via SecurityConfig. */
@RestController
@RequiredArgsConstructor
public class AdminPromotionsController {

    private final PricingAdminService service;
    private final JsonMerge jsonMerge;

    // ---------------------------------------------------------------- promotions

    @GetMapping(ApiPaths.Admin.PROMOTIONS)
    public List<PromotionDto> promotions() {
        return service.promotions();
    }

    @PostMapping(ApiPaths.Admin.PROMOTIONS)
    public ResponseEntity<PromotionDto> createPromotion(@RequestBody JsonNode body) {
        PromotionDto dto = jsonMerge.merge(PromotionDto.blank(), body, PromotionDto.class);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createPromotion(dto, AuthPrincipal.current().userId()));
    }

    @PutMapping(ApiPaths.Admin.PROMOTION)
    public PromotionDto updatePromotion(@PathVariable UUID id, @RequestBody JsonNode body) {
        PromotionDto dto = jsonMerge.merge(service.promotion(id), body, PromotionDto.class);
        return service.updatePromotion(id, dto, AuthPrincipal.current().userId());
    }

    @DeleteMapping(ApiPaths.Admin.PROMOTION)
    public ResponseEntity<Void> deletePromotion(@PathVariable UUID id) {
        service.deletePromotion(id, AuthPrincipal.current().userId());
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- coupons

    @GetMapping(ApiPaths.Admin.COUPONS)
    public List<CouponDto> coupons() {
        return service.coupons();
    }

    @PostMapping(ApiPaths.Admin.COUPONS)
    public ResponseEntity<CouponDto> createCoupon(@RequestBody JsonNode body) {
        CouponDto dto = jsonMerge.merge(CouponDto.blank(), body, CouponDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCoupon(dto, AuthPrincipal.current().userId()));
    }

    @PutMapping(ApiPaths.Admin.COUPON)
    public CouponDto updateCoupon(@PathVariable UUID id, @RequestBody JsonNode body) {
        CouponDto dto = jsonMerge.merge(service.coupon(id), body, CouponDto.class);
        return service.updateCoupon(id, dto, AuthPrincipal.current().userId());
    }

    @DeleteMapping(ApiPaths.Admin.COUPON)
    public ResponseEntity<Void> deleteCoupon(@PathVariable UUID id) {
        service.deleteCoupon(id, AuthPrincipal.current().userId());
        return ResponseEntity.noContent().build();
    }
}
