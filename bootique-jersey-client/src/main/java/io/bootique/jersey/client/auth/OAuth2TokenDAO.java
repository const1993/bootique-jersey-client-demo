package io.bootique.jersey.client.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.bind.annotation.XmlAttribute;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Function;

/**
 * @since 0.25
 */
public class OAuth2TokenDAO {

    private static final Logger LOGGER = LoggerFactory.getLogger(OAuth2TokenDAO.class);

    protected String tokenUrl;
    protected String username;
    protected String password;
    protected Duration expiresIn;

    protected Function<String, WebTarget> tokenTargetFactory;
    protected volatile WebTarget tokenTarget;

    public OAuth2TokenDAO(
            Function<String, WebTarget> tokenTargetFactory,
            String tokenUrl,
            String username,
            String password,
            Duration expiresIn) {

        this.tokenTargetFactory = tokenTargetFactory;
        this.tokenUrl = tokenUrl;
        this.username = username;
        this.password = password;
        this.expiresIn = expiresIn;
    }

    public OAuth2Token getToken() {

        // per https://tools.ietf.org/html/rfc6749#section-6  "client_credentials" grant should just reauthenticate
        // and not attempt to use a refresh token.

        Response tokenResponse = requestNewToken();

        try {
            OAuth2Token token = readToken(tokenResponse);
            LOGGER.info("Successfully obtained OAuth2 token. Expires on {}", token.getRefreshAfter());
            return token;
        } finally {
            tokenResponse.close();
        }
    }

    protected Response requestNewToken() {

        // "4.4.2. Access Token Request" https://tools.ietf.org/html/rfc6749#section-4.4.2

        LOGGER.info("reading OAuth2 token from " + tokenUrl);

        Entity<String> postEntity = Entity
                .entity("grant_type=client_credentials", MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        return getTokenTarget()
                .request()
                .header("Authorization", BasicAuthenticatorFactory.BasicAuthenticator.createBasicAuth(username, password))
                .post(postEntity);
    }

    protected WebTarget getTokenTarget() {

        // not concerned about concurrent access and potential multiple instances creation...
        if (tokenTarget == null) {
            tokenTarget = tokenTargetFactory.apply(tokenUrl);
        }

        return tokenTarget;
    }

    protected OAuth2Token readToken(Response response) {

        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            String json = response.readEntity(String.class);
            String message = String.format("Error reading token: %s ... %s", response.getStatus(), json);
            throw new RuntimeException(message);
        }

        TokenDTO token = response.readEntity(TokenDTO.class);
        Objects.requireNonNull(token);

        LocalDateTime expiresOn = LocalDateTime.now().plus(token.getDuration(expiresIn));
        return OAuth2Token.token(token.accessToken, expiresOn);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenDTO {

        @XmlAttribute(name = "access_token")
        String accessToken;

        @XmlAttribute(name = "expires_in")
        Integer expiresIn;

        Duration getDuration(Duration defaultTokenDuration) {
            return expiresIn != null ? Duration.ofSeconds(expiresIn) : defaultTokenDuration;
        }
    }
}
