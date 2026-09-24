package com.mesta.asset.api.access

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The membership state machine (V8): grants activate access, role changes need an active membership,
 * revocations end it until a later grant, and nobody grants or changes their own access.
 */
class MembershipTest {
    private val tenantId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val grantor = UUID.randomUUID().toString()

    private fun opened() = registered(tenantId, userId, T0)

    @Test
    fun `a grant activates access with its role`() {
        val state = opened().next(MembershipEvent.Granted(grantor, at(1), TenantRole.ANALYST))
        assertThat(state.active).isTrue()
        assertThat(state.role).isEqualTo(TenantRole.ANALYST)
    }

    @Test
    fun `a second grant while active is rejected — change the role instead`() {
        val state = opened().next(MembershipEvent.Granted(grantor, at(1), TenantRole.ANALYST))
        assertThatThrownBy { state.next(MembershipEvent.Granted(grantor, at(2), TenantRole.ADMIN)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("already has access")
    }

    @Test
    fun `role changes and revocation follow the active membership`() {
        val granted = opened().next(MembershipEvent.Granted(grantor, at(1), TenantRole.ANALYST))
        val promoted = granted.next(MembershipEvent.RoleChanged(grantor, at(2), TenantRole.APPROVER))
        assertThat(promoted.role).isEqualTo(TenantRole.APPROVER)

        val revoked = promoted.next(MembershipEvent.Revoked(grantor, at(3), "left the firm"))
        assertThat(revoked.active).isFalse()
        assertThat(revoked.role).isNull()

        val regranted = revoked.next(MembershipEvent.Granted(grantor, at(4), TenantRole.VIEWER))
        assertThat(regranted.active).isTrue()
        assertThat(regranted.role).isEqualTo(TenantRole.VIEWER)
    }

    @Test
    fun `role changes and revocations need an active membership`() {
        val state = opened()
        for (event in listOf(
            MembershipEvent.RoleChanged(grantor, at(1), TenantRole.ADMIN),
            MembershipEvent.Revoked(grantor, at(1), "no longer needed"),
        )) {
            assertThatThrownBy { state.next(event) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("no active membership")
        }
    }

    @Test
    fun `nobody grants or changes their own access, but anyone may revoke their own`() {
        val state = opened()
        val self = userId.toString()
        assertThatThrownBy { state.next(MembershipEvent.Granted(self, at(1), TenantRole.ADMIN)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("segregation of duties")
        val granted = state.next(MembershipEvent.Granted(grantor, at(1), TenantRole.ADMIN))
        assertThatThrownBy { granted.next(MembershipEvent.RoleChanged(self, at(2), TenantRole.VIEWER)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("segregation of duties")
        assertThat(granted.next(MembershipEvent.Revoked(self, at(2), "leaving")).active).isFalse()
    }

    @Test
    fun `a revocation needs a rationale and events stay in time order`() {
        assertThatThrownBy { MembershipEvent.Revoked(grantor, at(1), "  ") }
            .isInstanceOf(IllegalArgumentException::class.java)
        val granted = opened().next(MembershipEvent.Granted(grantor, at(2), TenantRole.ANALYST))
        assertThatThrownBy { granted.next(MembershipEvent.Revoked(grantor, at(1), "out of order")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("time order")
    }

    @Test
    fun `replay derives the same state the events imply`() {
        val events =
            listOf(
                MembershipEvent.Granted(grantor, at(1), TenantRole.ANALYST),
                MembershipEvent.RoleChanged(grantor, at(2), TenantRole.APPROVER),
                MembershipEvent.Revoked(grantor, at(3), "off-boarded"),
            )
        val state = replay(opened(), events)
        assertThat(state.status).isEqualTo(MembershipStatus.REVOKED)
        assertThat(state.lastEventAt).isEqualTo(at(3))
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-09-25T09:00:00Z")

        fun at(minutes: Long): Instant = T0.plusSeconds(minutes * 60)
    }
}
