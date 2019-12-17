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

import com.codahale.metrics.Gauge;
import com.codahale.metrics.jmx.JmxReporter;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.wire.bots.sdk.crypto.CryptoDatabase;
import com.wire.bots.sdk.crypto.CryptoFile;
import com.wire.bots.sdk.crypto.storage.JdbiStorage;
import com.wire.bots.sdk.crypto.storage.RedisStorage;
import com.wire.bots.sdk.factories.CryptoFactory;
import com.wire.bots.sdk.factories.StorageFactory;
import com.wire.bots.sdk.healthchecks.Alice2Bob;
import com.wire.bots.sdk.healthchecks.CryptoHealthCheck;
import com.wire.bots.sdk.healthchecks.Outbound;
import com.wire.bots.sdk.healthchecks.StorageHealthCheck;
import com.wire.bots.sdk.server.filters.AuthenticationFeature;
import com.wire.bots.sdk.server.resources.BotsResource;
import com.wire.bots.sdk.server.resources.EmptyStatusResource;
import com.wire.bots.sdk.server.resources.MessageResource;
import com.wire.bots.sdk.server.resources.StatusResource;
import com.wire.bots.sdk.server.tasks.AvailablePrekeysTask;
import com.wire.bots.sdk.server.tasks.ConversationTask;
import com.wire.bots.sdk.state.FileState;
import com.wire.bots.sdk.state.JdbiState;
import com.wire.bots.sdk.state.RedisState;
import com.wire.bots.sdk.tools.Logger;
import com.wire.bots.sdk.user.UserApplication;
import io.dropwizard.Application;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.jdbi.DBIFactory;
import io.dropwizard.servlets.tasks.Task;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.federecio.dropwizard.swagger.SwaggerBundle;
import io.federecio.dropwizard.swagger.SwaggerBundleConfiguration;
import org.flywaydb.core.Flyway;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.skife.jdbi.v2.DBI;

import javax.ws.rs.client.Client;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for your Application
 *
 * @param <Config>
 */
public abstract class Server<Config extends Configuration> extends Application<Config> {
    protected ClientRepo repo;
    protected Config config;
    protected Environment environment;
    protected Client client;
    protected MessageHandlerBase messageHandler;
    protected DBI jdbi;

    /**
     * This method is called once by the sdk in order to create the main message handler
     *
     * @param config Configuration object (yaml)
     * @param env    Environment object
     * @return Instance of your class that implements {@see @MessageHandlerBase}
     */
    protected abstract MessageHandlerBase createHandler(Config config, Environment env) throws Exception;

    /**
     * Override this method to put your custom initialization
     * NOTE: ClientRepo is not yet set at this stage. messageHandler is also not set
     *
     * @param config Configuration object (yaml)
     * @param env    Environment object
     */
    protected void initialize(Config config, Environment env) throws Exception {

    }

    /**
     * Override this method in case you need to add custom Resource and/or Task
     * {@link #addResource(Object)}
     * and {@link #addTask(io.dropwizard.servlets.tasks.Task)}
     *
     * @param config Configuration object (yaml)
     * @param env    Environment object
     */
    protected void onRun(Config config, Environment env) throws Exception {

    }

    @Override
    public void initialize(Bootstrap<Config> bootstrap) {
        bootstrap.setConfigurationSourceProvider(
                new SubstitutingSourceProvider(bootstrap.getConfigurationSourceProvider(),
                        new EnvironmentVariableSubstitutor(false)
                )
        );

        bootstrap.addBundle(new SwaggerBundle<Config>() {
            @Override
            protected SwaggerBundleConfiguration getSwaggerBundleConfiguration(Config configuration) {
                return configuration.swagger;
            }
        });
    }

