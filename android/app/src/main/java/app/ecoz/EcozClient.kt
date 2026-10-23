package app.ecoz

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/** [unreachable] means the laptop wasn't contacted at all (wrong address / off network), so rediscovery may help. */
data class Outcome(val ok: Boolean, val message: String, val unreachable: Boolean = false)

/** The laptop's answer to "may I send these files?". [tickets] is one per file, in order, when accepted. */
data class Offer(val outcome: Outcome, val accepted: Boolean = false, val tickets: List<String> = emptyList())

/** Lets another thread cut a blocking [EcozClient.listen] short. */
class StreamHandle {
    @Volatile private var connection: HttpsURLConnection? = null
    internal fun attach(c: HttpsURLConnection) { connection = c }
    fun close() { connection?.disconnect() }
}

/**
 * HTTPS client for the laptop service. The laptop's certificate is self-signed, so
 * instead of CA validation we pin its SHA-256 fingerprint from the pairing code. That
 * pin is the laptop's identity, which is why hostname verification is skipped.
 */
object EcozClient {
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 6_000
    private const val COPY_BUFFER_BYTES = 64 * 1024

    /** The laptop waits up to two minutes for an answer, so allow a little longer than that. */
    private const val OFFER_READ_TIMEOUT_MS = 135_000

    /** The laptop sends a keepalive every 15 s; silence for 45 s means the link is dead. */
    private const val STREAM_READ_TIMEOUT_MS = 45_000

    fun ping(p: Pairing): Outcome = request(p, "GET", "/v1/ping", null)

    fun send(p: Pairing, type: String, value: String): Outcome =
        request(p, "POST", "/v1/handoff", JSONObject().put("type", type).put("value", value).toString())

    fun post(p: Pairing, path: String, body: JSONObject): Outcome = request(p, "POST", path, body.toString())

