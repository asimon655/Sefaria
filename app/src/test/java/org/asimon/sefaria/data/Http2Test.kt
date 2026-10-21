package org.asimon.sefaria.data

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.asimon.sefaria.di.AppModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Over HTTPS the app's client speaks HTTP/2 (negotiated in the TLS handshake), as production needs;
 * debug builds also speak it to a plain-HTTP dev server (h2c with prior knowledge).
 */
class Http2Test {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.close()

    @Test
    fun httpsNegotiatesHttp2() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server.useHttps(serverCerts.sslSocketFactory())
        server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        server.enqueue(MockResponse.Builder().body("{}").build())
        server.start()

        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        // the app's client as AppModule builds it, trusting the test certificate
        val client = AppModule.okHttp().newBuilder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        client.newCall(Request.Builder().url(server.url("/v1/manifest")).build()).execute().use {
            assertEquals(Protocol.HTTP_2, it.protocol)
        }
    }

    @Test
    fun debugSpeaksH2cOverPlainHttp() {
        server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        server.enqueue(MockResponse.Builder().body("{}").build())
        server.start()
        val calls = AppModule.h2c(AppModule.okHttp())
        calls.newCall(Request.Builder().url(server.url("/v1/manifest")).build()).execute().use {
            assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, it.protocol)
        }
    }
}
