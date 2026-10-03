package com.saadat.publicapi.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Locale;
import com.saadat.config.props.AppProperties;
import com.saadat.common.domain.DreamStatus;
import com.saadat.dreams.domain.Testimonial;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.dreams.repo.TestimonialRepository;
import com.saadat.publicapi.WaitTime;
import com.saadat.publicapi.WaitTimeView;
import com.saadat.publicapi.api.PublicDtos.PublicStats;
import com.saadat.publicapi.api.PublicDtos.PushKey;
import com.saadat.publicapi.api.PublicDtos.TestimonialDto;
import com.saadat.publicapi.api.PublicDtos.TestimonialList;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** {@link ApiPaths.Public} routes owned by this package (the catalog lives in the pricing package). */
@RestController
public class PublicInfoController {

    /** How many approved testimonials the landing page receives. */
    static final int TESTIMONIALS_LIMIT = 20;
    private static final String CREATED_AT = "createdAt";
    private static final Set<String> KUNYA_PARTICLES = Set.of("أم", "ام", "أبو", "ابو", "umm", "um", "abu");

    private final WaitTimeView waitTimeView;
    private final TestimonialRepository testimonialRepository;
    private final UserRepository userRepository;
    private final SettingsService settings;
    private final AppProperties properties;
    private final DreamRepository dreamRepository;

    public PublicInfoController(WaitTimeView waitTimeView, TestimonialRepository testimonialRepository,
                            UserRepository userRepository, SettingsService settings, AppProperties properties,
                            DreamRepository dreamRepository) {
        this.dreamRepository = dreamRepository;
        this.waitTimeView = waitTimeView;
        this.testimonialRepository = testimonialRepository;
        this.userRepository = userRepository;
        this.settings = settings;
        this.properties = properties;
    }

    @GetMapping(ApiPaths.Public.WAIT_TIME)
    public WaitTime waitTime(@RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String lang) {
        return waitTimeView.current(Locale.fromTag(lang));
    }

    @GetMapping(ApiPaths.Public.TESTIMONIALS)
    public TestimonialList testimonials() {
        List<Testimonial> approved = testimonialRepository.findByApproved(true,
                PageRequest.of(0, TESTIMONIALS_LIMIT, Sort.by(Sort.Direction.DESC, CREATED_AT))).getContent();
        List<UUID> userIds = approved.stream().map(Testimonial::getUserId).distinct().toList();
        Map<UUID, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<TestimonialDto> items = new ArrayList<>();
        for (Testimonial t : approved) {
            User u = users.get(t.getUserId());
            if (u == null || u.isDeleted()) {
                continue; // never show testimonials of deleted accounts
            }
            items.add(new TestimonialDto(t.getId(), firstName(u.getName()), t.getRating(), t.getComment(),
                    t.getCreatedAt()));
        }
        return new TestimonialList(items);
    }

    @GetMapping(ApiPaths.Public.STATS)
    public PublicStats stats() {
        return new PublicStats(
                settings.getString(SettingKeys.STATS_SUBSCRIBERS, ""),
                settings.getString(SettingKeys.STATS_VIEWS, ""),
                settings.getString(SettingKeys.STATS_VIDEOS, ""),
                settings.getInt(SettingKeys.STATS_INTERPRETED_BASE, 0) + dreamRepository.countByStatus(DreamStatus.INTERPRETED));
    }

    @GetMapping(ApiPaths.Public.LEGAL)
    public PublicDtos.LegalInfo legal() {
        return new PublicDtos.LegalInfo(
                settings.getString(SettingKeys.BRAND_LEGAL_NAME, "").trim(),
                settings.getString(SettingKeys.BRAND_TAX_REGISTRATION_NO, "").trim(),
                settings.getString(SettingKeys.BRAND_SUPPORT_EMAIL, "").trim());
    }

    @GetMapping(ApiPaths.Public.PUSH_KEY)
    public PushKey pushKey() {
        String key = properties.getPush().getPublicKey();
        return new PushKey(key == null ? "" : key.trim());
    }

    /**
     * First name only (privacy). A leading kunya particle (أم / أبو, Umm / Abu) keeps the following word, so
     * "أم محمد" stays "أم محمد" while "Sara Ahmed" becomes "Sara".
     */
    public static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length > 1 && KUNYA_PARTICLES.contains(parts[0].toLowerCase(java.util.Locale.ROOT))) {
            return parts[0] + " " + parts[1];
        }
        return parts[0];
    }
}
