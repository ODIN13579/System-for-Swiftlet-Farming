
package com.swiftletcare.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.swiftletcare.alert.CameraHeartbeatService;
import com.swiftletcare.api.ApiError;

import jakarta.validation.Valid;

@RestController
public class AiPredatorController {

  private final AiPredatorService service;
  private final CameraHeartbeatService heartbeats;
  private final byte[] serviceKey;

  public AiPredatorController(
      AiPredatorService service,
      CameraHeartbeatService heartbeats,
      @Value("${app.ai-service-key}") String key
  ) {
    if (key.length() < 24)
      throw new IllegalArgumentException(
          "AI_SERVICE_KEY needs at least 24 characters"
      );

    this.service = service;
    this.heartbeats = heartbeats;
    this.serviceKey = key.getBytes(StandardCharsets.UTF_8);
  }

  private void requireServiceKey(String provided) {
    if (!MessageDigest.isEqual(
        serviceKey,
        provided.getBytes(StandardCharsets.UTF_8)
    )) {
      throw new ApiError.Failure(
          401, "UNAUTHORIZED", "Invalid AI service credential"
      );
    }
  }

  @PostMapping(
      path = "/api/ai/snapshots",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE
  )
  public ResponseEntity<AiPredatorService.SnapshotResult>
      uploadSnapshot(
          @RequestHeader(
              value = "X-Service-Key",
              defaultValue = ""
          ) String provided,
          @RequestParam("camera_id") long cameraId,
          @RequestParam("file") MultipartFile file
      ) throws IOException {

    requireServiceKey(provided);

    if (file.isEmpty()) {
      throw ApiError.bad("Snapshot is empty");
    }

    if (file.getSize() >
        AiPredatorService.MAX_IMAGE_BYTES) {
      throw new ApiError.Failure(
          413, "IMAGE_TOO_LARGE", "Snapshot exceeds 300 KiB"
      );
    }

    var result = service.saveSnapshot(
        cameraId,
        file.getContentType(),
        file.getBytes()
    );

    return ResponseEntity
        .status(HttpStatus.CREATED)
        .body(result);
  }

  @PostMapping("/api/ai/events")
  public ResponseEntity<AiPredatorService.EventResult>
      createEvent(
          @RequestHeader(
              value = "X-Service-Key",
              defaultValue = ""
          ) String provided,
          @Valid @RequestBody
              AiPredatorService.EventInput input
      ) {

    requireServiceKey(provided);

    var result = service.acceptEvent(input);

    return ResponseEntity.status(
        result.created() ? HttpStatus.CREATED : HttpStatus.OK
    ).body(result);
  }

  @PostMapping("/api/ai/heartbeat")
  public ResponseEntity<Void> heartbeat(
      @RequestHeader(value = "X-Service-Key", defaultValue = "") String provided,
      @Valid @RequestBody CameraHeartbeatService.Heartbeat input
  ) {
    requireServiceKey(provided);
    heartbeats.receive(input);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/snapshots/{id}")
  public ResponseEntity<byte[]> readSnapshot(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long id
  ) {
    var snapshot = service.readSnapshot(id, jwt);

    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "inline; filename=\"snapshot-" + id + "\""
        )
        .contentType(
            MediaType.parseMediaType(snapshot.mediaType())
        )
        .body(snapshot.bytes());
  }
}
