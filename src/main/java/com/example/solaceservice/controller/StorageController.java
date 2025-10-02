package com.example.solaceservice.controller;

import com.example.solaceservice.model.MessageRequest;
import com.example.solaceservice.model.MessageResponse;
import com.example.solaceservice.model.StoredMessage;
import com.example.solaceservice.service.AzureStorageService;
import com.example.solaceservice.service.MessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/storage")
@Slf4j
public class StorageController {

    @Autowired(required = false)
    private AzureStorageService azureStorageService;

    @Autowired
    private MessageService messageService;

    @GetMapping("/messages")
    public ResponseEntity<?> listStoredMessages(@RequestParam(defaultValue = "50") int limit) {
        if (azureStorageService == null) {
            return ResponseEntity.ok().body("Azure Storage is not configured");
        }

        try {
            List<StoredMessage> messages = azureStorageService.listMessages(limit);
            return ResponseEntity.ok(messages);
        } catch (Exception e) {
            log.error("Failed to list stored messages", e);
            return ResponseEntity.internalServerError().body("Failed to list stored messages: " + e.getMessage());
        }
    }

    @GetMapping("/messages/{messageId}")
    public ResponseEntity<?> getStoredMessage(@PathVariable String messageId) {
        if (azureStorageService == null) {
            return ResponseEntity.ok().body("Azure Storage is not configured");
        }

        try {
            StoredMessage message = azureStorageService.retrieveMessage(messageId);
            if (message != null) {
                return ResponseEntity.ok(message);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("Failed to retrieve stored message: {}", messageId, e);
            return ResponseEntity.internalServerError().body("Failed to retrieve stored message: " + e.getMessage());
        }
    }

    @PostMapping("/messages/{messageId}/republish")
    public ResponseEntity<?> republishMessage(@PathVariable String messageId) {
        if (azureStorageService == null) {
            return ResponseEntity.ok().body("Azure Storage is not configured");
        }

        try {
            StoredMessage storedMessage = azureStorageService.retrieveMessage(messageId);
            if (storedMessage == null) {
                return ResponseEntity.notFound().build();
            }

            // Create a new message request from the stored message
            MessageRequest request = new MessageRequest();
            request.setContent(storedMessage.getContent());
            request.setDestination(storedMessage.getDestination());
            request.setCorrelationId(storedMessage.getCorrelationId());

            // Generate new message ID for republishing
            String newMessageId = UUID.randomUUID().toString();

            log.info("Republishing stored message {} with new ID: {}", messageId, newMessageId);

            // Send the message
            messageService.sendMessage(request, newMessageId);

            // Store the republished message as well
            StoredMessage newStoredMessage = StoredMessage.fromRequest(request, newMessageId, "REPUBLISHED");
            azureStorageService.storeMessage(newStoredMessage);

            MessageResponse response = new MessageResponse(
                newMessageId,
                "REPUBLISHED",
                storedMessage.getDestination(),
                LocalDateTime.now()
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to republish message: {}", messageId, e);
            return ResponseEntity.internalServerError().body("Failed to republish message: " + e.getMessage());
        }
    }

    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<?> deleteStoredMessage(@PathVariable String messageId) {
        if (azureStorageService == null) {
            return ResponseEntity.ok().body("Azure Storage is not configured");
        }

        try {
            boolean deleted = azureStorageService.deleteMessage(messageId);
            if (deleted) {
                return ResponseEntity.ok().body("Message deleted successfully");
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("Failed to delete stored message: {}", messageId, e);
            return ResponseEntity.internalServerError().body("Failed to delete stored message: " + e.getMessage());
        }
    }

    @GetMapping("/status")
    public ResponseEntity<String> getStorageStatus() {
        if (azureStorageService == null) {
            return ResponseEntity.ok("Azure Storage is disabled");
        } else {
            return ResponseEntity.ok("Azure Storage is enabled and ready");
        }
    }
}