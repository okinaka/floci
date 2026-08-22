package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dedicated IP pools (the {@code dedicatedIpPoolStore}), extracted from {@link SesService} as part of
 * the store-based domain split. A clean leaf reached through the {@code SesService}
 * facade, which delegates here; the facade's configuration-set delivery-options validation also
 * checks pool existence through {@link #dedicatedIpPoolExists}.
 */
@ApplicationScoped
public class SesDedicatedIpService {

    private static final Logger LOG = Logger.getLogger(SesDedicatedIpService.class);

    private static final Set<String> SCALING_MODES = Set.of("STANDARD", "MANAGED");

    private final StorageBackend<String, DedicatedIpPool> dedicatedIpPoolStore;

    @Inject
    public SesDedicatedIpService(StorageFactory storageFactory) {
        this.dedicatedIpPoolStore = storageFactory.create("ses", "ses-dedicated-ip-pools.json",
                new TypeReference<Map<String, DedicatedIpPool>>() {});
    }

    SesDedicatedIpService(StorageBackend<String, DedicatedIpPool> dedicatedIpPoolStore) {
        this.dedicatedIpPoolStore = dedicatedIpPoolStore;
    }

    public DedicatedIpPool createDedicatedIpPool(String poolName, String scalingMode, String region) {
        if (poolName == null || poolName.isBlank()) {
            throw new AwsException("BadRequestException", "PoolName is required.", 400);
        }
        String effectiveScaling = (scalingMode == null || scalingMode.isBlank()) ? "STANDARD" : scalingMode;
        if (!SCALING_MODES.contains(effectiveScaling)) {
            throw new AwsException("BadRequestException", "The ScalingMode parameter is invalid.", 400);
        }
        String key = dedicatedIpPoolKey(region, poolName);
        if (dedicatedIpPoolStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException",
                    "The pool <" + poolName + "> already exists.", 400);
        }
        DedicatedIpPool pool = new DedicatedIpPool(poolName, effectiveScaling);
        dedicatedIpPoolStore.put(key, pool);
        LOG.infov("Created SES dedicated IP pool: {0} in region {1}", poolName, region);
        return pool;
    }

    public DedicatedIpPool getDedicatedIpPool(String poolName, String region) {
        return dedicatedIpPoolStore.get(dedicatedIpPoolKey(region, poolName))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "The requested pool <" + poolName + "> does not exist.", 404));
    }

    public boolean dedicatedIpPoolExists(String poolName, String region) {
        return dedicatedIpPoolStore.get(dedicatedIpPoolKey(region, poolName)).isPresent();
    }

    public List<String> listDedicatedIpPools(String region) {
        String prefix = "dedicatedIpPool::" + region + "::";
        return dedicatedIpPoolStore.scan(k -> k.startsWith(prefix)).stream()
                .map(DedicatedIpPool::getPoolName)
                .sorted()
                .toList();
    }

    public void deleteDedicatedIpPool(String poolName, String region) {
        String key = dedicatedIpPoolKey(region, poolName);
        if (dedicatedIpPoolStore.get(key).isEmpty()) {
            throw new AwsException("NotFoundException",
                    "The requested pool <" + poolName + "> does not exist.", 404);
        }
        dedicatedIpPoolStore.delete(key);
        LOG.infov("Deleted SES dedicated IP pool: {0} in region {1}", poolName, region);
    }

    // ──────────────────────── Dedicated IPs (IP-level) ────────────────────────
    //
    // SES has no API to create a dedicated IP — they are leased out-of-band
    // (STANDARD) or auto-provisioned by AWS (MANAGED). Floci does not model that,
    // so an account has no dedicated IPs: GetDedicatedIps is empty and any
    // IP-targeted operation reports the IP as not found, matching real AWS for an
    // account with no leased IPs (verified 2026-06-21).

    private AwsException dedicatedIpNotFound(String ip) {
        return new AwsException("NotFoundException",
                "Could not find dedicated IP <" + ip + "> under this account.", 404);
    }

    public DedicatedIpPool getDedicatedIp(String ip, String region) {
        throw dedicatedIpNotFound(ip);
    }

    public void putDedicatedIpInPool(String ip, String destinationPoolName, String region) {
        // AWS validates the required DestinationPoolName before it checks the IP.
        if (destinationPoolName == null || destinationPoolName.isBlank()) {
            throw new AwsException("BadRequestException", "Pool name can't be blank.", 400);
        }
        // AWS checks the IP before the destination pool.
        throw dedicatedIpNotFound(ip);
    }

    public void putDedicatedIpWarmupAttributes(String ip, String region) {
        throw dedicatedIpNotFound(ip);
    }

    public void putDedicatedIpPoolScalingAttributes(String poolName, String scalingMode, String region) {
        // AWS validates ScalingMode before it checks that the pool exists.
        if (scalingMode == null || !SCALING_MODES.contains(scalingMode)) {
            throw new AwsException("BadRequestException", "The ScalingMode parameter is invalid.", 400);
        }
        String key = dedicatedIpPoolKey(region, poolName);
        DedicatedIpPool pool = dedicatedIpPoolStore.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "The requested pool <" + poolName + "> does not exist.", 404));
        // AWS rejects downgrading a MANAGED pool back to STANDARD.
        if ("MANAGED".equals(pool.getScalingMode()) && "STANDARD".equals(scalingMode)) {
            throw new AwsException("BadRequestException", "The ScalingMode parameter is invalid.", 400);
        }
        pool.setScalingMode(scalingMode);
        dedicatedIpPoolStore.put(key, pool);
        LOG.infov("Updated ScalingMode on dedicated IP pool {0} in region {1}: {2}",
                poolName, region, scalingMode);
    }

    private static String dedicatedIpPoolKey(String region, String name) {
        return "dedicatedIpPool::" + region + "::" + name;
    }
}
