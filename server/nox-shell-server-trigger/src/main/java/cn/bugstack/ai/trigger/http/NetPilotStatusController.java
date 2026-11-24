package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;
import cn.bugstack.ai.domain.ssh.service.netpilot.NetPilotStatusProbe;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/netpilot")
@ConditionalOnProperty(prefix = "nox-shell.netpilot", name = "enabled", havingValue = "true")
public class NetPilotStatusController {
    private static final Logger LOG = LoggerFactory.getLogger(NetPilotStatusController.class);
    private static final Set<String> ACTIVE_STATES =
            Set.of("active", "reloading", "inactive", "failed", "activating", "deactivating", "maintenance", "refreshing");

    private final byte[] authorization;
    private final String allowedDevice;
    private final String allowedService;
    private final NetPilotStatusProbe probe;
    private final Semaphore allowedCalls = new Semaphore(1);

    public NetPilotStatusController(
            @Value("${nox-shell.netpilot.token}") String token,
            @Value("${nox-shell.netpilot.device-id}") String allowedDevice,
            @Value("${nox-shell.netpilot.service-id}") String allowedService,
            NetPilotStatusProbe probe) {
        if (!token.matches("[A-Za-z0-9_-]{32,128}") || !validId(allowedDevice) || !validId(allowedService)) {
            throw new IllegalArgumentException("Invalid NetPilot token or resource identifiers");
        }
        this.authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.allowedDevice = allowedDevice;
        this.allowedService = allowedService;
        this.probe = probe;
    }

    @GetMapping(value = "/devices/{deviceId}/services/{serviceId}", produces = "application/json")
    public ResponseEntity<ServiceEvidence> serviceStatus(
            @PathVariable("deviceId") String deviceId,
            @PathVariable("serviceId") String serviceId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String header) {
        if (header == null || !MessageDigest.isEqual(authorization, header.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").build();
        }
        if (!allowedDevice.equals(deviceId) || !allowedService.equals(serviceId)) {
            return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build();
        }
        if (!allowedCalls.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore()).build();
        }
        try {
            CommandExecutionResult execution = probe.readServiceStatus();
            ServiceEvidence evidence = interpret(execution);
            LOG.info("NetPilot service_status evidence={} device={} service={} status={}",
                    evidence.evidenceId(), allowedDevice, allowedService, evidence.status());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(evidence);
        } finally {
            allowedCalls.release();
        }
    }

    private ServiceEvidence interpret(CommandExecutionResult execution) {
        if ("NOT_SENT".equals(execution.status())) {
            return evidence("unavailable", null, "ssh_unavailable");
        }
        if ("UNKNOWN".equals(execution.status())) {
            String reason = "COMMAND_TIMEOUT".equals(execution.failureType()) ? "ssh_timeout" : "ssh_result_unknown";
            return evidence("unknown", null, reason);
        }
        String loadState = property(execution.stdout(), "LoadState=");
        String activeState = property(execution.stdout(), "ActiveState=");
        if ("not-found".equals(loadState) && "inactive".equals(activeState)
                && (Integer.valueOf(0).equals(execution.exitCode()) || Integer.valueOf(1).equals(execution.exitCode()))) {
            return evidence("observed", "not_found", null);
        }
        if (Integer.valueOf(0).equals(execution.exitCode()) && "loaded".equals(loadState)
                && activeState != null && ACTIVE_STATES.contains(activeState)) {
            return evidence("observed", activeState, null);
        }
        return evidence("unknown", null, "systemd_status_unavailable");
    }

    private ServiceEvidence evidence(String status, String serviceState, String reason) {
        return new ServiceEvidence("1.0", UUID.randomUUID().toString(), "final-test-ssh", allowedDevice, allowedService,
                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(), status, serviceState, reason);
    }

    private static String property(String output, String prefix) {
        var values = output.lines().filter(line -> line.startsWith(prefix)).limit(2).toList();
        return values.size() == 1 ? values.get(0).substring(prefix.length()) : null;
    }

    private static boolean validId(String id) {
        return id.matches("[a-z0-9][a-z0-9-]{0,47}");
    }

    public record ServiceEvidence(String schemaVersion, String evidenceId, String source, String deviceId,
                                  String serviceId, String observedAt, String status, String serviceState,
                                  String reason) {
    }
}
