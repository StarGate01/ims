// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipAccessNetworkHeadersTest {
    @Test
    fun `LTE headers contain padded PLMN TAC and ECI`() {
        val headers = SipAccessNetworkHeaders.lteHeaders(
            mcc = "001",
            mnc = "01",
            tac = 0x1234,
            ci = 0x0056789a,
            visitedNetworkId = "ims.example.test",
        )

        require(
            headers["P-Access-Network-Info"] == listOf(
                "3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=001011234056789A",
            ),
        )
        require(headers["P-Visited-Network-ID"] == listOf("\"ims.example.test\""))
    }

    @Test
    fun `unusable LTE identity degrades to the bare access type`() {
        // The cell-id parameter is optional; dropping the whole header instead makes a
        // network that authorizes on the serving access network reject the request.
        val headers = SipAccessNetworkHeaders.lteHeaders(
            mcc = "001",
            mnc = null,
            tac = 0x1234,
            ci = 0x0056789a,
            visitedNetworkId = "ims.example.test",
        )

        require(headers["P-Access-Network-Info"] == listOf("3GPP-E-UTRAN-FDD"))
        require(headers["P-Visited-Network-ID"] == listOf("\"ims.example.test\""))
    }

    @Test
    fun `unavailable cell identity fields degrade to the bare access type`() {
        // CellIdentityLte reports Integer.MAX_VALUE for a field it does not know.
        require(
            SipAccessNetworkHeaders.lteHeaders(
                mcc = "001",
                mnc = "01",
                tac = Int.MAX_VALUE,
                ci = Int.MAX_VALUE,
                visitedNetworkId = null,
            )["P-Access-Network-Info"] == listOf("3GPP-E-UTRAN-FDD"),
        )
    }

    @Test
    fun `visited network header is independently optional`() {
        val headers = SipAccessNetworkHeaders.lteHeaders(
            mcc = "001",
            mnc = "01",
            tac = 0x1234,
            ci = 0x0056789a,
            visitedNetworkId = null,
        )

        require("P-Access-Network-Info" in headers)
        require("P-Visited-Network-ID" !in headers)
    }
}
