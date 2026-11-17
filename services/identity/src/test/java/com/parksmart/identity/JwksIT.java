package com.parksmart.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.parksmart.common.security.JwtProperties;
import com.parksmart.common.security.ParkSmartJwt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Proves another service can verify Identity's tokens using only the published JWKS. */
class JwksIT extends IntegrationTest {

  @Test
  void jwks_publishes_only_the_public_key_and_verifies_issued_tokens() throws Exception {
    String jwksJson =
        mvc.perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JWKSet jwks = JWKSet.parse(jwksJson);
    assertThat(jwks.getKeys()).hasSize(1);
    RSAKey key = jwks.getKeys().get(0).toRSAKey();
    assertThat(key.isPrivate()).isFalse();
    assertThat(jwksJson).doesNotContain("\"d\"");

    String email = uniqueEmail();
    UUID id = register(email);
    String accessToken = login(email, PASSWORD).get("accessToken").asText();

    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    decoder.setJwtValidator(
        ParkSmartJwt.validator(
            new JwtProperties("https://identity.parksmart.test", "parksmart-api", null)));
    Jwt jwt = decoder.decode(accessToken);

    assertThat(jwt.getSubject()).isEqualTo(id.toString());
    assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", key.getKeyID());
    assertThat(jwt.getAudience()).containsExactly("parksmart-api");
    assertThat(jwt.getClaimAsStringList("roles")).containsExactly("DRIVER");
    assertThat(jwt.getId()).isNotBlank();
    assertThat(jwt.getClaims()).doesNotContainKeys("email", "phone");
  }
}
