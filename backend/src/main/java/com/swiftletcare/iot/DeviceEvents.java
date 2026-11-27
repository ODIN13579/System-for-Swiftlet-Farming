
package com.swiftletcare.iot;

import java.time.Instant;
import java.util.Map;

import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.swiftletcare.alert.AlertEngine;
import com.swiftletcare.api.ApiError;

import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates firmware diagnostic events and forwards them
 * to the BUILD 3 Alert Engine.
 */
@Service
public class DeviceEvents {

    public record Event(
            @NotBlank @Size(max = 100) String device_id,
            @NotNull Instant timestamp,
            @NotNull
            @Pattern(regexp = "SENSOR_FAULT|SENSOR_RECOVERED|MANUAL_LIMIT")
            String event_type,
            @NotBlank @Size(max = 80) String sensor,
            @NotBlank @Size(max = 200) String reason
    ) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Validator validator;
    private final JsonMapper json;
    private final AlertEngine alertEngine;

    public DeviceEvents(
            JdbcTemplate jdbc,
            PlatformTransactionManager tm,
            Validator validator,
            JsonMapper json,
            AlertEngine alertEngine
    ) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.validator = validator;
        this.json = json;
        this.alertEngine = alertEngine;
    }

    public void accept(long house, long zone, Event p) {

        // 1. Validate incoming event.
        if (p == null
                || !validator.validate(p).isEmpty()
                || p.timestamp().isAfter(
                        Instant.now().plusSeconds(300))) {
            throw ApiError.bad("Invalid device event");
        }

        // 2. Verify device and its House/Zone.
        Long device = tx.execute(s -> {

            var ids = jdbc.queryForList("""
                select d.id
                from swiftletcare.devices d
                join swiftletcare.zones z
                    on z.id = d.zone_id
                join swiftletcare.houses h
                    on h.id = z.house_id
                where d.device_id = ?
                    and z.id = ?
                    and h.id = ?
                    and d.type = 'ESP32'
                    and d.enabled
                    and z.active
                    and h.active
                for share of h,z,d
                """,
                Long.class,
                p.device_id(),
                zone,
                house
            );

            if (ids.isEmpty()) {
                throw ApiError.bad(
                    "Device event source/topic mismatch or inactive resource"
                );
            }

            return ids.getFirst();
        });

        // 3. Send validated event to Alert Engine
        // after the transaction has committed.
        alertEngine.deviceEvent(
                zone,
                device,
                p.event_type(),
                p.sensor(),
                p.reason()
        );

        // 4. Structured diagnostic logging.
        // JSON encoding prevents log injection.
        LoggerFactory.getLogger(DeviceEvents.class).info(
            "{}",
            json.writeValueAsString(Map.of(
                "category", "device.event",
                "deviceId", device,
                "deviceCode", p.device_id(),
                "houseId", house,
                "zoneId", zone,
                "type", p.event_type(),
                "sensor", p.sensor(),
                "reason", p.reason(),
                "occurredAt", p.timestamp(),
                "receivedAt", Instant.now()
            ))
        );
    }
}
