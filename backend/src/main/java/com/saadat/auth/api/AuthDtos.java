package com.saadat.auth.api;

import jakarta.validation.constraints.Past;

import java.time.LocalDate;

import com.saadat.common.domain.CodePurpose;
import com.saadat.common.domain.Gender;
import com.saadat.users.api.UserDto;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request/response records of the /auth routes (types.ts "Auth" section). */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** "Remember me" of a sign-in request: a missing value means true (docs/SESSIONS_PROFILE_CONTRACT.md §1). */
    public static boolean rememberMe(Boolean value) {
        return value == null || value;
    }

    public record AuthResponse(String accessToken, long expiresIn, UserDto user) {
        @Override
        public String toString() {
            return "AuthResponse[user=" + (user == null ? null : user.id()) + "]";
        }
    }

    /** {@code rememberMe}: missing = true (persistent cookie); false = browser-session cookie. */
    public record GoogleLoginRequest(@NotBlank String idToken, Boolean rememberMe) {
    }

    public record MagicRequest(@NotBlank @Email @Size(max = 320) String email) {
    }

    /** Either {@code token} or {@code email}+{@code code}; {@code rememberMe} as in {@link GoogleLoginRequest}. */
    public record MagicVerifyRequest(
            @Size(max = 256) String token,
            @Email @Size(max = 320) String email,
            @Size(max = 16) String code,
            Boolean rememberMe) {
    }

    // ---------------------------------------------------------------- e-mail + password (V37)

    /** POST /auth/signup/request and /auth/password/forgot. */
    public record EmailCodeRequest(@NotBlank @Email @Size(max = 320) String email) {
    }

    /** POST /auth/code/verify: {@code purpose} SIGN_UP or RESET_PASSWORD. */
    public record CodeVerifyRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 16) String code,
            @NotNull CodePurpose purpose) {
    }

    /** A verified code's single-use ticket; {@code expiresIn} in seconds. */
    public record TicketResponse(String ticket, long expiresIn) {
        @Override
        public String toString() {
            return "TicketResponse[expiresIn=" + expiresIn + "]";
        }
    }

    /** POST /auth/signup/complete. The password rule is checked by the server (422 WEAK_PASSWORD). */
    public record SignupCompleteRequest(
            @NotBlank @Size(max = 256) String ticket,
            @NotNull String password,
            @NotNull @AssertTrue Boolean acceptedTerms,
            Boolean rememberMe) {
        @Override
        public String toString() {
            return "SignupCompleteRequest[acceptedTerms=" + acceptedTerms + ", rememberMe=" + rememberMe + "]";
        }
    }

    /** POST /auth/password/reset. */
    public record PasswordResetRequest(
            @NotBlank @Size(max = 256) String ticket,
            @NotNull String password,
            Boolean rememberMe) {
        @Override
        public String toString() {
            return "PasswordResetRequest[rememberMe=" + rememberMe + "]";
        }
    }

    /** POST /auth/password/login. */
    public record PasswordLoginRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotNull @Size(max = 1024) String password,
            Boolean rememberMe) {
        @Override
        public String toString() {
            return "PasswordLoginRequest[rememberMe=" + rememberMe + "]";
        }
    }

    public record OnboardingRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Gender gender,
            @NotNull @Past LocalDate birthDate,
            @NotNull @AssertTrue Boolean acceptedTerms) {
    }
}
