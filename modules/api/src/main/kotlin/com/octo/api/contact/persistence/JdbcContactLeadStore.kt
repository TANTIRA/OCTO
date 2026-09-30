package com.octo.api.contact.persistence

import com.octo.api.contact.ContactController.ContactLead
import com.octo.api.contact.ContactStore
import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import javax.sql.DataSource

/** JDBC access to `octo.contact_lead` (V39): insert-only, append-only, pre-tenant. */
class JdbcContactLeadStore(
    private val dataSource: DataSource,
) : ContactStore {
    override fun record(lead: ContactLead) {
        dataSource.scoped(TenantScope.All) { connection ->
            connection
                .prepareStatement(
                    """
                    insert into octo.contact_lead
                        (email, first_name, last_name, firm, role, aum_band, phone, message, source_ip)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, lead.email)
                    statement.setString(2, lead.firstName)
                    statement.setString(3, lead.lastName)
                    statement.setString(4, lead.firm)
                    statement.setString(5, lead.role)
                    statement.setString(6, lead.aumBand)
                    statement.setString(7, lead.phone)
                    statement.setString(8, lead.message)
                    statement.setString(9, lead.sourceIp)
                    statement.executeUpdate()
                }
        }
    }
}
