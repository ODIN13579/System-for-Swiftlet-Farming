package com.swiftletcare.iot;

import java.net.URI;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ControlCommandController {
  private final ControlCommands commands;
  private final ObjectProvider<MqttGateway> mqtt;

  public ControlCommandController(ControlCommands commands, ObjectProvider<MqttGateway> mqtt) {
    this.commands = commands;
    this.mqtt = mqtt;
  }

  @PostMapping("/actuators/{actuatorId}/commands")
  public ResponseEntity<ControlReadController.CommandView> create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long actuatorId,
      @RequestBody ControlCommands.Request request) {
    var gateway = mqtt.getIfAvailable();
    var command = commands.create(jwt, actuatorId, request,
        gateway != null && gateway.controlReady());

    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/commands/" + command.commandId()))
        .body(command);
  }
}