    @Override
    public void run(final Config config, Environment env) throws Exception {
        this.config = config;
        this.environment = env;

        migrateDBifNeeded(config.dataSourceFactory);
        
        this.jdbi = new DBIFactory().build(environment, config.dataSourceFactory, "lithium");

        // Override these values for Jersey Client just in case
        config.jerseyClient.setChunkedEncodingEnabled(false);
        config.jerseyClient.setGzipEnabled(false);
        config.jerseyClient.setGzipEnabledForRequests(false);

        client = new JerseyClientBuilder(environment)
                .using(config.jerseyClient)
                .withProvider(MultiPartFeature.class)
                .withProvider(JacksonJsonProvider.class)
                .build(getName());

        StorageFactory storageFactory = getStorageFactory();
        CryptoFactory cryptoFactory = getCryptoFactory();

        repo = new ClientRepo(client, cryptoFactory, storageFactory);

        initialize(config, env);

        messageHandler = createHandler(config, env);

        if (config.userMode != null) {
            runInUserMode();
        }

        runInBotMode();

        initTelemetry();

        onRun(config, env);
    }

    protected void migrateDBifNeeded(DataSourceFactory database) {
        Flyway flyway = Flyway
                .configure()
                .dataSource(database.getUrl(), database.getUser(), database.getPassword())
                .load();
        flyway.migrate();
    }

    public StorageFactory getStorageFactory() {
        if (config.db != null) {
            if (config.db.driver.equals("redis"))
                return (botId) -> new RedisState(botId, config.db);
            if (config.db.driver.equals("fs"))
                return botId -> new FileState(botId, config.db);

            return botId -> new JdbiState(botId, jdbi);
        }

        return botId -> new JdbiState(botId, jdbi);
    }

    public CryptoFactory getCryptoFactory() {
        if (config.db != null) {
            if (config.db.driver.equals("redis"))
                return (botId) -> new CryptoDatabase(botId, new RedisStorage(config.db.host, config.db.port, config.db.password));
            if (config.db.driver.equals("fs"))
                return (botId) -> new CryptoFile(botId, config.db);

            return (botId) -> new CryptoDatabase(botId, new JdbiStorage(jdbi));
        }

        return (botId) -> new CryptoDatabase(botId, new JdbiStorage(jdbi));
    }


    private void runInBotMode() {
        addResource(new StatusResource());
        addResource(new EmptyStatusResource());

        botResource();
        messageResource();

        addTask(new ConversationTask(repo));
        addTask(new AvailablePrekeysTask(repo));
    }

    private void runInUserMode() throws Exception {
        Logger.info("Starting in User Mode");

        UserApplication app = new UserApplication(environment)
                .addClient(client)
                .addConfig(config)
                .addCryptoFactory(getCryptoFactory())
                .addStorageFactory(getStorageFactory())
                .addHandler(messageHandler);

        app.run();
    }

    protected void messageResource() {
        addResource(new MessageResource(messageHandler, repo));
    }

    protected void botResource() {
        StorageFactory storageFactory = getStorageFactory();
        CryptoFactory cryptoFactory = getCryptoFactory();

        addResource(new BotsResource(messageHandler, storageFactory, cryptoFactory));
    }

    protected void addTask(Task task) {
        environment.admin().addTask(task);
    }

    protected void addResource(Object component) {
        environment.jersey().register(component);
    }

    private void initTelemetry() {
        final CryptoFactory cryptoFactory = getCryptoFactory();
        final StorageFactory storageFactory = getStorageFactory();

        registerFeatures();

        environment.healthChecks().register("Storage", new StorageHealthCheck(storageFactory));
        environment.healthChecks().register("Crypto", new CryptoHealthCheck(cryptoFactory));
        environment.healthChecks().register("Alice2Bob", new Alice2Bob(cryptoFactory));
        environment.healthChecks().register("Outbound", new Outbound(client));

        environment.metrics().register("logger.errors", (Gauge<Integer>) Logger::getErrorCount);
        environment.metrics().register("logger.warnings", (Gauge<Integer>) Logger::getWarningCount);

        JmxReporter jmxReporter = JmxReporter.forRegistry(environment.metrics())
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();
        jmxReporter.start();
    }

    protected void registerFeatures() {
        this.environment.jersey().register(AuthenticationFeature.class);
    }

    public ClientRepo getRepo() {
        return repo;
    }

    public Config getConfig() {
        return config;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public Client getClient() {
        return client;
    }
}
