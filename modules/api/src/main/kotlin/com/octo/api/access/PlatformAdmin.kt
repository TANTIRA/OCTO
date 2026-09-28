package com.octo.api.access

/**
 * The platform-level gate for tenant provisioning (tenancy megaplan slice B). Every [TenantRole]
 * is scoped to one organization, so nothing inside a tenant can create another — platform
 * administration is a deliberately small, separate list of JWT subjects read from
 * `OCTO_PLATFORM_ADMINS`.
 *
 * An unset or empty list admits nobody: provisioning is impossible until an operator names the
 * admins, the same fail-closed posture the security config takes on a missing JWKS URL.
 */
class PlatformAdmin(
    subjects: String?,
) {
    private val admins: Set<String> =
        subjects
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()

    fun isAdmin(subject: String?): Boolean = subject != null && subject in admins
}