    /**
     * Step 1 of sending files: describe them and wait while the laptop asks its owner. The wait can
     * last up to the laptop's prompt timeout, so this read is allowed to be long.
     */
    fun offerFiles(p: Pairing, files: List<Pair<String, Long>>): Offer {
        var accepted = false
        var tickets = emptyList<String>()
        val outcome = guarded(p) {
            val conn = connect(p, "POST", "/v1/file/offer", OFFER_READ_TIMEOUT_MS)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("files", JSONArray().apply {
                files.forEach { (name, size) -> put(JSONObject().put("name", name).put("size", size)) }
            })
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                if (code != 200) return@guarded statusOutcome(p, code)
                val reply = JSONObject(conn.inputStream.bufferedReader().readText())
                accepted = reply.optBoolean("accepted")
                tickets = reply.optJSONArray("tickets")?.let { list -> List(list.length()) { list.getString(it) } } ?: emptyList()
                Outcome(true, if (accepted) "Accepted" else "Declined")
            } finally {
                conn.disconnect()
            }
        }
        return Offer(outcome, accepted, tickets)
    }

    /** Step 2: stream one approved file (exactly [size] bytes), presenting its one-time [ticket]. */
    fun upload(p: Pairing, ticket: String, size: Long, source: InputStream, onProgress: (Long) -> Unit): Outcome =
        guarded(p) {
            val conn = connect(p, "POST", "/v1/file", STREAM_READ_TIMEOUT_MS)
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(size) // streams without buffering the whole file in memory
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-Ecoz-Ticket", ticket)
            try {
                conn.outputStream.use { out -> copy(source, out, onProgress) }
                statusOutcome(p, conn.responseCode)
            } finally {
                conn.disconnect()
            }
        }

    /** Tells the laptop we don't want a file it offered, so it can forget the offer. */
    fun decline(p: Pairing, fileId: String): Outcome =
        request(p, "POST", "/v1/file/${URLEncoder.encode(fileId, "UTF-8")}/decline", "{}")

    /** Streams a file the laptop offered into [sink]; checks that all [expectedSize] bytes arrived. */
    fun download(p: Pairing, fileId: String, expectedSize: Long, sink: OutputStream, onProgress: (Long) -> Unit): Outcome =
        guarded(p) {
            val conn = connect(p, "GET", "/v1/file/${URLEncoder.encode(fileId, "UTF-8")}", STREAM_READ_TIMEOUT_MS)
            try {
                val code = conn.responseCode
                if (code != 200) return@guarded statusOutcome(p, code)
                val received = conn.inputStream.use { input -> copy(input, sink, onProgress) }
                if (received != expectedSize) Outcome(false, "The file arrived incomplete.") else Outcome(true, "Received")
            } finally {
                conn.disconnect()
            }
        }

    private fun copy(input: InputStream, output: OutputStream, onProgress: (Long) -> Unit): Long {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
            onProgress(total)
        }
        return total
    }

    /**
     * Offers the laptop this phone's unlock public key. The laptop asks its owner, so this can wait up
     * to the laptop's prompt timeout. Returns whether the owner approved.
     */
    fun registerUnlockKey(p: Pairing, publicKeyDer: ByteArray): Pair<Outcome, Boolean> {
        var accepted = false
        val outcome = guarded(p) {
            val conn = connect(p, "POST", "/v1/unlock/register", OFFER_READ_TIMEOUT_MS)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("public_key", android.util.Base64.encodeToString(publicKeyDer, android.util.Base64.NO_WRAP))
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                if (code != 200) return@guarded statusOutcome(p, code)
                accepted = JSONObject(conn.inputStream.bufferedReader().readText()).optBoolean("accepted")
                Outcome(true, if (accepted) "Approved" else "Not approved")
            } finally {
                conn.disconnect()
            }
        }
        return outcome to accepted
    }

    /** Sends the signed answer to an unlock challenge. */
    fun answerUnlock(p: Pairing, nonceB64: String, signature: ByteArray): Outcome = request(
        p, "POST", "/v1/unlock/response",
        JSONObject().put("nonce", nonceB64)
            .put("signature", android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)).toString(),
    )

    fun ack(p: Pairing, eventId: String): Outcome =
        request(p, "POST", "/v1/ack", JSONObject().put("id", eventId).toString())

    /**
     * Holds the laptop->phone event stream open and calls [onEvent] for each item, blocking
     * until the connection ends. [onConnected] fires once the laptop accepts the stream.
     */
    fun listen(p: Pairing, handle: StreamHandle, onConnected: () -> Unit, onEvent: (JSONObject) -> Unit): Outcome =
        guarded(p) {
            val conn = connect(p, "GET", "/v1/events", STREAM_READ_TIMEOUT_MS)
            handle.attach(conn)
            try {
                val code = conn.responseCode
                if (code != 200) return@guarded statusOutcome(p, code)
                onConnected()
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("data: ")) onEvent(JSONObject(line.removePrefix("data: ")))
                    }
                }
                Outcome(false, "${p.name} closed the connection.")
            } finally {
                conn.disconnect()
            }
        }

    private fun request(p: Pairing, method: String, path: String, body: String?): Outcome = guarded(p) {
        val conn = connect(p, method, path, READ_TIMEOUT_MS)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            statusOutcome(p, conn.responseCode)
        } finally {
            conn.disconnect()
        }
    }

    private fun connect(p: Pairing, method: String, path: String, readTimeoutMs: Int): HttpsURLConnection {
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(p.fingerprint)), null) }
        return (URL("https://${p.host}:${p.port}$path").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = tls.socketFactory
            hostnameVerifier = HostnameVerifier { _, _ -> true }
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer ${p.token}")
        }
    }

    private fun statusOutcome(p: Pairing, code: Int): Outcome = when (code) {
        200 -> Outcome(true, "Sent to ${p.name}")
        401 -> Outcome(false, "Laptop rejected the pairing. Pair again.")
        400 -> Outcome(false, "Laptop refused that.")
        403 -> Outcome(false, "The laptop didn't approve that upload.")
        404 -> Outcome(false, "That file is no longer available on the laptop.")
        507 -> Outcome(false, "Not enough free space on the laptop.")
        else -> Outcome(false, "Laptop answered $code.")
    }

    private fun guarded(p: Pairing, block: () -> Outcome): Outcome = try {
        block()
    } catch (e: SSLException) {
        if (e.causeChain().any { it is CertificateException })
            Outcome(false, "Laptop identity changed. Pair again.")
        else Outcome(false, "Secure connection failed.")
    } catch (_: ConnectException) {
        Outcome(false, "Can't reach ${p.name}. Same Wi-Fi? Is the service running?", unreachable = true)
    } catch (_: NoRouteToHostException) {
        Outcome(false, "No route to ${p.name}. Same Wi-Fi?", unreachable = true)
    } catch (_: SocketTimeoutException) {
        Outcome(false, "${p.name} didn't answer in time.", unreachable = true)
    } catch (e: IOException) {
        Outcome(false, "Network error: ${e.message ?: e::class.simpleName}")
    }

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private class PinnedTrust(private val expectedHex: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("client certificates are not used")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
            val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                .joinToString("") { "%02x".format(it) }
            if (!MessageDigest.isEqual(actual.toByteArray(), expectedHex.toByteArray()))
                throw CertificateException("certificate fingerprint mismatch")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
