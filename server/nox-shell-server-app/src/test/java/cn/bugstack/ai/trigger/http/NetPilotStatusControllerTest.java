package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;
import cn.bugstack.ai.domain.ssh.service.netpilot.NetPilotStatusProbe;
import cn.bugstack.ai.infrastructure.adapter.port.NetPilotSshStatusProbe;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class NetPilotStatusControllerTest {
    private static final String PATH = "/api/v1/netpilot/devices/lab/services/web";
    private static final String TOKEN = "netpilot-local-fixture-aaaaaaaaaaaaaa";
    private static final String AUTH = "Bearer " + TOKEN;

    @Test
    public void disabledByDefault() {
        new ApplicationContextRunner().withUserConfiguration(ApiConfiguration.class).run(context -> {
            assertEquals(0, context.getBeanNamesForType(NetPilotStatusController.class).length);
            assertEquals(0, context.getBeanNamesForType(NetPilotSshStatusProbe.class).length);
        });
    }

    @Test
    public void requiresTokenAndMatchingDeviceAndServiceBeforeSsh() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MockMvc api = api(() -> {
            calls.incrementAndGet();
            return service("active");
        });
        api.perform(get(PATH)).andExpect(status().isUnauthorized());
        api.perform(get(PATH).header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        api.perform(get(PATH.replace("/lab/", "/other/")).header("Authorization", AUTH)).andExpect(status().isNotFound());
        api.perform(get(PATH.replace("/web", "/other")).header("Authorization", AUTH)).andExpect(status().isNotFound());
        api.perform(post(PATH).header("Authorization", AUTH)).andExpect(status().isMethodNotAllowed());
        assertEquals(0, calls.get());
    }

    @Test
    public void returnsOnlyStructuredScopedEvidence() throws Exception {
        api(() -> CommandExecutionResult.completed(0,
                "LoadState=loaded\nActiveState=inactive\nExtra=private_value\n", "private_stderr"))
                .perform(get(PATH).header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.source").value("final-test-ssh"))
                .andExpect(jsonPath("$.deviceId").value("lab"))
                .andExpect(jsonPath("$.serviceId").value("web"))
                .andExpect(jsonPath("$.status").value("observed"))
                .andExpect(jsonPath("$.serviceState").value("inactive"))
                .andExpect(jsonPath("$.evidenceId").isString())
                .andExpect(jsonPath("$.observedAt").isString())
                .andExpect(content().string(not(containsString("private_"))));
    }

    @Test
    public void distinguishesMissingServiceAndUnconfirmedResults() throws Exception {
        api(() -> CommandExecutionResult.completed(1, "LoadState=not-found\nActiveState=inactive\n", ""))
                .perform(get(PATH).header("Authorization", AUTH))
                .andExpect(jsonPath("$.serviceState").value("not_found"));
        api(() -> CommandExecutionResult.unknown("LoadState=loaded\nActiveState=active\n", "", "COMMAND_TIMEOUT"))
                .perform(get(PATH).header("Authorization", AUTH))
                .andExpect(jsonPath("$.status").value("unknown"))
                .andExpect(jsonPath("$.reason").value("ssh_timeout"));
        api(() -> CommandExecutionResult.notSent("SSH_CONNECTION_UNAVAILABLE", false))
                .perform(get(PATH).header("Authorization", AUTH))
                .andExpect(jsonPath("$.status").value("unavailable"))
                .andExpect(jsonPath("$.reason").value("ssh_unavailable"));
        api(() -> CommandExecutionResult.completed(0, "LoadState=loaded\nActiveState=healthy\n", ""))
                .perform(get(PATH).header("Authorization", AUTH))
                .andExpect(jsonPath("$.status").value("unknown"));
    }

    @Test
    public void rejectsConcurrentQueryUntilCurrentProbeReleasesSlot() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        NetPilotStatusController controller = controller(() -> {
            running.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) {
                    return CommandExecutionResult.notSent("TIMEOUT", false);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return service("active");
        });
        Thread first = new Thread(() -> controller.serviceStatus("lab", "web", AUTH));
        first.start();
        try {
            assertTrue(running.await(2, TimeUnit.SECONDS));
            assertEquals(429, controller.serviceStatus("lab", "web", AUTH).getStatusCode().value());
        } finally {
            release.countDown();
            first.join(3000);
        }
        assertEquals(200, controller.serviceStatus("lab", "web", AUTH).getStatusCode().value());
    }

    @Test
    public void realLoopbackHttpAndSshWhenFixtureProvided() throws Exception {
        String fixturePort = System.getenv("NETPILOT_FIXTURE_SSH_PORT");
        Assume.assumeTrue(fixturePort != null);
        verifyPythonProbe(fixturePort, System.getenv("NETPILOT_FIXTURE_KNOWN_HOSTS"), "active");
        verifyPythonProbe(fixturePort, System.getenv("NETPILOT_FIXTURE_OTHER_HOSTS"), "ssh_unavailable");
    }

    private void verifyPythonProbe(String sshPort, String hosts, String expected) throws Exception {
        try (var context = new AnnotationConfigServletWebServerApplicationContext()) {
            TestPropertyValues.of("nox-shell.netpilot.enabled=true",
                    "nox-shell.netpilot.token=" + TOKEN, "nox-shell.netpilot.device-id=lab",
                    "nox-shell.netpilot.service-id=web", "nox-shell.netpilot.systemd-unit=fixture.service",
                    "nox-shell.netpilot.ssh-host=127.0.0.1", "nox-shell.netpilot.ssh-port=" + sshPort,
                    "nox-shell.netpilot.ssh-user=fixture", "nox-shell.netpilot.ssh-known-hosts=" + hosts,
                    "nox-shell.netpilot.ssh-private-key=" + System.getenv("NETPILOT_FIXTURE_CLIENT_KEY")).applyTo(context);
            context.register(IsolatedHttp.class);
            context.refresh();
            Path skill = Path.of(System.getenv("NETPILOT_FIXTURE_CLI"));
            var process = new ProcessBuilder(System.getenv("NETPILOT_FIXTURE_PYTHON"), "-B", skill.toString(),
                    "remote-service", "--device", "lab", "--service", "web", "--authorize");
            process.environment().put("NETPILOT_REMOTE_URL", "http://127.0.0.1:" + context.getWebServer().getPort());
            process.environment().put("NETPILOT_REMOTE_TOKEN", TOKEN);
            Process query = process.start();
            try {
                assertTrue(query.waitFor(16, TimeUnit.SECONDS));
                String output = new String(query.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(output, 0, query.exitValue());
                assertTrue(output, output.contains("\"ok\": true") && output.contains("\"" + expected + "\""));
            } finally {
                if (query.isAlive()) {
                    query.destroyForcibly();
                }
            }
        }
    }

    private static MockMvc api(NetPilotStatusProbe probe) {
        return MockMvcBuilders.standaloneSetup(controller(probe)).build();
    }

    private static NetPilotStatusController controller(NetPilotStatusProbe probe) {
        return new NetPilotStatusController(TOKEN, "lab", "web", probe);
    }

    private static CommandExecutionResult service(String state) {
        return CommandExecutionResult.completed(0, "LoadState=loaded\nActiveState=" + state + "\n", "");
    }

    @Configuration(proxyBeanMethods = false)
    @Import({NetPilotStatusController.class, NetPilotSshStatusProbe.class})
    static class ApiConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(ApiConfiguration.class)
    static class IsolatedHttp {
        @Bean
        TomcatServletWebServerFactory webServer() throws Exception {
            var factory = new TomcatServletWebServerFactory(0);
            factory.setAddress(InetAddress.getByName("127.0.0.1"));
            return factory;
        }

        @Bean
        DispatcherServlet dispatcherServlet() {
            return new DispatcherServlet();
        }

        @Bean
        ServletRegistrationBean<DispatcherServlet> dispatcherRegistration(DispatcherServlet servlet) {
            return new ServletRegistrationBean<>(servlet, "/");
        }
    }
}
