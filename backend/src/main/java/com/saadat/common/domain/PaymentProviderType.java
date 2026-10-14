package com.saadat.common.domain;

/** Payment provider. MOCK is used only when app.payments.mock=true. Stored as varchar via {@code @Enumerated(EnumType.STRING)}; JSON value equals the constant name. */
public enum PaymentProviderType {
    KASHIER,
    MOR,
    MOCK,
    /** Manual InstaPay transfer inside Egypt (EGP), confirmed by the interpreter (V34). */
    INSTAPAY
}
