package com.guuslab.nemi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.EllipticCurve

/** One key from the JWKS document. */
@Serializable
public data class Jwk(
    @SerialName("kty") val kty: String,
    @SerialName("kid") val kid: String? = null,
    @SerialName("crv") val crv: String? = null,
    @SerialName("x") val x: String? = null,
    @SerialName("y") val y: String? = null,
    @SerialName("alg") val alg: String? = null,
    @SerialName("use") val use: String? = null,
)

@Serializable
public data class JwkSet(@SerialName("keys") val keys: List<Jwk> = emptyList())

/** Minimal JOSE support: just enough to verify Nemi's ES256 id_tokens with `java.security`. */
internal object Jose {
    /** NIST P-256 (secp256r1). Hard coded: `AlgorithmParameters("EC")` is not reliable on older Android. */
    private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val A = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16)
    private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    private val GX = BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16)
    private val GY = BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
    private val N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    val P256: ECParameterSpec = ECParameterSpec(EllipticCurve(ECFieldFp(P), A, B), ECPoint(GX, GY), N, 1)

    class ParsedJwt(
        val header: JsonObject,
        val payload: JsonObject,
        val signingInput: ByteArray,
        val signature: ByteArray,
    )

    fun parse(jwt: String): ParsedJwt {
        val parts = jwt.split('.')
        if (parts.size != 3) throw invalid("The id_token is not a compact JWS")
        return try {
            ParsedJwt(
                header = NemiJson.parseToJsonElement(String(Base64Url.decode(parts[0]), Charsets.UTF_8)).jsonObject,
                payload = NemiJson.parseToJsonElement(String(Base64Url.decode(parts[1]), Charsets.UTF_8)).jsonObject,
                signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII),
                signature = Base64Url.decode(parts[2]),
            )
        } catch (e: Exception) {
            throw invalid("The id_token could not be decoded", e)
        }
    }

    /** Builds a P-256 public key from the JWK `x` and `y` coordinates, refusing points off the curve. */
    fun ecPublicKey(jwk: Jwk): ECPublicKey {
        if (jwk.kty != "EC" || jwk.crv != "P-256") throw invalid("Key ${jwk.kid} is not an EC P-256 key")
        val x = Base64Url.decode(jwk.x ?: throw invalid("Key ${jwk.kid} has no x"))
        val y = Base64Url.decode(jwk.y ?: throw invalid("Key ${jwk.kid} has no y"))
        if (x.size != 32 || y.size != 32) throw invalid("Key ${jwk.kid} has malformed coordinates")
        val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
        if (!isOnCurve(point)) throw invalid("Key ${jwk.kid} is not a point on P-256")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, P256)) as ECPublicKey
    }

    private fun isOnCurve(point: ECPoint): Boolean {
        val x = point.affineX
        val y = point.affineY
        if (x.signum() < 0 || x >= P || y.signum() < 0 || y >= P) return false
        val left = y.modPow(BigInteger.valueOf(2), P)
        val right = x.modPow(BigInteger.valueOf(3), P).add(A.multiply(x)).add(B).mod(P)
        return left == right
    }

    /** Converts a JOSE ES256 signature (`r || s`, 64 bytes) to the ASN.1 DER form `java.security` expects. */
    fun joseToDer(signature: ByteArray): ByteArray {
        if (signature.size != 64) throw invalid("An ES256 signature must be 64 bytes")
        val r = derInteger(signature.copyOfRange(0, 32))
        val s = derInteger(signature.copyOfRange(32, 64))
        val body = r + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun derInteger(unsigned: ByteArray): ByteArray {
        var start = 0
        while (start < unsigned.size - 1 && unsigned[start] == 0.toByte()) start++
        var value = unsigned.copyOfRange(start, unsigned.size)
        if (value[0].toInt() and 0x80 != 0) value = byteArrayOf(0) + value
        return byteArrayOf(0x02, value.size.toByte()) + value
    }

    fun verifyEs256(key: ECPublicKey, signingInput: ByteArray, joseSignature: ByteArray): Boolean = try {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(signingInput)
        verifier.verify(joseToDer(joseSignature))
    } catch (e: NemiOAuthException) {
        throw e
    } catch (_: Exception) {
        false
    }

    fun invalid(message: String, cause: Throwable? = null): NemiOAuthException =
        NemiOAuthException(NemiOAuthException.INVALID_ID_TOKEN, message, cause = cause)
}
