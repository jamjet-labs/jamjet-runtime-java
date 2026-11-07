package dev.jamjet.runtime.server;

import dev.jamjet.runtime.core.state.InMemoryStateBackend;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import org.eclipse.jetty.server.ServerConnector;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ServerBindTest {

    @Test
    void theDefaultBindIsLoopbackOnly() throws Exception {
        Javalin app = JamjetServer.start(new InMemoryStateBackend(), new ServerConfig(0, "in-memory", null), null);
        try {
            assertThat(boundHosts(app)).containsExactly("127.0.0.1");
            try (Socket local = new Socket()) {
                local.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), app.port()), 2000);
                assertThat(local.isConnected()).isTrue();
            }
        } finally {
            app.stop();
        }
    }

    @Test
    void theDefaultBindIsNotReachableFromAnotherInterface() throws Exception {
        Javalin app = JamjetServer.start(new InMemoryStateBackend(), new ServerConfig(0, "in-memory", null), null);
        try {
            InetAddress lan = nonLoopbackAddress();
            assumeTrue(lan != null && reachableWhenBoundToAll(lan),
                    "no non-loopback address this JVM can connect to (sandbox); theDefaultBindIsLoopbackOnly covers the bound host");
            assertThatThrownBy(() -> {
                try (Socket remote = new Socket()) {
                    remote.connect(new InetSocketAddress(lan, app.port()), 2000);
                }
            }).isInstanceOf(IOException.class);
        } finally {
            app.stop();
        }
    }

    /** Control for the probe: a server on all interfaces must be reachable on {@code lan}. */
    private static boolean reachableWhenBoundToAll(InetAddress lan) {
        ServerConfig all = new ServerConfig(0, "in-memory", null, "0.0.0.0", "t0ken");
        Javalin open = JamjetServer.start(new InMemoryStateBackend(), all, null);
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(lan, open.port()), 2000);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            open.stop();
        }
    }

    private static List<String> boundHosts(Javalin app) {
        return Arrays.stream(app.jettyServer().server().getConnectors())
                .map(c -> ((ServerConnector) c).getHost())
                .map(String::valueOf)
                .toList();
    }

    @Test
    void theHostIsConfigurable() throws Exception {
        ServerConfig config = new ServerConfig(0, "in-memory", null, "0.0.0.0", "t0ken");
        Javalin app = JamjetServer.start(new InMemoryStateBackend(), config, null);
        try {
            assertThat(boundHosts(app)).containsExactly("0.0.0.0");
            try (Socket local = new Socket()) {
                local.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), app.port()), 2000);
                assertThat(local.isConnected()).isTrue();
            }
        } finally {
            app.stop();
        }
    }

    @Test
    void aNonLoopbackBindWithoutAuthWarns() {
        assertThat(JamjetServer.exposureWarning("0.0.0.0", false)).isPresent()
                .get().asString().contains("no authentication");
        assertThat(JamjetServer.exposureWarning("10.1.2.3", false)).isPresent();
        assertThat(JamjetServer.exposureWarning("0.0.0.0", true)).isEmpty();
        assertThat(JamjetServer.exposureWarning("127.0.0.1", false)).isEmpty();
        assertThat(JamjetServer.exposureWarning("localhost", false)).isEmpty();
        assertThat(JamjetServer.exposureWarning("::1", false)).isEmpty();
    }

    @Test
    void configDefaultsToLoopbackAndHidesTheToken() {
        assertThat(ServerConfig.defaults().host()).isEqualTo("127.0.0.1");
        assertThat(new ServerConfig(1, "in-memory", null, " ", " ").host()).isEqualTo("127.0.0.1");
        assertThat(new ServerConfig(1, "in-memory", null, " ", " ").apiToken()).isNull();
        assertThat(new ServerConfig(1, "in-memory", null, null, "s3cret").toString())
                .doesNotContain("s3cret");
    }

    private static InetAddress nonLoopbackAddress() throws Exception {
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp() || nif.isLoopback()) {
                continue;
            }
            for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                    return addr;
                }
            }
        }
        return null;
    }
}
