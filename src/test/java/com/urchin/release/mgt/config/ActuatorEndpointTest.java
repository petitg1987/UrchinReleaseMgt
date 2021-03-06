package com.urchin.release.mgt.config;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class ActuatorEndpointTest {

    private RequestSpecification restAssured;

    @LocalServerPort
    int randomServerPort;

    @BeforeEach
    public void setup() {
        restAssured = RestAssured.given().baseUri("http://localhost:" + randomServerPort + "/");
    }

    @Test
    public void checkSecure(){
        restAssured.get("actuator/health").then().statusCode(401);
    }

}
