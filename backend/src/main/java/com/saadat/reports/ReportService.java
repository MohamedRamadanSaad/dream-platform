package com.saadat.reports;

import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.Gender;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import com.saadat.common.error.NotFoundException;
import com.saadat.common.util.Ages;
import com.saadat.config.props.AppProperties;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.domain.DreamMessage;
import com.saadat.dreams.repo.DreamMessageRepository;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.dreams.repo.InterpretationRepository;
import com.saadat.mail.MessageText;
import com.saadat.payments.api.OrderDto;
import com.saadat.payments.domain.CreditLedgerEntry;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.CreditLedgerRepository;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.pricing.repo.CountryRepository;
import com.saadat.reports.ReportViews.Download;
import com.saadat.reports.ReportViews.DreamView;
import com.saadat.reports.ReportViews.InterpretationView;
import com.saadat.reports.ReportViews.MessageView;
import com.saadat.reports.ReportViews.Row;
import com.saadat.reports.pdf.PdfRenderer;
import com.saadat.settings.BusinessZone;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PDF reports (contract §4) rendered from {@code templates/pdf/report.html}. Interpreter documents show the
 * e-mail and the payment of each dream; user documents never show the e-mail or any payment reference. Drafts are
 * never included (a draft id answers 404). Labels come from messages {@code pdf.*} / {@code report.*}; dates use
 * the locale's patterns ({@code pdf.format.*}) in the business time zone.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    static final String TEMPLATE = "pdf/report";
    static final int SHORT_ID_LENGTH = 8;
    static final String DIR_RTL = "rtl";
    static final String DIR_LTR = "ltr";
    static final String FILE_DREAM = "dream-";
    static final String FILE_USER = "user-";
    static final String FILE_MY_DREAMS = "dreams-";
    static final String PDF_SUFFIX = ".pdf";
    /** Label/value pairs per table line. */
    static final int PAIRS_PER_LINE = 2;

    private final DreamRepository dreamRepository;
    private final DreamMessageRepository messageRepository;
    private final InterpretationRepository interpretationRepository;
    private final CreditLedgerRepository ledgerRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final CountryRepository countryRepository;
    private final CreditService creditService;
    private final SettingsService settings;
    private final BusinessZone businessZone;
    private final AppProperties properties;
    private final MessageText messageText;
    private final PdfRenderer pdfRenderer;
    private final Clock clock;

    // ================================================================== interpreter

    /** GET /admin/dreams/{id}/pdf */
    @Transactional(readOnly = true)
    public Download adminDream(UUID dreamId, Locale locale) {
        Dream dream = nonDraft(dreamRepository.findById(dreamId), dreamId);
        User owner = userRepository.findById(dream.getUserId())
                .orElseThrow(() -> NotFoundException.of("User", dream.getUserId()));
        Ctx ctx = ctx(locale, true);
        byte[] pdf = render(ctx, text(ctx, "pdf.title.adminDream"), nameOf(owner, ctx), shortId(dream.getId()), owner,
                List.of(dream));
        return new Download(FILE_DREAM + shortId(dream.getId()) + PDF_SUFFIX, pdf);
    }

    /** GET /admin/users/{id}/pdf */
    @Transactional(readOnly = true)
    public Download adminUser(UUID userId, Locale locale) {
        User user = userRepository.findById(userId).orElseThrow(() -> NotFoundException.of("User", userId));
        Ctx ctx = ctx(locale, true);
        byte[] pdf = render(ctx, text(ctx, "pdf.title.adminUser"), nameOf(user, ctx), null, user, nonDrafts(userId));
        return new Download(FILE_USER + shortId(userId) + PDF_SUFFIX, pdf);
    }

    // ================================================================== dream owner

    /**
     * GET /dreams/{id}/pdf — only the owner's non-draft dream (404 otherwise).
     *
     * @param requested Accept-Language locale, or null to use the user's own locale
     */
    @Transactional(readOnly = true)
    public Download myDream(UUID userId, UUID dreamId, Locale requested) {
        Dream dream = nonDraft(dreamRepository.findByIdAndUserId(dreamId, userId), dreamId);
        User user = activeUser(userId);
        Ctx ctx = ctx(requested != null ? requested : user.getLocale(), false);
        byte[] pdf = render(ctx, text(ctx, "pdf.title.userDream"), null, shortId(dream.getId()), user,
                List.of(dream));
        return new Download(FILE_DREAM + shortId(dream.getId()) + PDF_SUFFIX, pdf);
    }

    /** GET /me/dreams/pdf — all the caller's non-draft dreams. */
    @Transactional(readOnly = true)
    public Download myDreams(UUID userId, Locale requested) {
        User user = activeUser(userId);
        Ctx ctx = ctx(requested != null ? requested : user.getLocale(), false);
        byte[] pdf = render(ctx, text(ctx, "pdf.title.myDreams"), nameOf(user, ctx), null, user, nonDrafts(userId));
        LocalDate today = LocalDate.now(clock.withZone(ctx.zone()));
        return new Download(FILE_MY_DREAMS + today + PDF_SUFFIX, pdf);
    }

    // ================================================================== model

    /**
     * @param subtitle  person name under the title, or null
     * @param reference short dream number shown after the name (printed left-to-right as {@code #1a2b3c4d}), or null
     */
    private byte[] render(Ctx ctx, String title, String subtitle, String reference, User person, List<Dream> dreams) {
        boolean rtl = ctx.locale() == Locale.AR;
        Map<String, Object> model = new HashMap<>();
        model.put("lang", ctx.locale().code());
        model.put("dir", rtl ? DIR_RTL : DIR_LTR);
        model.put("rtl", rtl);
        model.put("brandName", settings.getString(rtl ? SettingKeys.BRAND_NAME_AR : SettingKeys.BRAND_NAME_EN, ""));
        model.put("tagline", settings.getString(rtl ? SettingKeys.BRAND_TAGLINE_AR : SettingKeys.BRAND_TAGLINE_EN,
                ""));
        model.put("site", siteHost());
        model.put("generated", messageText.get("pdf.generated", ctx.locale(),
                Map.of("date", ctx.dateTime().format(clock.instant().atZone(ctx.zone())))));
        model.put("title", title);
        model.put("subtitle", subtitle);
        model.put("reference", reference);
        model.put("personGrid", ReportViews.grid(personRows(ctx, person), PAIRS_PER_LINE, rtl));
        List<DreamView> views = new ArrayList<>();
        for (Dream d : dreams) {
            views.add(dreamView(ctx, d));
        }
        model.put("dreams", views);
        return pdfRenderer.render(TEMPLATE, ctx.locale(), model);
    }

    private List<Row> personRows(Ctx ctx, User user) {
        List<Row> rows = new ArrayList<>();
        rows.add(row(ctx, "pdf.person.name", PdfRenderer.clean(user.getName())));
        if (ctx.admin()) {
            rows.add(row(ctx, "pdf.person.email", PdfRenderer.clean(user.getEmail())));
        }
        rows.add(row(ctx, "pdf.person.gender", gender(ctx, user.getGender())));
        rows.add(row(ctx, "pdf.person.birthDate",
                user.getBirthDate() == null ? null : ctx.date().format(user.getBirthDate())));
        Integer age = Ages.of(user.getBirthDate(), clock);
        rows.add(row(ctx, "pdf.person.age", age == null ? null : String.valueOf(age)));
        rows.add(row(ctx, "pdf.person.country", country(ctx, user.getCountryCode())));
        rows.add(row(ctx, "pdf.person.joined", dateOf(ctx, user.getCreatedAt())));
        rows.add(row(ctx, "pdf.person.credits", String.valueOf(creditService.balance(user.getId()))));
        rows.add(row(ctx, "pdf.person.dreams",
                String.valueOf(dreamRepository.countByUserIdAndStatusNot(user.getId(), DreamStatus.DRAFT))));
        return rows;
    }

    private DreamView dreamView(Ctx ctx, Dream d) {
        List<Row> meta = new ArrayList<>();
        meta.add(row(ctx, "pdf.dream.submitted", dateTimeOf(ctx, d.getSubmittedAt())));
        meta.add(row(ctx, "pdf.dream.expected", dateTimeOf(ctx, d.getExpectedBy())));
        if (d.getInterpretedAt() != null) {
            meta.add(row(ctx, "pdf.dream.interpreted", dateTimeOf(ctx, d.getInterpretedAt())));
        }
        meta.add(row(ctx, "pdf.dream.gender", gender(ctx, d.getGender())));
        if (d.getStatus() == DreamStatus.CANCELLED && d.getCancelledReason() != null
                && !d.getCancelledReason().isBlank()) {
            meta.add(row(ctx, "pdf.dream.cancelReason", PdfRenderer.clean(d.getCancelledReason())));
        }

        List<MessageView> messages = new ArrayList<>();
        for (DreamMessage m : messageRepository.findByDreamIdOrderByCreatedAtAsc(d.getId())) {
            boolean fromInterpreter = m.getSenderRole() == Role.INTERPRETER;
            messages.add(new MessageView(text(ctx, "pdf.sender." + m.getSenderRole().name()),
                    dateTimeOf(ctx, m.getCreatedAt()), PdfRenderer.clean(m.getBody()), fromInterpreter));
        }

        InterpretationView interpretation = interpretationRepository.findByDreamId(d.getId())
                .map(i -> new InterpretationView(PdfRenderer.clean(i.getText()),
                        dateTimeOf(ctx, d.getInterpretedAt() != null ? d.getInterpretedAt() : i.getCreatedAt())))
                .orElse(null);

        boolean rtl = ctx.locale() == Locale.AR;
        List<Row> payment = ctx.admin() ? paymentRows(ctx, d) : List.of();
        return new DreamView(shortId(d.getId()), text(ctx, "report.status." + d.getStatus().name()),
                d.getStatus().name(), ReportViews.grid(meta, PAIRS_PER_LINE, rtl), PdfRenderer.clean(d.getText()),
                messages, interpretation, ReportViews.grid(payment, PAIRS_PER_LINE, rtl));
    }

    /** The order that paid for the dream (ledger entry → order); interpreter documents only. */
    private List<Row> paymentRows(Ctx ctx, Dream d) {
        if (d.getLedgerEntryId() == null) {
            return List.of();
        }
        Optional<Order> order = ledgerRepository.findById(d.getLedgerEntryId())
                .map(CreditLedgerEntry::getOrderId)
                .flatMap(orderRepository::findById);
        if (order.isEmpty()) {
            return List.of();
        }
        Order o = order.get();
        String ref = o.getProviderTxnId() != null ? o.getProviderTxnId()
                : (o.getProviderOrderId() != null ? o.getProviderOrderId() : o.getId().toString());
        List<Row> rows = new ArrayList<>();
        rows.add(row(ctx, "pdf.payment.order", PdfRenderer.clean(ref)));
        rows.add(row(ctx, "pdf.payment.package", PdfRenderer.clean(o.getPackageNameSnapshot())));
        rows.add(row(ctx, "pdf.payment.amount", o.getAmount().toPlainString() + " " + o.getCurrency().name()));
        rows.add(row(ctx, "pdf.payment.provider", OrderDto.displayProvider(o).name()));
        rows.add(row(ctx, "pdf.payment.paidAt",
                dateTimeOf(ctx, o.getPaidAt() != null ? o.getPaidAt() : o.getCreatedAt())));
        return rows;
    }

    // ================================================================== helpers

    private Dream nonDraft(Optional<Dream> dream, UUID dreamId) {
        return dream.filter(d -> d.getStatus() != DreamStatus.DRAFT)
                .orElseThrow(() -> NotFoundException.of("Dream", dreamId));
    }

    private List<Dream> nonDrafts(UUID userId) {
        return dreamRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(d -> d.getStatus() != DreamStatus.DRAFT)
                .toList();
    }

    private User activeUser(UUID userId) {
        return userRepository.findById(userId).filter(u -> !u.isDeleted())
                .orElseThrow(() -> NotFoundException.of("User", userId));
    }

    private Ctx ctx(Locale locale, boolean admin) {
        Locale loc = locale == null ? Locale.AR : locale;
        java.util.Locale javaLocale = MessageText.toJava(loc);
        return new Ctx(loc, admin, businessZone.zone(),
                DateTimeFormatter.ofPattern(text(loc, "pdf.format.date"), javaLocale),
                DateTimeFormatter.ofPattern(text(loc, "pdf.format.dateTime"), javaLocale));
    }

    private Row row(Ctx ctx, String labelKey, String value) {
        return new Row(text(ctx, labelKey), value == null || value.isBlank() ? text(ctx, "report.none") : value);
    }

    private String text(Ctx ctx, String key) {
        return text(ctx.locale(), key);
    }

    private String text(Locale locale, String key) {
        return messageText.get(key, locale, Map.of());
    }

    private String gender(Ctx ctx, Gender gender) {
        return gender == null ? null : text(ctx, "report.gender." + gender.name());
    }

    private String country(Ctx ctx, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return countryRepository.findById(code.trim())
                .map(c -> ctx.locale() == Locale.EN ? c.getNameEn() : c.getNameAr())
                .orElse(code.trim());
    }

    private String nameOf(User user, Ctx ctx) {
        String name = PdfRenderer.clean(user.getName());
        return name == null || name.isBlank() ? text(ctx, "report.none") : name;
    }

    private static String dateOf(Ctx ctx, Instant instant) {
        return instant == null ? null : ctx.date().format(instant.atZone(ctx.zone()));
    }

    private static String dateTimeOf(Ctx ctx, Instant instant) {
        return instant == null ? null : ctx.dateTime().format(instant.atZone(ctx.zone()));
    }

    private String siteHost() {
        String url = properties.getFrontendUrl();
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    static String shortId(UUID id) {
        return id.toString().substring(0, SHORT_ID_LENGTH);
    }

    /** Formatting context of one document. */
    private record Ctx(Locale locale, boolean admin, ZoneId zone, DateTimeFormatter date,
                       DateTimeFormatter dateTime) {
    }
}
