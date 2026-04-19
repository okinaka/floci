package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServerFactory;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerLauncherTest {

    @Mock ContainerLifecycleManager lifecycleManager;
    @Mock ContainerLogStreamer logStreamer;
    @Mock ImageResolver imageResolver;
    @Mock RuntimeApiServerFactory runtimeApiServerFactory;
    @Mock DockerHostResolver dockerHostResolver;
    @Mock EmulatorConfig config;
    @Mock EcrRegistryManager ecrRegistryManager;
    @Mock RuntimeApiServer runtimeApiServer;
    @Mock DockerClient dockerClient;
    @Mock CopyArchiveToContainerCmd copyCmd;

    @TempDir
    Path tempDir;

    ContainerLauncher launcher;

    @BeforeEach
    void setUp() {
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);

        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker()).thenReturn(docker);
        when(docker.logMaxSize()).thenReturn("10m");
        when(docker.logMaxFile()).thenReturn("3");

        ContainerBuilder containerBuilder = new ContainerBuilder(config, dockerHostResolver);
        launcher = new ContainerLauncher(containerBuilder, lifecycleManager, logStreamer, imageResolver,
                runtimeApiServerFactory, dockerHostResolver, config, ecrRegistryManager);

        when(runtimeApiServerFactory.create()).thenReturn(runtimeApiServer);
        when(runtimeApiServer.getPort()).thenReturn(9000);
        when(dockerHostResolver.resolve()).thenReturn("127.0.0.1");

        when(lifecycleManager.create(any())).thenReturn("container-123");
        ContainerLifecycleManager.ContainerInfo info =
                new ContainerLifecycleManager.ContainerInfo("container-123", Map.of());
        when(lifecycleManager.startCreated(eq("container-123"), any())).thenReturn(info);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        // Stub the Docker copy chain so copyDirToContainer / copyFileToContainer
        // don't throw when the mock DockerClient is used.
        when(dockerClient.copyArchiveToContainerCmd(any())).thenReturn(copyCmd);
        when(copyCmd.withRemotePath(any())).thenReturn(copyCmd);
        when(copyCmd.withTarInputStream(any())).thenReturn(copyCmd);
    }

    @Test
    void launchFunction_createsWithoutBindMounts() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("code"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("standard-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).create(specCaptor.capture());

        ContainerSpec spec = specCaptor.getValue();
        assertTrue(spec.binds().isEmpty(), "Function should NOT have bind mounts");
    }

    @Test
    void launchFunction_createsBeforeCopyAndStartsAfter() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("code"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("order-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        // Verify ordering: create → Docker copy (to /var/task) → startCreated
        InOrder inOrder = inOrder(lifecycleManager, dockerClient);
        inOrder.verify(lifecycleManager).create(any());
        inOrder.verify(dockerClient).copyArchiveToContainerCmd("container-123");
        inOrder.verify(lifecycleManager).startCreated(eq("container-123"), any());

        // createAndStart must NOT be called — Lambda uses the split path
        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void launchProvidedRuntime_copiesBootstrapBeforeStart() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("provided-code"));
        Files.writeString(codePath.resolve("bootstrap"), "#!/bin/sh\necho hello");

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("provided-fn");
        fn.setRuntime("provided.al2023");
        fn.setHandler("bootstrap");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        // The critical invariant: create must happen before any Docker copy,
        // and start must happen after. This is the exact regression from #466.
        InOrder inOrder = inOrder(lifecycleManager, dockerClient);
        inOrder.verify(lifecycleManager).create(any());
        // Two copies: code to /var/task + bootstrap to /var/runtime
        inOrder.verify(dockerClient, times(2)).copyArchiveToContainerCmd("container-123");
        inOrder.verify(lifecycleManager).startCreated(eq("container-123"), any());

        // Verify both /var/task and /var/runtime were targeted
        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(copyCmd, atLeast(2)).withRemotePath(pathCaptor.capture());
        assertTrue(pathCaptor.getAllValues().contains("/var/task"));
        assertTrue(pathCaptor.getAllValues().contains("/var/runtime"));

        verify(lifecycleManager, never()).createAndStart(any());
    }
}
