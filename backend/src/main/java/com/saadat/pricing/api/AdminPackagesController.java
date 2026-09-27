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

/** /admin/packages — CRUD (DELETE is a soft delete: active=false). ROLE_INTERPRETER via SecurityConfig. */
@RestController
@RequiredArgsConstructor
public class AdminPackagesController {

    private final PricingAdminService service;
    private final JsonMerge jsonMerge;

    @GetMapping(ApiPaths.Admin.PACKAGES)
    public List<AdminPackageDto> list() {
        return service.packages();
    }

    @PostMapping(ApiPaths.Admin.PACKAGES)
    public ResponseEntity<AdminPackageDto> create(@RequestBody JsonNode body) {
        AdminPackageDto dto = jsonMerge.merge(AdminPackageDto.blank(), body, AdminPackageDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPackage(dto, AuthPrincipal.current().userId()));
    }

    @PutMapping(ApiPaths.Admin.PACKAGE)
    public AdminPackageDto update(@PathVariable UUID id, @RequestBody JsonNode body) {
        AdminPackageDto dto = jsonMerge.merge(service.packageDto(id), body, AdminPackageDto.class);
        return service.updatePackage(id, dto, AuthPrincipal.current().userId());
    }

    @DeleteMapping(ApiPaths.Admin.PACKAGE)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.deactivatePackage(id, AuthPrincipal.current().userId());
        return ResponseEntity.noContent().build();
    }
}
