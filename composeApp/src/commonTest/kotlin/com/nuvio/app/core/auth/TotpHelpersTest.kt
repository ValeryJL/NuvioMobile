package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class TotpHelpersTest {

    @Test
    fun normalizeTotpQrSvgPreservesRawSvg() {
        val raw = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>"
        assertEquals(raw, normalizeTotpQrSvg(raw))
    }

    @Test
    fun normalizeTotpQrSvgStripsPercentEncodedDataUri() {
        val encoded = "data:image/svg+xml;utf-8,%3Csvg%20width%3D%22100%22%3E%3C%2Fsvg%3E"
        assertEquals("<svg width=\"100\"></svg>", normalizeTotpQrSvg(encoded))
    }

    @Test
    fun formatTotpSecretGroupsFourCharactersUppercase() {
        assertEquals("JBSW Y3DP EHPK 3PXP", formatTotpSecretForDisplay("jbswy3dpehpk3pxp"))
    }
}
