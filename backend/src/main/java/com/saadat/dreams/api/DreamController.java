package com.saadat.dreams.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.api.PageResponse;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.dreams.service.DreamService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The dream owner's routes (/dreams/**). */
@RestController
@RequiredArgsConstructor
public class DreamController {

    private final DreamService dreamService;

    @GetMapping(ApiPaths.Dreams.ROOT)
    public PageResponse<DreamSummaryDto> list(@RequestParam(required = false) DreamStatus status,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return dreamService.list(me(), status, page, size);
    }

    @PostMapping(ApiPaths.Dreams.ROOT)
    public ResponseEntity<DreamDetailDto> create(@Valid @RequestBody DreamRequests.DraftRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dreamService.createDraft(me(), body));
    }

    @GetMapping(ApiPaths.Dreams.BY_ID)
    public DreamDetailDto get(@PathVariable UUID id) {
        return dreamService.get(me(), id);
    }

    @PutMapping(ApiPaths.Dreams.BY_ID)
    public DreamDetailDto update(@PathVariable UUID id, @Valid @RequestBody DreamRequests.DraftRequest body) {
        return dreamService.updateDraft(me(), id, body);
    }

    @DeleteMapping(ApiPaths.Dreams.BY_ID)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        dreamService.deleteDraft(me(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(ApiPaths.Dreams.SUBMIT)
    public DreamRequests.SubmitResponse submit(@Valid @RequestBody DreamRequests.SubmitRequest body) {
        return dreamService.submit(me(), body.dreamIds(), null);
    }

    @PostMapping(ApiPaths.Dreams.MESSAGES)
    public ResponseEntity<DreamMessageDto> reply(@PathVariable UUID id,
                                                 @Valid @RequestBody DreamRequests.MessageRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dreamService.reply(me(), id, body.body()));
    }

    @PostMapping(ApiPaths.Dreams.TESTIMONIAL)
    public ResponseEntity<Void> testimonial(@PathVariable UUID id,
                                            @Valid @RequestBody DreamRequests.TestimonialRequest body) {
        dreamService.testimonial(me(), id, body);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private static UUID me() {
        return AuthPrincipal.current().userId();
    }
}
