package fr.fortytwo.badgescanner.api

import android.content.Context
import fr.fortytwo.badgescanner.R
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The CA (Ixoff ibox4) serves a self-signed certificate whose CN
 * (ibox4.ibox.pro) doesn't match the endpoint hostname, so Android rejects
 * the handshake with "Trust anchor for certification path not found".
 *
 * This builds an OkHttp client that additionally trusts that exact pinned
 * certificate (bundled in res/raw/ca_cert.pem, valid until Oct 2029);
 * any other server still goes through the normal system trust store.
 * Only used for CA requests — the 42 intranet keeps default TLS validation.
 */
object CaTrust {

    fun pinnedClient(context: Context, base: OkHttpClient): OkHttpClient {
        val pinned = context.resources.openRawResource(R.raw.ca_cert).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

        val systemTm = systemTrustManager()
        val pinnedTm = trustManagerFor(pinned)

        val combined = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                systemTm.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    systemTm.checkServerTrusted(chain, authType)
                } catch (_: CertificateException) {
                    pinnedTm.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                systemTm.acceptedIssuers + pinnedTm.acceptedIssuers
        }

        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(combined), null) }
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

        return base.newBuilder()
            .sslSocketFactory(ssl.socketFactory, combined)
            .hostnameVerifier { hostname, session ->
                if (defaultVerifier.verify(hostname, session)) return@hostnameVerifier true
                // Accept the pinned certificate even though its CN doesn't match
                runCatching { session.peerCertificates.firstOrNull() == pinned }.getOrDefault(false)
            }
            .build()
    }

    private fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun trustManagerFor(cert: X509Certificate): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("ca", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
