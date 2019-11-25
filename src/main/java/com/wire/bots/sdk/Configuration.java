//
// Wire
// Copyright (C) 2016 Wire Swiss GmbH
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see http://www.gnu.org/licenses/.
//

package com.wire.bots.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.client.JerseyClientConfiguration;
import io.federecio.dropwizard.swagger.SwaggerBundleConfiguration;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

/**
 * Application configuration class. Extend this class to add your custom configuration
 */
public class Configuration extends io.dropwizard.Configuration {

    @JsonProperty("swagger")
    public SwaggerBundleConfiguration swagger;

    @Valid
    @NotNull
    public JerseyClientConfiguration jerseyClient = new JerseyClientConfiguration();

    @JsonProperty("jerseyClient")
    public JerseyClientConfiguration getJerseyClientConfiguration() {
        return jerseyClient;
    }

    @JsonProperty("jerseyClient")
    public void setJerseyClientConfiguration(JerseyClientConfiguration jerseyClient) {
        this.jerseyClient = jerseyClient;
    }

    /**
     * Authentication token
     */
    @JsonProperty
    @NotNull
    public String auth;

    /**
     * The storage for the State and Cryptobox
     */
    @NotNull
    @JsonProperty
    public DB db;

    /**
     * Set to True if you want to run the bot as the User. Requires email/password System properties set
     */
    @JsonProperty
    public UserMode userMode;

    @JsonProperty
    public String apiHost = "https://prod-nginz-https.wire.com";

    @JsonProperty
    public String wsHost = "wss://prod-nginz-ssl.wire.com/await";

    public static String propOrEnv(String prop, boolean strict) {
        final String env = prop.replace('.', '_').toUpperCase();
        final String val = System.getProperty(prop, System.getenv(env));
        if (val == null && strict) {
            throw new ConfigValueNotFoundException(prop + " (" + env + ") not found");
        }
        return val;
    }

    public static String propOrEnv(String prop, String def) {
        final String val = propOrEnv(prop, false);
        if (val == null) {
            return def;
        } else {
            return val;
        }
    }

    public String getAuth() {
        return auth;
    }

    public DB getDB() {
        return db;
    }

    public SwaggerBundleConfiguration getSwagger() {
        return swagger;
    }

    public static class DB {
        public String host;
        public Integer port;
        public String database = "";
        public String driver; // like: postgresql or fs
        public String user;
        public String password;
        public String url; // a database url of the form: jdbc:`subprotocol`:`subname` or `file:///path/to/data/folder`
        public Integer timeout = 5000;
    }

    public final static class ConfigValueNotFoundException extends RuntimeException {
        ConfigValueNotFoundException(String message) {
            super(message);
        }
    }

    public static class UserMode {
        public String email;
        public String password;
    }
}
