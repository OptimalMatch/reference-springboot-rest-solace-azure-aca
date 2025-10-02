package com.example.solaceservice.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoredMessage {
    private String messageId;
    private String content;
    private String destination;
    private String correlationId;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime timestamp;

    private String originalStatus;

    public static StoredMessage fromRequest(MessageRequest request, String messageId, String status) {
        return new StoredMessage(
            messageId,
            request.getContent(),
            request.getDestination(),
            request.getCorrelationId(),
            LocalDateTime.now(),
            status
        );
    }
}