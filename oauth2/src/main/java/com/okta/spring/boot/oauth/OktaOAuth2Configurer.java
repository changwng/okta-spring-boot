/*
 * Copyright 2018-Present Okta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.okta.spring.boot.oauth;

import com.okta.commons.configcheck.ConfigurationValidator;
import com.okta.spring.boot.oauth.config.OktaOAuth2Properties;
import com.okta.spring.boot.oauth.http.UserAgentRequestInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;

import static org.springframework.util.StringUtils.isEmpty;

final class OktaOAuth2Configurer extends AbstractHttpConfigurer<OktaOAuth2Configurer, HttpSecurity> {

    private static final Logger log = LoggerFactory.getLogger(OktaOAuth2Configurer.class);

    @Override
    public void init(HttpSecurity http) throws Exception {

        ApplicationContext context = http.getSharedObject(ApplicationContext.class);

        // make sure OktaOAuth2Properties are available
        if (!context.getBeansOfType(OktaOAuth2Properties.class).isEmpty()) {
            OktaOAuth2Properties oktaOAuth2Properties = context.getBean(OktaOAuth2Properties.class);

            // if OAuth2ClientProperties bean is not available do NOT configure
            if (!context.getBeansOfType(OAuth2ClientProperties.class).isEmpty()
                && !isEmpty(oktaOAuth2Properties.getIssuer())
                && !isEmpty(oktaOAuth2Properties.getClientId())) {
                // configure Okta user services
                configureLogin(http, oktaOAuth2Properties);

                // check for RP-Initiated logout
                if (!context.getBeansOfType(OidcClientInitiatedLogoutSuccessHandler.class).isEmpty()) {
                    http.logout().logoutSuccessHandler(context.getBean(OidcClientInitiatedLogoutSuccessHandler.class));
                }

            } else {
                log.debug("OAuth/OIDC Login not configured due to missing issuer, client-id, or client-secret property");
            }

            // resource server configuration
            if (!context.getBeansOfType(OAuth2ResourceServerProperties.class).isEmpty()) {
                OAuth2ResourceServerProperties resourceServerProperties = context.getBean(OAuth2ResourceServerProperties.class);

                log.debug("isOpaqueTokenValidationRequired()?: {}", isOpaqueTokenValidationRequired(oktaOAuth2Properties, resourceServerProperties));
                log.debug("isRootOrgIssuer(resourceServerProperties.getJwt().getIssuerUri())?: {}", isRootOrgIssuer(resourceServerProperties.getJwt().getIssuerUri()));
                log.debug("isEmpty(resourceServerProperties.getJwt().getIssuerUri()?: {}", isEmpty(resourceServerProperties.getJwt().getIssuerUri()));

                if (isOpaqueTokenValidationRequired(oktaOAuth2Properties, resourceServerProperties) ||
                    !isRootOrgIssuer(resourceServerProperties.getJwt().getIssuerUri())) {
                    log.debug("Configuring resource server for Opaque Token validation");
                    configureResourceServerWithOpaqueTokenValidation(http, oktaOAuth2Properties, resourceServerProperties);
                } else if (!isEmpty(resourceServerProperties.getJwt().getIssuerUri())) {
                    log.debug("Configuring resource server for JWT validation");
                    configureResourceServerWithJwtValidation(http, oktaOAuth2Properties);
                }
            } else {
                log.debug("OAuth resource server not configured due to missing OAuth2ResourceServerProperties bean");
            }
        }
    }

    private void configureLogin(HttpSecurity http, OktaOAuth2Properties oktaOAuth2Properties) throws Exception {

        http.oauth2Login()
                .tokenEndpoint()
                    .accessTokenResponseClient(accessTokenResponseClient());

        if (oktaOAuth2Properties.getRedirectUri() != null) {
            http.oauth2Login().redirectionEndpoint().baseUri(oktaOAuth2Properties.getRedirectUri());
        }
    }

    private void configureResourceServerWithJwtValidation(HttpSecurity http, OktaOAuth2Properties oktaOAuth2Properties) throws Exception {

        http.oauth2ResourceServer()
            .jwt().jwtAuthenticationConverter(new OktaJwtAuthenticationConverter(oktaOAuth2Properties.getGroupsClaim()));
    }

    private void configureResourceServerWithOpaqueTokenValidation(HttpSecurity http,
                                                                  OktaOAuth2Properties oktaOAuth2Properties,
                                                                  OAuth2ResourceServerProperties resourceServerProperties)
        throws Exception {

        OpaqueTokenIntrospector opaqueTokenIntrospector = new OktaOpaqueTokenIntrospector(
            resourceServerProperties.getOpaquetoken().getIntrospectionUri(),
            oktaOAuth2Properties.getClientId(),
            oktaOAuth2Properties.getClientSecret(),
            restTemplate());

        http.oauth2ResourceServer()
            .opaqueToken().introspector(opaqueTokenIntrospector);
    }

    private OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> accessTokenResponseClient() {

        DefaultAuthorizationCodeTokenResponseClient accessTokenResponseClient = new DefaultAuthorizationCodeTokenResponseClient();
        accessTokenResponseClient.setRestOperations(restTemplate());
        return accessTokenResponseClient;
    }

    private RestTemplate restTemplate() {

        RestTemplate restTemplate = new RestTemplate(Arrays.asList(
            new FormHttpMessageConverter(),
            new OAuth2AccessTokenResponseHttpMessageConverter(),
            new StringHttpMessageConverter()));
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        restTemplate.getInterceptors().add(new UserAgentRequestInterceptor());
        return restTemplate;
    }

    private boolean isOpaqueTokenValidationRequired(OktaOAuth2Properties oktaOAuth2Properties,
                                                    OAuth2ResourceServerProperties resourceServerProperties) {
        return oktaOAuth2Properties.isOpaque()
            && !isEmpty(resourceServerProperties.getOpaquetoken().getClientId())
            && !isEmpty(resourceServerProperties.getOpaquetoken().getClientSecret())
            && !isEmpty(resourceServerProperties.getOpaquetoken().getIntrospectionUri());
    }

    private boolean isRootOrgIssuer(String issuerUri) {
        try {
            ConfigurationValidator.assertOrgUrl(issuerUri);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return true;
    }
}