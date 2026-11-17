package com.parksmart.identity.api;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public signing keys for every other service (RFC 7517). Only the public half is exposed. */
@RestController
class JwksController {

  private final Map<String, Object> jwks;

  JwksController(RSAKey signingKey) {
    this.jwks = new JWKSet(signingKey.toPublicJWK()).toJSONObject(true);
  }

  @GetMapping("/.well-known/jwks.json")
  ResponseEntity<Map<String, Object>> jwks() {
    return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5))).body(jwks);
  }
}
