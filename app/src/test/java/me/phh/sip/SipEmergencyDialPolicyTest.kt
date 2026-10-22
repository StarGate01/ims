// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipEmergencyDialPolicyTest {

    @Test
    fun `request uri defaults to the generic service urn`() {
        require(SipEmergencyDialPolicy.requestUri() == "urn:service:sos")
    }

    @Test
    fun `a known sub-service is honoured and an unknown one falls back to generic`() {
        require(SipEmergencyDialPolicy.requestUri("urn:service:sos.police") == "urn:service:sos.police")
        require(SipEmergencyDialPolicy.requestUri("URN:SERVICE:SOS.FIRE") == "urn:service:sos.fire")
        // Never guess: an unrecognised sub-service would risk the wrong agency.
        require(SipEmergencyDialPolicy.requestUri("urn:service:sos.dentist") == "urn:service:sos")
        require(SipEmergencyDialPolicy.requestUri("tel:112") == "urn:service:sos")
    }

    @Test
    fun `sos urn recognition accepts generic sub-services and bracketed forms`() {
        require(SipEmergencyDialPolicy.isSosUrn("urn:service:sos"))
        require(SipEmergencyDialPolicy.isSosUrn("<urn:service:sos>"))
        require(SipEmergencyDialPolicy.isSosUrn("URN:Service:SOS.ambulance"))
        // A sub-service the spec gained after this code was written must still count.
        require(SipEmergencyDialPolicy.isSosUrn("urn:service:sos.unknown-future"))
        require(!SipEmergencyDialPolicy.isSosUrn("tel:+4930110"))
        require(!SipEmergencyDialPolicy.isSosUrn("sip:112@ims.example.test"))
        require(!SipEmergencyDialPolicy.isSosUrn("urn:service:sosdentist"))
    }

    @Test
    fun `IMS is attempted only when enabled provisioned and registered`() {
        require(
            SipEmergencyDialPolicy.mayAttemptOverIms(
                enabled = true,
                carrierProvisionsEmergency = true,
                registered = true,
            ),
        )
        require(
            !SipEmergencyDialPolicy.mayAttemptOverIms(
                enabled = false,
                carrierProvisionsEmergency = true,
                registered = true,
            ),
        )
        require(
            !SipEmergencyDialPolicy.mayAttemptOverIms(
                enabled = true,
                carrierProvisionsEmergency = false,
                registered = true,
            ),
        )
        require(
            !SipEmergencyDialPolicy.mayAttemptOverIms(
                enabled = true,
                carrierProvisionsEmergency = true,
                registered = false,
            ),
        )
    }

    @Test
    fun `csfb retry is a normal call only in the case stock describes`() {
        // The documented case: flag set, number not detected as emergency, 380 without sos URN.
        require(
            SipEmergencyDialPolicy.csfbRetryIsNormalCall(
                sosUrnRequired = true,
                numberWasDetectedAsEmergency = false,
                alternativeServiceContact = "sip:redirect@ims.example.test",
            ),
        )
        // A missing Contact is still "no contact header with sos urn type".
        require(
            SipEmergencyDialPolicy.csfbRetryIsNormalCall(
                sosUrnRequired = true,
                numberWasDetectedAsEmergency = false,
                alternativeServiceContact = null,
            ),
        )
        // The network did say sos: keep it an emergency retry.
        require(
            !SipEmergencyDialPolicy.csfbRetryIsNormalCall(
                sosUrnRequired = true,
                numberWasDetectedAsEmergency = false,
                alternativeServiceContact = "urn:service:sos.police",
            ),
        )
        // A number we already knew to be an emergency number stays an emergency retry.
        require(
            !SipEmergencyDialPolicy.csfbRetryIsNormalCall(
                sosUrnRequired = true,
                numberWasDetectedAsEmergency = true,
                alternativeServiceContact = null,
            ),
        )
        // Carrier does not set the flag: never reinterpret the retry.
        require(
            !SipEmergencyDialPolicy.csfbRetryIsNormalCall(
                sosUrnRequired = false,
                numberWasDetectedAsEmergency = false,
                alternativeServiceContact = null,
            ),
        )
    }
}
