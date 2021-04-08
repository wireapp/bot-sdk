package com.wire.lithium.healthchecks;

import com.codahale.metrics.health.HealthCheck;
import com.wire.lithium.server.monitoring.MDCUtils;
import com.wire.xenon.crypto.Crypto;
import com.wire.xenon.factories.CryptoFactory;
import com.wire.xenon.tools.Logger;

import java.util.UUID;

public class CryptoHealthCheck extends HealthCheck {
    private final CryptoFactory cryptoFactory;

    public CryptoHealthCheck(CryptoFactory cryptoFactory) {
        this.cryptoFactory = cryptoFactory;
    }

    @Override
    protected Result check() {
        try {
            MDCUtils.put("healthCheck", "CryptoHealthCheck"); // tag the logs with health check
            Logger.debug("Starting CryptoHealthCheck healthcheck");

            try (Crypto crypto = cryptoFactory.create(UUID.randomUUID())) {
                crypto.newLastPreKey();
                crypto.newPreKeys(0, 8);
                return Result.healthy();
            }
        } catch (Exception e) {
            Logger.exception("Exception during CryptoHealthCheck.", e);
            return Result.unhealthy(e.getMessage());
        } finally {
            Logger.debug("Finished CryptoHealthCheck healthcheck");
        }
    }
}
