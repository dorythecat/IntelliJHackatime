package io.github.dorythecat.intellijhackatime

import javax.net.ssl.X509TrustManager

class LocalSSLTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
        // No implementation needed for local trust manager
    }

    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
        // No implementation needed for local trust manager
    }

    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
        return arrayOf()
    }
}
