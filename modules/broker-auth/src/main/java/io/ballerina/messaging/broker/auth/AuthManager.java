/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package io.ballerina.messaging.broker.auth;

import io.ballerina.messaging.broker.auth.authentication.authenticator.Authenticator;
import io.ballerina.messaging.broker.auth.authentication.authenticator.AuthenticatorFactory;
import io.ballerina.messaging.broker.auth.authentication.sasl.BrokerSecurityProvider;
import io.ballerina.messaging.broker.auth.authentication.sasl.SaslServerBuilder;
import io.ballerina.messaging.broker.auth.authentication.sasl.plain.PlainSaslServerBuilder;
import io.ballerina.messaging.broker.common.BrokerConfigProvider;
import io.ballerina.messaging.broker.common.StartupContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Security;
import java.util.HashMap;
import java.util.Map;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;

/**
 * Class to manage authentication and authorization of message broker incoming connections.
 * This has a list of sasl servers registered by the message broker which will be used during authentication of incoming
 * connections.
 */
public class AuthManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthManager.class);
    /**
     * Map of SASL Server mechanisms
     */
    private Map<String, SaslServerBuilder> saslMechanisms = new HashMap<>();

    private BrokerAuthConfiguration brokerAuthConfiguration;
    /**
     * Authenticator which defines the authentication strategy for auth manager.
     */
    private Authenticator authenticator;

    public AuthManager(StartupContext startupContext)  throws Exception {
        BrokerConfigProvider configProvider = startupContext.getService(BrokerConfigProvider.class);
        brokerAuthConfiguration = configProvider
                .getConfigurationObject(BrokerAuthConfiguration.NAMESPACE, BrokerAuthConfiguration.class);
        startupContext.registerService(AuthManager.class, this);
        authenticator = new AuthenticatorFactory().getAuthenticator(startupContext,
                                                                    brokerAuthConfiguration.getAuthentication());
    }

    public void start() {
        if (isAuthenticationEnabled()) {
            registerSaslServers();
        }
    }

    public void stop() {
        LOGGER.info("Broker auth manager service stopped.");
    }

    /**
     * Register security provider mechanisms
     */
    private void registerSaslServers() {
        // create PLAIN SaslServer builder
        PlainSaslServerBuilder plainSaslServerBuilder = new PlainSaslServerBuilder();
        saslMechanisms.put(plainSaslServerBuilder.getMechanismName(), plainSaslServerBuilder);
        // Register given Sasl Server factories
        if (Security
                .insertProviderAt(new BrokerSecurityProvider(BrokerAuthConstants.PROVIDER_NAME, saslMechanisms), 1)
                == -1) {
            LOGGER.info("AMQ security authentication providers are already installed.");
        } else {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("AMQ security authentication mechanisms providers are successfully registered.");
            }
        }
    }

    /**
     * Create sasl server for given mechanism
     *
     * @param hostName  Hostname of the server
     * @param mechanism Sasl mechanism
     * @return Sasl server created for mechanism
     * @throws SaslException Throws if server does not support for given mechanism
     */
    public SaslServer createSaslServer(String hostName, String mechanism) throws SaslException {
        SaslServerBuilder saslServerBuilder = saslMechanisms.get(mechanism);
        if (saslServerBuilder != null) {
            SaslServer saslServer = Sasl.createSaslServer(mechanism, BrokerAuthConstants.AMQP_PROTOCOL_IDENTIFIER,
                                                          hostName,
                                                          saslServerBuilder.getProperties(),
                                                          saslServerBuilder.getCallbackHandler());
            if (saslServer != null) {
                return saslServer;
            } else {
                throw new SaslException("Sasl server cannot be found for mechanism: " + mechanism);
            }
        } else {
            throw new SaslException("Server does not support for mechanism: " + mechanism);
        }
    }

    /**
     * Provides broker authentication enabled.
     * @return broker authentication enabled or not
     */
    public boolean isAuthenticationEnabled() {
        return brokerAuthConfiguration.getAuthentication().isEnabled();
    }

    /**
     * Provides authenticator which will be used to authenticate users.
     * @return broker authenticator
     */
    public Authenticator getAuthenticator() {
        return authenticator;
    }
}
