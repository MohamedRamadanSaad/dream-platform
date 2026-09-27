package com.saadat.dreams.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.api.PageResponse;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.dreams.service.AdminDreamService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Interpreter routes for dreams and testimonials (ROLE_INTERPRETER via SecurityConfig). */
@RestController
@RequiredArgsConstructor
public class AdminDreamController {

    private final AdminDreamService service;

    @GetMapping(ApiPaths.Admin.DREAMS)
    public PageResponse<AdminDreamRow> queue(@RequestParam(required = false) DreamStatus status,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return service.queue(status, page, size);
    }

    @GetMapping(ApiPaths.Admin.DREAM)
    public AdminDreamDetailDto detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping(ApiPaths.Admin.DREAM_MESSAGES)
    public ResponseEntity<DreamMessageDto> ask(@PathVariable UUID id,
                                               @Valid @RequestBody DreamRequests.MessageRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.ask(actor(), id, body.body()));
    }

    @PostMapping(ApiPaths.Admin.DREAM_INTERPRETATION)
    public ResponseEntity<Void> interpret(@PathVariable UUID id,
                                          @Valid @RequestBody DreamRequests.InterpretationRequest body) {
        service.interpret(actor(), id, body.text());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(ApiPaths.Admin.DREAM_CANCEL)
    public ResponseEntity<Void> cancel(@PathVariable UUID id,
                                       @RequestBody(required = false) DreamRequests.CancelRequest body) {
        service.cancel(actor(), id, body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping(ApiPaths.Admin.TESTIMONIALS)
    public PageResponse<AdminTestimonialRow> testimonials(@RequestParam(required = false) Boolean approved,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size) {
        return service.testimonials(approved, page, size);
    }

    @PatchMapping(ApiPaths.Admin.TESTIMONIAL)
    public ResponseEntity<Void> setTestimonial(@PathVariable UUID id,
                                               @Valid @RequestBody DreamRequests.TestimonialPatch body) {
        service.setTestimonialApproved(actor(), id, body.approved());
        return ResponseEntity.noContent().build();
    }

    private static UUID actor() {
        return AuthPrincipal.current().userId();
    }
}
