package com.cloudinfra.auth;

import com.cloudinfra.test.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AuthIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void testRefreshTokenInvalid() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Refresh-Token", "invalid-token");
        
        ResponseEntity<Map> response = restTemplate.postForEntity("/auth/refresh", new HttpEntity<>(headers), Map.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("code")).isEqualTo(1003); // AuthExceptionCode.TOKEN_INVALID.getCode()
        assertThat(body).containsKey("traceId");
    }
}
