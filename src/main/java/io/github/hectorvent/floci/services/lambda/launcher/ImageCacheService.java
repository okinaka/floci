package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.AuthConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Ensures each Docker image is pulled only once.
 * Thread-safe using ConcurrentHashMap for double-checked locking per image.
 */
@ApplicationScoped
public class ImageCacheService {

    private static final Logger LOG = Logger.getLogger(ImageCacheService.class);

    static final int MAX_PULL_ATTEMPTS = 3;
    static final long INITIAL_BACKOFF_MS = 500L;

    private final DockerClient dockerClient;
    private final List<EmulatorConfig.DockerConfig.RegistryCredential> registryCredentials;
    private final Set<String> pulledImages = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Inject
    public ImageCacheService(DockerClient dockerClient, EmulatorConfig config) {
        this.dockerClient = dockerClient;
        this.registryCredentials = config.docker().registryCredentials();
    }

    public void ensureImageExists(String imageUri) {
        if (pulledImages.contains(imageUri)) {
            return;
        }
        Object lock = locks.computeIfAbsent(imageUri, k -> new Object());
        synchronized (lock) {
            if (pulledImages.contains(imageUri)) {
                return;
            }
            if (isLocalImagePresent(imageUri)) {
                pulledImages.add(imageUri);
                LOG.infov("Image already present locally, skipping pull: {0}", imageUri);
                return;
            }
            LOG.infov("Pulling image: {0}", imageUri);
            try {
                runWithRetry(imageUri, MAX_PULL_ATTEMPTS, INITIAL_BACKOFF_MS,
                        () -> dockerClient.pullImageCmd(imageUri)
                                .withAuthConfig(resolveAuth(imageUri))
                                .exec(new PullImageResultCallback())
                                .awaitCompletion(5, TimeUnit.MINUTES));
                pulledImages.add(imageUri);
                LOG.infov("Image pulled successfully: {0}", imageUri);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while pulling image: " + imageUri, e);
            }
        }
    }

    /**
     * Runs the given pull attempt, retrying on transient registry failures with exponential
     * backoff. A failure is considered transient when the docker daemon returns HTTP 500
     * ({@link InternalServerErrorException}) — most notably ECR Public's
     * {@code "toomanyrequests: Rate exceeded"} 500. Permanent failures (auth, missing image,
     * malformed request) keep surfacing their original docker-java exception subclass on the
     * first attempt and are not retried. (docker-java 3.7.x only maps 500 to a dedicated
     * subclass; other 5xx responses surface as the generic {@code DockerException} and are
     * also left to the caller without retry.)
     */
    static void runWithRetry(String imageUri, int maxAttempts, long initialBackoffMs,
                             PullAttempt attempt) throws InterruptedException {
        long backoffMs = initialBackoffMs;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                attempt.run();
                return;
            } catch (InternalServerErrorException e) {
                if (i == maxAttempts) {
                    throw e;
                }
                LOG.warnv("Transient image pull failure for {0} (attempt {1}/{2}): {3}. "
                        + "Retrying in {4}ms.", imageUri, i, maxAttempts, e.getMessage(), backoffMs);
                Thread.sleep(backoffMs);
                backoffMs *= 2;
            }
        }
    }

    @FunctionalInterface
    interface PullAttempt {
        void run() throws InterruptedException;
    }

    private boolean isLocalImagePresent(String imageUri) {
        try {
            dockerClient.inspectImageCmd(imageUri).exec();
            return true;
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            return false;
        } catch (Exception e) {
            LOG.debugv("Could not check local image presence for {0}: {1}", imageUri, e.getMessage());
            return false;
        }
    }

    private AuthConfig resolveAuth(String imageUri) {
        String host = extractRegistryHost(imageUri);
        for (EmulatorConfig.DockerConfig.RegistryCredential cred : registryCredentials) {
            if (cred.server().equals(host)) {
                LOG.debugv("Using configured credentials for registry: {0}", host);
                return new AuthConfig()
                        .withUsername(cred.username())
                        .withPassword(cred.password())
                        .withRegistryAddress(cred.server());
            }
        }
        return new AuthConfig();
    }

    static String extractRegistryHost(String imageUri) {
        String firstSegment = imageUri.split("/")[0];
        return (firstSegment.contains(".") || firstSegment.contains(":")) ? firstSegment : "";
    }
}
