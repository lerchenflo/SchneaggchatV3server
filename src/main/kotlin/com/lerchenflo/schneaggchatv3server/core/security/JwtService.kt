package com.lerchenflo.schneaggchatv3server.core.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.*

@Service
class JwtService(
    //Inject jwt secret from .env -> docker-compose.yaml -> application.properties -> here
    @Value($$"${jwt.secret}") private val jwtSecret: String
) {
    fun getEncryptionKey() : String {
        return jwtSecret.take(20)
    }

    private val secretKey = Keys.hmacShaKeyFor(jwtSecret.toByteArray())
    //private val accessTokenValidityMs = 15L /*min*/ * 60L * 1000L    //How a user can use his access token
    private val accessTokenValidityMs = 10L * 1000L    //How a user can use his access token

    val refreshTokenValidityMs = 30L /*days*/ * 24L * 60L * 60L * 1000L
    private val emailTokenValidityMs = 24L * 60L * 60L * 1000L


    private fun generateToken(
        userId: String,
        type: String,
        expiry: Long
    ): String {

        val now = Date()
        val expiryDate = Date(now.time + expiry)

        return Jwts.builder()
            .setHeaderParam("typ", type)
            .subject(userId)
            .claim("type", type)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }

    fun generateAccessToken(userId: String): String {
        return generateToken(
            userId = userId,
            type = "access_token",
            expiry = accessTokenValidityMs
        )
    }

    fun generateRefreshToken(userId: String): String {

        val expiryDate = Date(System.currentTimeMillis() + refreshTokenValidityMs)
        //println("Refresh token created for user $userId - Expires at: $expiryDate")

        return generateToken(
            userId = userId,
            type = "refresh_token",
            expiry = refreshTokenValidityMs
        )
    }


    fun validateAccessToken(accessToken: String): Boolean {
        val claims = parseAllClaims(accessToken) ?: return false
        val tokentype = claims["type"] as? String ?: return false
        return tokentype == "access_token"
    }

    fun validateRefreshToken(refreshToken: String): Boolean {
        val claims = parseAllClaims(refreshToken) ?: run {
            return false
        }
        val tokentype = claims["type"] as? String ?: run {
            return false
        }
        return tokentype == "refresh_token"
    }

    // Authorization: Bearer <Token>
    fun getUserIdFromToken(token: String): String {
        val claims = parseAllClaims(token)
            ?: throw ResponseStatusException(HttpStatusCode.valueOf(401), "Invalid token")
        return claims.subject
    }



    private fun parseAllClaims(token: String): Claims? {
        val rawToken = token.replace("Bearer ", "")

        if (rawToken.isBlank()) {
            return null
        }

        return try {
            Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(rawToken)
                .payload
        } catch (e: Exception){
            //e.printStackTrace()
            null
        }
    }


    fun generateEmailToken(
        userId: String,
        email: String
    ): String {

        val now = Date()
        val expiryDate = Date(now.time + emailTokenValidityMs) //15 min valid

        return Jwts.builder()
            .subject(userId)
            .claim("type", "emailverification")
            .claim("email", email)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }

    /**
     * Validate an email token. returns either null if not valid or the email which got validated and the userid
     */
    fun validateEmailToken(token: String): Pair<String, ObjectId>? {
        val claims = parseAllClaims(token) ?: return null
        val tokentype = claims["type"] as? String ?: return null
        if (tokentype == "emailverification"
            && claims["email"] is String){

            val userid = ObjectId(getUserIdFromToken(token))
            val email = claims["email"] as String
            return Pair(email, userid)
        }
        return null
    }




    fun generateDelAccEmailToken(
        userId: String,
        email: String
    ): String {

        val now = Date()
        val expiryDate = Date(now.time + emailTokenValidityMs) //24 hours valid

        return Jwts.builder()
            .subject(userId)
            .claim("type", "accountdeletion")
            .claim("email", email)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }

    fun generateDelAccConfirmToken(
        userId: String,
        email: String
    ): String {

        val now = Date()
        val expiryDate = Date(now.time + (30 * 60 * 1000L)) //30 minutes valid

        return Jwts.builder()
            .subject(userId)
            .claim("type", "accountdeletionconfirm")
            .claim("email", email)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }

    /**
     * Validate an email token. returns either null if not valid or the email which got validated and the userid
     */
    fun validateDelAccEmailToken(token: String): Pair<String, ObjectId>? {
        val claims = parseAllClaims(token) ?: return null
        val tokentype = claims["type"] as? String ?: return null
        if (tokentype == "accountdeletion"
            && claims["email"] is String){

            val userid = ObjectId(getUserIdFromToken(token))
            val email = claims["email"] as String
            return Pair(email, userid)
        }
        return null
    }

    /**
     * Validate a confirmation token. returns either null if not valid or the email and userid
     */
    fun validateDelAccConfirmToken(token: String): Pair<String, ObjectId>? {
        val claims = parseAllClaims(token) ?: return null
        val tokentype = claims["type"] as? String ?: return null
        if (tokentype == "accountdeletionconfirm"
            && claims["email"] is String){

            val userid = ObjectId(getUserIdFromToken(token))
            val email = claims["email"] as String
            return Pair(email, userid)
        }
        return null
    }


    fun generatePasswordResetToken(
        userId: String,
        email: String
    ): String {

        val now = Date()
        val expiryDate = Date(now.time + (60 * 60 * 1000L)) //1 hour valid

        return Jwts.builder()
            .subject(userId)
            .claim("type", "passwordreset")
            .claim("email", email)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }

    /**
     * Validate a password reset token. returns either null if not valid or the email and userid
     */
    fun validatePasswordResetToken(token: String): Pair<String, ObjectId>? {
        val claims = parseAllClaims(token) ?: return null
        val tokentype = claims["type"] as? String ?: return null
        if (tokentype == "passwordreset"
            && claims["email"] is String){

            val userid = ObjectId(getUserIdFromToken(token))
            val email = claims["email"] as String
            return Pair(email, userid)
        }
        return null
    }


}