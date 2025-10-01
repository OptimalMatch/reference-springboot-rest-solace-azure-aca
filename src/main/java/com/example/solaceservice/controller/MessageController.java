package com.example.solaceservice.controller;

import com.example.solaceservice.model.MessageRequest;
import com.example.solaceservice.model.MessageResponse;
import com.example.solaceservice.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
@Slf4j
public class MessageController {

    private final MessageService messageService;

    @PostMapping
    public ResponseEntity<MessageResponse> sendMessage(@Valid @RequestBody MessageRequest request) {
        log.info("Received message request: {}", request);

        String messageId = UUID.randomUUID().toString();

        try {
            messageService.sendMessage(request, messageId);

            MessageResponse response = new MessageResponse(
                messageId,
                "SENT",
                request.getDestination(),
                LocalDateTime.now()
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to send message", e);

            MessageResponse response = new MessageResponse(
                messageId,
                "FAILED",
                request.getDestination(),
                LocalDateTime.now()
            );

            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Service is running");
    }
}