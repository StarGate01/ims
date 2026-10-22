// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.AccessNetworkConstants
import android.telephony.CellIdentityLte
import android.telephony.NetworkRegistrationInfo
import android.telephony.Rlog
import android.telephony.TelephonyManager
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE

internal object SipAccessNetworkHeaders {
    /**
     * Access-network value without the optional cell-id parameter.
     *
     * `utran-cell-id-3gpp` is optional in TS 24.229, so an unreadable cell identity is a reason
     * to degrade the header rather than to drop it. Dropping it is not harmless: a network that
     * authorizes a request against the serving access network rejects a request that carries no
     * PANI at all, so losing one unavailable field would otherwise take out the whole feature.
     * Verified against 1&1 Germany, which accepts this bare form for MO SMS exactly as it accepts
     * the full one (RP-ACK in both cases, RP-Cause 10 with no header).
     */
    private const val LTE_ACCESS_TYPE = "3GPP-E-UTRAN-FDD"

    fun forOutgoingRequest(
        logTag: String,
        telephonyManager: TelephonyManager,
        registrationTech: Int,
        policy: SipCarrierPolicy.OutgoingPaniPolicy,
        visitedNetworkPolicy: SipCarrierPolicy.OutgoingVisitedNetworkPolicy,
        visitedNetworkId: String,
    ): SipHeadersMap {
        if (registrationTech != REGISTRATION_TECH_LTE ||
            policy != SipCarrierPolicy.OutgoingPaniPolicy.LTE_CELL_IDENTITY
        ) {
            return emptyMap()
        }

        val visitedNetwork = visitedNetworkId.takeIf {
            visitedNetworkPolicy == SipCarrierPolicy.OutgoingVisitedNetworkPolicy.REGISTRATION_REALM
        }

        val cellIdentity = try {
            telephonyManager.serviceState
                ?.networkRegistrationInfoList
                ?.firstOrNull {
                    it.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN &&
                        (it.domain and NetworkRegistrationInfo.DOMAIN_PS) != 0 &&
                        it.isNetworkRegistered
                }
                ?.cellIdentity as? CellIdentityLte
        } catch (e: RuntimeException) {
            Rlog.w(logTag, "Could not read LTE identity for SIP access headers", e)
            null
        }

        if (cellIdentity == null) {
            Rlog.w(logTag, "No registered LTE identity; sending access type without cell id")
        }

        return lteHeaders(
            mcc = cellIdentity?.mccString,
            mnc = cellIdentity?.mncString,
            tac = cellIdentity?.tac ?: Int.MAX_VALUE,
            ci = cellIdentity?.ci ?: Int.MAX_VALUE,
            visitedNetworkId = visitedNetwork,
        )
    }

    internal fun lteHeaders(
        mcc: String?,
        mnc: String?,
        tac: Int,
        ci: Int,
        visitedNetworkId: String?,
    ): SipHeadersMap {
        val headers = mutableMapOf(
            "P-Access-Network-Info" to listOf(
                lteCellId(mcc, mnc, tac, ci)
                    ?.let { "$LTE_ACCESS_TYPE;utran-cell-id-3gpp=$it" }
                    ?: LTE_ACCESS_TYPE,
            ),
        )
        visitedNetworkId?.takeIf(String::isNotBlank)?.let {
            headers["P-Visited-Network-ID"] = listOf("\"$it\"")
        }
        return headers
    }

    /**
     * MCC + MNC + TAC + ECI as TS 24.229 expects it, or null when any field is unusable.
     *
     * `CellIdentityLte` reports `Integer.MAX_VALUE` for a field it does not know and null for an
     * unknown MCC/MNC, which the range checks reject.
     */
    private fun lteCellId(mcc: String?, mnc: String?, tac: Int, ci: Int): String? {
        val normalizedMcc = mcc?.takeIf { it.length == 3 && it.all(Char::isDigit) } ?: return null
        val normalizedMnc = mnc?.takeIf { it.length in 2..3 && it.all(Char::isDigit) } ?: return null
        if (tac !in 0..0xffff || ci !in 0..0x0fffffff) return null

        return (
            normalizedMcc + normalizedMnc +
                tac.toString(16).padStart(4, '0') +
                ci.toString(16).padStart(7, '0')
            ).uppercase()
    }
}
