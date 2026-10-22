//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

/**
 * Decisions for emergency calls placed over an established MMTEL registration.
 *
 * Kept free of Android and SipHandler state so the rules can be unit tested: the SIP stack
 * supplies the facts, this object supplies the answers.
 *
 * Scope note: this covers an emergency call on the *normal* IMS registration, which TS 23.167
 * permits for a UE that is already registered. A dedicated emergency registration on the
 * emergency PDN is a separate, unimplemented step (see samsung-calling.md §15).
 */
internal object SipEmergencyDialPolicy {

    /** TS 24.229: the request URI of an emergency INVITE is a service URN, not a TEL URI. */
    const val SOS_SERVICE_URN = "urn:service:sos"

    /**
     * Sub-services defined by RFC 5031. Only used when the dialled number is known to map to one;
     * the generic URN is always acceptable and is the default, because mapping a number to the
     * wrong sub-service routes the caller to the wrong agency.
     */
    private val SUB_SERVICE_URNS = setOf(
        "urn:service:sos.police",
        "urn:service:sos.ambulance",
        "urn:service:sos.fire",
        "urn:service:sos.marine",
        "urn:service:sos.mountain",
    )

    /** True when [uri] is `urn:service:sos` or one of its sub-services. */
    fun isSosUrn(uri: String): Boolean {
        val trimmed = uri.trim().trimStart('<').trimEnd('>').lowercase()
        if (trimmed == SOS_SERVICE_URN) return true
        if (trimmed in SUB_SERVICE_URNS) return true
        // Accept unknown sub-services rather than rejecting a network that invents one.
        return trimmed.startsWith("$SOS_SERVICE_URN.")
    }

    /**
     * Request URI and To for an emergency INVITE.
     *
     * The generic service URN is used regardless of the dialled digits: the network resolves the
     * URN to the right agency using the serving location, which it knows and we do not.
     */
    fun requestUri(subService: String? = null): String {
        val candidate = subService?.trim()?.lowercase()
        return if (candidate != null && candidate in SUB_SERVICE_URNS) candidate else SOS_SERVICE_URN
    }

    /**
     * Whether an emergency call may be attempted over IMS at all.
     *
     * All three must hold. Any false answer means telephony should be told to redial over CS,
     * which is the behaviour this install has today.
     *
     * @param enabled operator-set kill switch; false restores the CS-only behaviour exactly.
     * @param carrierProvisionsEmergency the carrier database carries an emergency-capable profile.
     * @param registered an MMTEL registration is usable right now.
     */
    fun mayAttemptOverIms(
        enabled: Boolean,
        carrierProvisionsEmergency: Boolean,
        registered: Boolean,
    ): Boolean = enabled && carrierProvisionsEmergency && registered

    /**
     * Stock's semantics for `sos_urn_required`, quoted from Samsung's own schema:
     *
     * > "Used for Emergency Calls for undetected emergency numbers, when there is no contact
     * > header with sos urn type in 380 Alternative Service response from network. When this is
     * > enabled => the emergency call is treated as a normal call during CSFB."
     *
     * So: the number was not recognised as an emergency number, the network answered 380
     * Alternative Service, and that response carried no SOS URN contact. With the flag set, the
     * retry over CS is a *normal* call rather than an emergency one.
     *
     * @param numberWasDetectedAsEmergency whether the dial string was known to be an emergency number.
     * @param alternativeServiceContact the Contact URI carried by the 380/382 response, if any.
     */
    fun csfbRetryIsNormalCall(
        sosUrnRequired: Boolean,
        numberWasDetectedAsEmergency: Boolean,
        alternativeServiceContact: String?,
    ): Boolean {
        if (!sosUrnRequired) return false
        if (numberWasDetectedAsEmergency) return false
        val contact = alternativeServiceContact
        if (contact != null && isSosUrn(contact)) return false
        return true
    }
}
