package com.saadat.push.api;

/**
 * types.ts {@code PushTestResult}: answer of {@code POST /push/test}. {@code subscriptions} = devices connected to the
 * caller's account, {@code sent} = how many push services accepted the test, {@code configured} = the server has the
 * VAPID keys (false: nothing can be sent until VAPID_PRIVATE_KEY / VAPID_SUBJECT are set).
 */
public record PushTestResult(int subscriptions, int sent, boolean configured) {
}
